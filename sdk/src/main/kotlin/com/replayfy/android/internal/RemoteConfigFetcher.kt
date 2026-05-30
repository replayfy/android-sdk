package com.replayfy.android.internal

import android.content.Context
import com.replayfy.android.ReplayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Periodically fetches the dashboard's `/v1/sdk/config` response,
 * caches it to SharedPreferences, and applies it to the SDK's
 * runtime state.
 *
 * # Lifecycle
 *
 *   1. Cold launch — load the LAST CACHED response (if any) from
 *      SharedPreferences + apply immediately. Customers whose
 *      offline app fires off-network get last-known-good behaviour
 *      instead of falling back to the customer-passed
 *      [ReplayConfig] defaults (which can drift from intent).
 *
 *   2. Async first fetch — within the first ~2 s of process start,
 *      hit `GET /v1/sdk/config`. Successful response overwrites the
 *      cache + reapplies the runtime state. Network failure leaves
 *      the cached config in place + schedules a retry.
 *
 *   3. Periodic re-fetch — every [REFRESH_INTERVAL_MS] (default 15
 *      min), pull fresh config. Customers flipping toggles in the
 *      dashboard see the change in at most one fetch cycle.
 *
 * # Override semantics
 *
 * The dashboard response **always wins** over the customer-passed
 * [ReplayConfig]. The customer's baseline becomes a fallback used
 * only when:
 *
 *   - The cold-launch cache is empty (first ever launch of the app
 *     with the SDK) AND the first fetch hasn't completed.
 *   - The customer set `useRemoteConfig = false` (escape hatch for
 *     customers who hard-code intentionally — kiosk apps, etc.).
 */
internal class RemoteConfigFetcher(
    context: Context,
    private val config: ReplayConfig,
    private val onApply: (RemoteConfig) -> Unit,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var loopJob: Job? = null

    /** OkHttp shared by every fetch — keeps the connection-pool
     *  warm across the periodic re-fetch cycle. */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Last applied config — null until first apply. Read by callers
     *  that need to ask "did the dashboard say X?" without a fetch
     *  round-trip. */
    @Volatile var current: RemoteConfig? = null
        private set

    /** Apply the cached response IMMEDIATELY (cold-launch path),
     *  then kick off the periodic fetch loop. Idempotent — repeat
     *  calls re-apply but don't double-schedule. */
    fun start() {
        // Cold-launch apply. Read + decode the cache; if anything is
        // off (corrupt JSON, missing fields), silently skip and wait
        // for the first network fetch to populate.
        loadCached()?.let {
            current = it
            onApply(it)
        }
        // Periodic loop — first iteration fires immediately so the
        // network truth lands ASAP, then sleeps for REFRESH_INTERVAL_MS
        // between iterations.
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                val fetched = fetchOnce()
                if (fetched != null) {
                    current = fetched
                    onApply(fetched)
                    saveCached(fetched)
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /** Stop the fetch loop. Called from [ReplayCore.stop] /
     *  cancelCurrentSession so we don't leak a coroutine after the
     *  SDK is shut down. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Synchronous fetch + parse. Returns null on any failure
     *  (network error, non-2xx response, parse failure). */
    private fun fetchOnce(): RemoteConfig? {
        val host = config.apiHost.trimEnd('/')
        val req = Request.Builder()
            .url("$host/v1/sdk/config")
            .header("x-replay-api-key", config.apiKey)
            .header("user-agent", buildUserAgent())
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w(
                        "ReplaySdk",
                        "RemoteConfig fetch HTTP ${resp.code} — keeping cached",
                    )
                    return null
                }
                val body = resp.body?.string() ?: return null
                RemoteConfig.parse(body, System.currentTimeMillis())
            }
        } catch (t: Throwable) {
            android.util.Log.w("ReplaySdk", "RemoteConfig fetch failed: ${t.message}")
            null
        }
    }

    private fun loadCached(): RemoteConfig? {
        val json = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        return RemoteConfig.parse(json, fetchedAt)
    }

    private fun saveCached(cfg: RemoteConfig) {
        prefs.edit()
            .putString(KEY_PAYLOAD, RemoteConfig.encode(cfg))
            .putLong(KEY_FETCHED_AT, cfg.fetchedAtMs)
            .apply()
    }

    private fun buildUserAgent(): String {
        return "${com.replayfy.android.BuildConfig.SDK_NAME}/" +
            com.replayfy.android.BuildConfig.SDK_VERSION +
            " (android)"
    }

    companion object {
        const val PREFS_NAME = "replay_remote_config_v1"
        const val KEY_PAYLOAD = "payload_json"
        const val KEY_FETCHED_AT = "fetched_at_ms"

        /** 15 minutes — matches UXCam + balances "customers see flips
         *  reasonably fast" against "we don't hammer the backend from
         *  every device in the field". */
        const val REFRESH_INTERVAL_MS: Long = 15 * 60 * 1000L
    }
}
