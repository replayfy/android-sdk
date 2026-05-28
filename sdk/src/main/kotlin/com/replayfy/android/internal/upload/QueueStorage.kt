package com.replayfy.android.internal.upload

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * On-disk queue of failed/pending batches. Each file is one
 * pre-serialized JSON body ready to POST to /v1/replay/batch.
 *
 * Lives under `context.filesDir/replay-queue/`. Files are named
 * `<seq>-<sessionId>.json` so the worker can:
 *   • drain in seq order (preserves the dashboard's session
 *     reconstruction sequence)
 *   • identify which session a queued batch belongs to without
 *     parsing the body
 *
 * Capacity-bounded: when there are more than MAX_FILES queued, the
 * OLDEST get dropped. Better to lose the cold tail of an old session
 * than to fill the user's disk and OOM.
 *
 * All file IO is synchronous — designed for callers already on
 * Dispatchers.IO. No coroutine plumbing here keeps the surface
 * small.
 */
internal class QueueStorage(context: Context) {

    private val dir: File = File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    /** Monotonic per-process sequence to keep filenames unique
     *  within a single launch (multiple batches can queue in the
     *  same millisecond). */
    private val perProcessSeq = AtomicLong(System.currentTimeMillis())

    /**
     * Persist a serialized batch. Caller passes the exact JSON body
     * BatchSender would have sent.
     *
     * Returns true on successful disk write, false on IO error
     * (disk full, permission, FS quirk). Callers should NOT retry —
     * if persistence fails, the batch is just lost. Better than
     * blocking the foreground hot path on retry loops.
     */
    fun enqueue(sessionId: String, json: String): Boolean {
        return try {
            evictOldestIfFull()
            val seq = perProcessSeq.incrementAndGet()
            val safeSession = sessionId.take(40).filter { it.isLetterOrDigit() || it == '_' }
            val file = File(dir, "$seq-$safeSession$FILE_EXT")
            // Write to a temp file first, then atomic-rename. Avoids
            // a corrupt file if the process is killed mid-write.
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        } catch (e: IOException) {
            android.util.Log.w(TAG, "queue enqueue failed: ${e.message}")
            false
        }
    }

    /**
     * List queued files in oldest-first order. WorkManager worker
     * iterates this list, POSTs each, deletes on success.
     */
    fun listOldestFirst(): List<File> {
        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(FILE_EXT)
        } ?: return emptyList()
        return files.sortedBy { it.name }
    }

    /** Delete one queued file. Called after successful upload. */
    fun delete(file: File): Boolean = try {
        file.delete()
    } catch (_: Throwable) { false }

    /** Number of queued files. Cheap call — just lists the dir. */
    fun size(): Int = dir.listFiles { f -> f.name.endsWith(FILE_EXT) }?.size ?: 0

    /** Wipe everything. Used on opt-out / GDPR-forget. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * If we're at capacity, delete the oldest file to make room.
     * Best-effort — never throw. Picked O(n) sort because the
     * common case is "well under cap, never triggers".
     */
    private fun evictOldestIfFull() {
        val files = dir.listFiles { f -> f.name.endsWith(FILE_EXT) } ?: return
        if (files.size < MAX_FILES) return
        files.sortedBy { it.name }.firstOrNull()?.delete()
    }

    private companion object {
        private const val TAG = "ReplaySdk"
        private const val DIR_NAME = "replay-queue"
        private const val FILE_EXT = ".json"
        /** Hard cap so a long offline streak can't fill the user's
         *  disk. ~500 batches × ~50 KB = 25 MB worst case. */
        private const val MAX_FILES = 500
    }
}
