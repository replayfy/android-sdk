package com.replayfy.android.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mobile-relevant subset of the dashboard's `/v1/sdk/config` response.
 *
 * The full backend response carries fields meaningful only for the
 * web SDK (canvas / iframe / blockSelectors), which we drop on
 * parse. Everything kept here maps to a real toggle / behaviour in
 * the mobile SDKs:
 *
 *   - `capture.*`   — runtime toggles consulted on every emit /
 *                     session start.
 *   - `sampling.*`  — random-gate at session start that lets
 *                     customers throttle volume per workspace
 *                     without touching the SDK's source.
 *   - `privacy.*`   — bulk-occlusion + URL-redact rules applied
 *                     before bitmap + network events leave the
 *                     device.
 *   - `minDurationMs` — drops the session at close when nothing
 *                       meaningful happened.
 *
 * **Override semantics.** This config ALWAYS wins over the
 * customer-passed [com.replayfy.android.ReplayConfig]. The
 * customer's baseline becomes a fallback used only when the
 * remote fetch hasn't completed yet (cold-launch first-fetch race)
 * AND no cached prior response exists.
 */
internal data class RemoteConfig(
    val workspaceId: Long?,
    val captureConsole: Boolean,
    val captureNetwork: Boolean,
    val captureNetworkHeaders: Boolean,
    val captureNetworkBodies: Boolean,
    val captureErrors: Boolean,
    val capturePerformance: Boolean,
    val minDurationMs: Long,
    val samplingRate: Double,
    val samplingAlwaysRecordErrors: Boolean,
    val samplingAlwaysRecordIdentified: Boolean,
    val privacyMaskAllInputs: Boolean,
    val privacyRedactUrlPatterns: List<String>,
    val retentionDays: Int,
    val fetchedAtMs: Long,
) {
    companion object {
        /** Parse the JSON body returned by `GET /v1/sdk/config`. Tolerant
         *  of missing fields — every property falls back to a sensible
         *  default so an old-version backend doesn't break the SDK. */
        fun parse(json: String, fetchedAtMs: Long): RemoteConfig? {
            return try {
                val root = JSONObject(json)
                val capture = root.optJSONObject("capture")
                val sampling = root.optJSONObject("sampling")
                val privacy = root.optJSONObject("privacy")
                RemoteConfig(
                    workspaceId = if (root.has("workspaceId")) root.getLong("workspaceId") else null,
                    captureConsole = capture?.optBoolean("console", true) ?: true,
                    captureNetwork = capture?.optBoolean("network", false) ?: false,
                    captureNetworkHeaders = capture?.optBoolean("networkHeaders", false) ?: false,
                    captureNetworkBodies = capture?.optBoolean("networkBodies", false) ?: false,
                    captureErrors = capture?.optBoolean("errors", true) ?: true,
                    capturePerformance = capture?.optBoolean("performance", true) ?: true,
                    minDurationMs = (capture?.optLong("minDurationMs", 5_000L) ?: 5_000L)
                        .coerceAtLeast(0L),
                    samplingRate = (sampling?.optDouble("rate", 1.0) ?: 1.0).coerceIn(0.0, 1.0),
                    samplingAlwaysRecordErrors = sampling?.optBoolean("alwaysRecordErrors", true) ?: true,
                    samplingAlwaysRecordIdentified = sampling?.optBoolean("alwaysRecordIdentified", true) ?: true,
                    privacyMaskAllInputs = privacy?.optBoolean("maskAllInputs", false) ?: false,
                    privacyRedactUrlPatterns = (privacy?.optJSONArray("redactUrlPatterns"))
                        ?.toStringList() ?: emptyList(),
                    retentionDays = root.optInt("retentionDays", 30),
                    fetchedAtMs = fetchedAtMs,
                )
            } catch (t: Throwable) {
                android.util.Log.w("ReplaySdk", "RemoteConfig.parse failed: ${t.message}")
                null
            }
        }

        /** Serialize for SharedPreferences cache. Kept as JSON for
         *  cross-version compatibility — a future field addition
         *  doesn't break parsing of cached entries. */
        fun encode(cfg: RemoteConfig): String {
            val root = JSONObject()
            root.put("workspaceId", cfg.workspaceId ?: JSONObject.NULL)
            root.put("capture", JSONObject().apply {
                put("console", cfg.captureConsole)
                put("network", cfg.captureNetwork)
                put("networkHeaders", cfg.captureNetworkHeaders)
                put("networkBodies", cfg.captureNetworkBodies)
                put("errors", cfg.captureErrors)
                put("performance", cfg.capturePerformance)
                put("minDurationMs", cfg.minDurationMs)
            })
            root.put("sampling", JSONObject().apply {
                put("rate", cfg.samplingRate)
                put("alwaysRecordErrors", cfg.samplingAlwaysRecordErrors)
                put("alwaysRecordIdentified", cfg.samplingAlwaysRecordIdentified)
            })
            root.put("privacy", JSONObject().apply {
                put("maskAllInputs", cfg.privacyMaskAllInputs)
                put("redactUrlPatterns", JSONArray(cfg.privacyRedactUrlPatterns))
            })
            root.put("retentionDays", cfg.retentionDays)
            return root.toString()
        }

        private fun JSONArray.toStringList(): List<String> {
            val out = ArrayList<String>(length())
            for (i in 0 until length()) {
                out.add(optString(i, ""))
            }
            return out.filter { it.isNotEmpty() }
        }
    }
}
