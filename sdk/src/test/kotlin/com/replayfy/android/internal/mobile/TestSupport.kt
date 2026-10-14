package com.replayfy.android.internal.mobile

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** Poll [condition] until it holds or [timeoutMs] passes (the drains run on
 *  their own threads). */
internal fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
}

/** Every thread alive right now. */
internal fun liveThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.isAlive }.toSet()

/** The threads started since [before] that are still alive, by name, leaving
 *  out the fake ingest server's own and daemon threads: the JVM starts its
 *  graphics service threads (AppKit, Java2D) on the first decoded bitmap,
 *  while every thread the engine starts (executor workers, HandlerThreads
 *  made from the main thread) is a non-daemon one. */
internal fun threadsStartedSince(before: Set<Thread>): List<String> =
    liveThreads()
        .filter { it !in before && !it.isDaemon && it.name != FakeIngestServer.THREAD_NAME }
        .map { it.name }

/** Timestamps of the frames in one gzipped `[u64 ts][u32 size][jpeg]` archive. */
internal fun frameTimestamps(gz: ByteArray): List<Long> = frames(gz).map { it.first }

/** The JPEGs in one gzipped frames archive, in order. */
internal fun frameJpegs(gz: ByteArray): List<ByteArray> = frames(gz).map { it.second }

private fun frames(gz: ByteArray): List<Pair<Long, ByteArray>> {
    val raw = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
    val out = ArrayList<Pair<Long, ByteArray>>()
    var off = 0
    while (off + 12 <= raw.size) {
        var ts = 0L
        for (i in 0 until 8) ts = ts or ((raw[off + i].toLong() and 0xff) shl (8 * i))
        var len = 0
        for (i in 0 until 4) len = len or ((raw[off + 8 + i].toInt() and 0xff) shl (8 * i))
        val end = minOf(off + 12 + len, raw.size)
        out.add(ts to raw.copyOfRange(off + 12, end))
        off += 12 + len
    }
    return out
}

/**
 * Records uploads and answers them synchronously after a delay, like the HTTP
 * transport does on the buffers' sender threads.
 */
internal class FakeUploader(override val token: String? = "tok-1") : MobileUploading {
    private val lock = Any()
    private val archives = ArrayList<ByteArray>()
    private val batches = ArrayList<ByteArray>()
    private val lates = ArrayList<ByteArray>()
    private val imageResults = ArrayDeque<Boolean>()
    private val messageResults = ArrayDeque<Boolean>()

    /** Fail every send of that kind while set (the device is offline). */
    @Volatile var failImages = false
    @Volatile var failMessages = false
    @Volatile var failLate = false
    /** How long an upload takes before its outcome is reported. */
    @Volatile var imageDelayMs = 10L
    @Volatile var messageDelayMs = 10L

    /** Outcomes for the next sends, in order; once empty every send succeeds. */
    fun queueImageResults(vararg results: Boolean) = synchronized(lock) { imageResults.addAll(results.toList()) }
    fun queueMessageResults(vararg results: Boolean) = synchronized(lock) { messageResults.addAll(results.toList()) }

    val imageFrameCounts: List<Int> get() = synchronized(lock) { archives.map { frameTimestamps(it).size } }
    val imageJpegs: List<ByteArray> get() = synchronized(lock) { archives.flatMap { frameJpegs(it) } }
    val imageTimestamps: List<Long> get() = synchronized(lock) { archives.flatMap { frameTimestamps(it) } }
    val messageBatchCount: Int get() = synchronized(lock) { batches.size }
    val lateBatches: List<ByteArray> get() = synchronized(lock) { ArrayList(lates) }

    override fun sendImages(gzippedArchive: ByteArray): Boolean {
        val ok = synchronized(lock) {
            val ok = !failImages && (imageResults.removeFirstOrNull() ?: true)
            if (ok) archives.add(gzippedArchive)
            ok
        }
        Thread.sleep(imageDelayMs)
        return ok
    }

    override fun sendMessages(gzippedBatch: ByteArray): Boolean {
        val ok = synchronized(lock) {
            val ok = !failMessages && (messageResults.removeFirstOrNull() ?: true)
            if (ok) batches.add(gzippedBatch)
            ok
        }
        Thread.sleep(messageDelayMs)
        return ok
    }

    override fun sendLateMessages(batch: ByteArray): Boolean {
        val ok = synchronized(lock) {
            if (!failLate) lates.add(batch)
            !failLate
        }
        Thread.sleep(10)
        return ok
    }
}
