package com.replayfy.android.internal.mobile

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Packs JPEG screenshots into the frames-archive format the backend
 * appends per session: a flat concatenation of
 *   [uint64 LE timestamp][uint32 LE size][jpeg bytes]
 * gzip-compressed (java.util.zip.GZIPOutputStream emits the 1f 8b
 * gzip framing the backend's gunzip requires).
 */
object MobileFrames {
    /** `frames` is an ordered list of (jpegBytes, epochMs). */
    fun archive(frames: List<Pair<ByteArray, Long>>): ByteArray? {
        if (frames.isEmpty()) return null
        val binary = ByteArrayOutputStream()
        for ((jpeg, ts) in frames) {
            writeUInt64LE(binary, ts)
            writeUInt32LE(binary, jpeg.size.toLong())
            binary.write(jpeg)
        }
        return gzip(binary.toByteArray())
    }

    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun writeUInt64LE(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 8) out.write(((v ushr (i * 8)) and 0xff).toInt())
    }

    private fun writeUInt32LE(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 4) out.write(((v ushr (i * 8)) and 0xff).toInt())
    }
}
