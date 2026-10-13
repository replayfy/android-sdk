package com.replayfy.android.internal.mobile

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * A local stand-in for the mobile ingest API, so the engine runs end to end
 * (real MobileTransport, real /start) in the lifecycle tests. Each /start
 * opens session `sN` with token `tokN`. Just enough HTTP/1.1 for
 * HttpURLConnection: one request per connection, fixed-length bodies.
 */
internal class FakeIngestServer : AutoCloseable {
    class Request(val path: String, val auth: String?, val body: ByteArray)

    val requests = CopyOnWriteArrayList<Request>()
    /** Answer that endpoint with a 500 while set (the device is offline). */
    @Volatile var failImages = false
    @Volatile var failMessages = false
    @Volatile var failLate = false
    /** /start waits for this before answering (a slow first round-trip). */
    @Volatile var startGate: java.util.concurrent.CountDownLatch? = null
    /** Answer this many of the next /start requests with a 500. */
    val failStarts = AtomicInteger()

    private val sessions = AtomicInteger()
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    // Named, so a test counting the engine's threads can leave the server's out.
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, THREAD_NAME) }

    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        pool.execute {
            while (!socket.isClosed) {
                val conn = try { socket.accept() } catch (_: Throwable) { break }
                try { pool.execute { serve(conn) } } catch (_: Throwable) { conn.close() }
            }
        }
    }

    private fun serve(conn: Socket) = conn.use {
        val input = conn.getInputStream().buffered()
        val requestLine = readLine(input) ?: return
        val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: return
        var length = 0
        var auth: String? = null
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':').trim()
            if (name == "content-length") length = value.toInt()
            if (name == "authorization") auth = value
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }

        if (path == "/v1/mobile/start") startGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
        val failed = (path == "/v1/mobile/images" && failImages) ||
            (path == "/v1/mobile/i" && failMessages) ||
            (path == "/v1/mobile/late" && failLate) ||
            (path == "/v1/mobile/start" && failStarts.getAndUpdate { maxOf(it - 1, 0) } > 0)
        if (!failed) requests.add(Request(path, auth, body))
        val (code, reply) = when {
            failed -> 500 to "{}"
            path == "/v1/mobile/start" -> {
                val n = sessions.incrementAndGet()
                200 to """{"ok":true,"data":{"token":"tok$n","sessionID":"s$n","fps":3,""" +
                    """"quality":"low","framesSupport":true,"projectID":"p","userUUID":"u"}}"""
            }
            else -> 200 to "{}"
        }
        val bytes = reply.toByteArray()
        val out = conn.getOutputStream()
        out.write(
            ("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    fun on(path: String): List<Request> = requests.filter { it.path == path }

    /** Frames received on /images, across every archive. */
    val frameCount: Int get() = on("/v1/mobile/images").sumOf { frameTimestamps(it.body).size }

    /** Whether any delivered message batch (/i gzipped, /late raw) carries
     *  [text]; only batches sent with [auth] when given. */
    fun messagesContain(text: String, auth: String? = null): Boolean {
        val needle = text.toByteArray()
        val batches = on("/v1/mobile/i").filter { auth == null || it.auth == auth }
            .map { GZIPInputStream(ByteArrayInputStream(it.body)).use { s -> s.readBytes() } } +
            on("/v1/mobile/late").filter { auth == null || it.auth == auth }.map { it.body }
        return batches.any { indexOf(it, needle) >= 0 }
    }

    override fun close() {
        try { socket.close() } catch (_: Throwable) {}
        pool.shutdownNow()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    companion object {
        const val THREAD_NAME = "fake-ingest-server"
    }
}
