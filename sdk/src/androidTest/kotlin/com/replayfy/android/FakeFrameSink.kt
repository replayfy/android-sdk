package com.replayfy.android

import com.replayfy.android.internal.mobile.MobileUploading
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Collects the frames the screenshotter uploads, on a device. Every send
 * succeeds; the gzipped `[u64 ts][u32 size][jpeg]` archives are unpacked
 * into their JPEGs.
 */
class FakeFrameSink : MobileUploading {
    private val lock = Any()
    private val jpegs = ArrayList<ByteArray>()

    override val token: String? = "tok-device"
    override fun sendMessages(gzippedBatch: ByteArray): Boolean = true
    override fun sendLateMessages(batch: ByteArray): Boolean = true

    override fun sendImages(gzippedArchive: ByteArray): Boolean {
        val raw = GZIPInputStream(ByteArrayInputStream(gzippedArchive)).use { it.readBytes() }
        val out = ArrayList<ByteArray>()
        var off = 0
        while (off + 12 <= raw.size) {
            var len = 0
            for (i in 0 until 4) len = len or ((raw[off + 8 + i].toInt() and 0xff) shl (8 * i))
            val end = minOf(off + 12 + len, raw.size)
            out.add(raw.copyOfRange(off + 12, end))
            off += 12 + len
        }
        synchronized(lock) { jpegs.addAll(out) }
        return true
    }

    /** Every JPEG received so far, in order. */
    val frames: List<ByteArray> get() = synchronized(lock) { ArrayList(jpegs) }
}
