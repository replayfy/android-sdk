package com.replayfy.android.internal.mobile

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk copy of a session's final message batch — the one that ends the
 * session via /late — and of any batch a session end could not send. The
 * reference tracker's late-messages file: the batch is written before it is
 * sent and deleted once the server accepts it, so a batch that never got out
 * (offline at session end, or the process died mid-send) is resent on the
 * next launch.
 *
 * Two differences from the reference. It resends under whatever token the NEW
 * launch holds, which files the batch into the new session (and on our backend
 * /late would also end that new session); each file here carries the token of
 * the session it belongs to. And the resend is a plain /i batch, not /late:
 * the batch's messages are what was missing, while the session itself has
 * normally been ended by the server's idle timer long before the next launch
 * (see [MobileTransport.sendSavedMessages]).
 *
 * File = `writeUTF(token)` + the raw (un-gzipped) batch, written to a temp
 * file and renamed into place so a process killed mid-write never leaves a
 * torn batch. All IO is synchronous; callers are background threads.
 */
class MobileLateMessages(private val dir: File) {
    private val seq = AtomicLong()

    /** Persist [batch] for the session [token] belongs to. Returns the file to
     *  [delete] once the server accepts it, or null if the write failed (the
     *  send still goes ahead; it just isn't covered). */
    fun save(token: String, batch: ByteArray): File? {
        return try {
            dir.mkdirs()
            evictOldest()
            // Zero-padded epoch-ms, then a counter for two writes in one
            // millisecond, so the names sort in write order.
            val name = String.format(java.util.Locale.ROOT, "late-%013d-%06d", System.currentTimeMillis(), seq.incrementAndGet())
            val file = File(dir, name + EXT)
            val tmp = File(dir, file.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use {
                it.writeUTF(token)
                it.write(batch)
            }
            if (tmp.renameTo(file)) file else { tmp.delete(); null }
        } catch (_: Throwable) {
            null
        }
    }

    fun delete(file: File) {
        try { file.delete() } catch (_: Throwable) {}
    }

    /** Batches waiting to be resent, oldest first. */
    fun pending(): List<File> =
        try {
            dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }.orEmpty().sortedBy { it.name }
        } catch (_: Throwable) {
            emptyList()
        }

    /** Resend [files] (from an earlier launch) via [send], deleting each the
     *  server accepts. One that fails stays for the next launch; the rest are
     *  still tried, so one batch the server keeps refusing can't hold back
     *  the others. An unreadable file is dropped rather than retried forever. */
    fun resend(files: List<File>, send: (batch: ByteArray, token: String) -> Boolean) {
        for (file in files) {
            val entry = read(file)
            if (entry == null) {
                delete(file)
                continue
            }
            val ok = try { send(entry.second, entry.first) } catch (_: Throwable) { false }
            if (ok) delete(file)
        }
    }

    private fun read(file: File): Pair<String, ByteArray>? =
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val token = input.readUTF()
                val batch = input.readBytes()
                if (token.isEmpty() || batch.isEmpty()) null else token to batch
            }
        } catch (_: Throwable) {
            null
        }

    /** Bounded: a device that stays offline across many session ends keeps
     *  the newest batches rather than filling the disk. */
    private fun evictOldest() {
        val files = pending()
        if (files.size < MAX_FILES) return
        files.take(files.size - MAX_FILES + 1).forEach { delete(it) }
    }

    private companion object {
        const val EXT = ".dat"
        const val MAX_FILES = 10
    }
}
