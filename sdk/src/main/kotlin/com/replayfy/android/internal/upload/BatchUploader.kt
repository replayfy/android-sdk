package com.replayfy.android.internal.upload

import android.content.Context
import com.replayfy.android.ReplayConfig
import com.replayfy.android.internal.BatchSender
import com.replayfy.android.internal.ReplayBatchEnvelope

/**
 * Outbound batch pipeline. Tries the live HTTP path first; on
 * failure, persists the serialized JSON to [QueueStorage] so
 * [SessionUploadWorker] can drain it later (next foreground,
 * WorkManager constraints permitting).
 *
 * Why this layer rather than putting the fallback inside
 * [BatchSender]:
 *   1. Keeps [BatchSender] purely transport — easier to test the
 *      pure send path in isolation.
 *   2. Lets the worker re-use BatchSender directly without
 *      accidentally re-queuing already-queued bytes.
 *   3. Makes the live-vs-persisted decision a one-line wrap in
 *      [ReplayCore], rather than scattered conditionals.
 *
 * Designed for [Dispatchers.IO]-only callers — the disk write +
 * network call are both blocking.
 */
internal class BatchUploader(
    private val context: Context,
    private val config: ReplayConfig,
    private val sender: BatchSender,
    private val storage: QueueStorage = QueueStorage(context),
) {

    /**
     * Send the envelope. Returns true on live success, false when
     * we fell back to queueing (or if even queueing failed).
     */
    /**
     * Callback fired the first time a batch successfully reaches
     * the backend. Used by [com.replayfy.android.internal.ReplayCore]
     * to drain the verification-listener list registered via
     * `Replay.addVerificationListener` — mirrors UXCam's
     * `VerificationListener.onVerificationSuccess`.
     *
     * One-shot: after firing once the closure is cleared so
     * subsequent uploads don't re-notify. Set to non-null when
     * the customer registers a listener; cleared on first success.
     */
    @Volatile
    var onFirstUploadSuccess: (() -> Unit)? = null

    fun upload(envelope: ReplayBatchEnvelope): Boolean {
        val ok = sender.send(envelope)
        if (ok) {
            // Fire the verification-success callback exactly once.
            // Read-and-clear under a lock so concurrent uploads
            // can't double-fire if the customer registers a
            // listener mid-batch.
            val cb = synchronized(this) {
                val c = onFirstUploadSuccess
                onFirstUploadSuccess = null
                c
            }
            cb?.invoke()
            return true
        }

        // Live send failed — serialize once and persist. Caller's
        // already-serialized JSON would be nicer but the existing
        // SessionRuntime hand-off uses typed envelopes; serialize
        // here keeps the rest of the SDK ignorant of JSON.
        val json = try {
            sender.serialize(envelope)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "serialize for queue failed: ${t.message}")
            return false
        }
        val queued = storage.enqueue(envelope.sessionId, json)
        if (queued) {
            // Nudge WorkManager so the queue drains as soon as the
            // network is back, rather than waiting until next app
            // launch. Idempotent — schedule() uses uniqueWork.
            SessionUploadWorker.schedule(context, config)
        }
        return false
    }

    /**
     * Drain any pre-existing queue on init. Called once from
     * [ReplayCore.init] to pick up batches persisted during a
     * previous process lifetime. Doesn't block init — runs as
     * a WorkManager job in the background.
     */
    fun scheduleDrain() {
        if (storage.size() > 0) {
            SessionUploadWorker.schedule(context, config)
        }
    }

    /** Wipe the on-disk queue. Used on opt-out / GDPR-forget. */
    fun clearQueue() {
        storage.clear()
    }

    private companion object {
        private const val TAG = "ReplaySdk"
    }
}
