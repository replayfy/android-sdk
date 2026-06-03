package com.replayfy.android.internal.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.replayfy.android.BuildConfig
import com.replayfy.android.ReplayConfig
import com.replayfy.android.internal.BatchSender
import java.util.concurrent.TimeUnit

/**
 * Drains the on-disk queue ([QueueStorage]) by POSTing each
 * persisted batch to /v1/replay/batch.
 *
 * WorkManager invokes this in the host app's process when its
 * constraints are met (network connected). Survives process death,
 * device reboot, doze mode.
 *
 * Why not a plain Service: a Service can be killed alongside the
 * process. WorkManager guarantees execution by persisting the work
 * request via its own SQLite database — even if the SDK never gets
 * re-initialized, the worker fires.
 *
 * Configuration comes through WorkData rather than a singleton
 * because Workers run in arbitrary process states (could be the
 * app's first launch after a kill, before [LegacyCore.init] has
 * landed).
 */
internal class SessionUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val apiKey = inputData.getString(KEY_API_KEY)
        val apiHost = inputData.getString(KEY_API_HOST)
        if (apiKey.isNullOrBlank() || apiHost.isNullOrBlank()) {
            // Worker was scheduled without credentials — nothing we
            // can do. Don't retry: the SDK will re-schedule next
            // launch with correct config.
            return Result.failure()
        }

        val storage = QueueStorage(applicationContext)
        val files = storage.listOldestFirst()
        if (files.isEmpty()) return Result.success()

        // Spin up a transport with the worker-provided config. Can't
        // reuse the live BatchSender because this might be running
        // before the SDK has init'd (or in a separate worker process
        // depending on AGP settings).
        val sender = BatchSender(
            ReplayConfig(apiKey = apiKey, apiHost = apiHost),
        )

        var anyFailed = false
        for (file in files) {
            val json = try {
                file.readText()
            } catch (t: Throwable) {
                // Corrupt or unreadable file — delete it so we don't
                // get stuck retrying. Lost batch is acceptable; we
                // log so customer-success can correlate gaps.
                android.util.Log.w(TAG, "queued file unreadable, dropping: ${file.name}")
                storage.delete(file)
                continue
            }
            val ok = sender.sendJson(
                json = json,
                sdkName = BuildConfig.SDK_NAME,
                sdkVersion = BuildConfig.SDK_VERSION,
            )
            if (ok) {
                storage.delete(file)
            } else {
                anyFailed = true
                // Don't try the rest of the queue this run — likely
                // the same network condition that killed this one
                // will kill them too. WorkManager will retry per
                // its backoff schedule.
                break
            }
        }

        return if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "ReplaySdk"
        const val UNIQUE_NAME = "replay-session-upload"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_HOST = "api_host"

        /**
         * Schedule a one-shot drain. Called from [LegacyCore.init]
         * to pick up any batches queued during a previous process
         * lifetime, AND from the end-of-session hook so the last
         * batch reliably ships even if the user backgrounds the app
         * during the network round-trip.
         *
         * Uses [ExistingWorkPolicy.KEEP] so concurrent calls don't
         * pile up duplicate workers — the existing one continues.
         */
        fun schedule(context: Context, config: ReplayConfig) {
            val data = Data.Builder()
                .putString(KEY_API_KEY, config.apiKey)
                .putString(KEY_API_HOST, config.apiHost)
                .build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SessionUploadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS,
                )
                .build()
            try {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            } catch (t: Throwable) {
                // WorkManager.getInstance can throw IllegalStateException
                // if the host app has a custom WorkManager Configuration
                // that we initialized in the wrong order. Best-effort —
                // queued files remain on disk and next launch will try
                // again.
                android.util.Log.w(TAG, "WorkManager schedule failed: ${t.message}")
            }
        }
    }
}
