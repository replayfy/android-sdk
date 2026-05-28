package com.replayfy.android.internal.capture

import android.util.Base64
import com.replayfy.android.ReplayConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * Uploads snapshot bitmaps to `POST /v1/replay/assets/:hash` and
 * returns the resolved URL the player will fetch at render time.
 *
 * Content-addressed: the same image bytes always produce the same
 * hash, so we cache hash → URL locally and skip the round-trip on
 * subsequent snapshots that contain the same image (recurring icons,
 * loading spinners, app chrome).
 *
 * The cache lives in-memory per process, capped at MAX_CACHED
 * entries with LRU eviction. Persistent dedup (e.g. across cold
 * launches) isn't worth the complexity — most apps re-render the
 * same icons in the first frame anyway, the cache fills again
 * within seconds.
 *
 * Designed to be called from a background thread (IO dispatcher).
 * Blocks on the network round-trip. Caller treats this as best-
 * effort: a null return means "no imageRef for this snapshot, ship
 * tree-only".
 */
internal class AssetUploader(
    private val config: ReplayConfig,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS) // larger — uploads are bigger than downloads
        .retryOnConnectionFailure(true)
        .build()

    /** LRU cache: hash -> resolved URL (or null on permanent failure
     *  so we don't retry forever on the same broken hash). Synced
     *  via Collections.synchronizedMap rather than ConcurrentHashMap
     *  because LinkedHashMap.removeEldestEntry is the easiest way to
     *  get LRU semantics. */
    private val cache: MutableMap<String, String?> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String?>?): Boolean {
                return size > MAX_CACHED
            }
        }
    )

    /**
     * Upload the asset and return the resolved URL. Returns the
     * cached URL when the hash has been uploaded before; uploads
     * fresh otherwise.
     *
     * Returns null when:
     *   - upload failed (network error, non-2xx response, malformed body)
     *   - storage is disabled on the backend (returns ok:false)
     *
     * In all null cases, the snapshot ships without imageRef and the
     * player falls back to wireframe rendering for this frame.
     */
    fun uploadOrCached(asset: BitmapCapture.EncodedAsset): String? {
        cache[asset.hash]?.let { return it }
        val url = doUpload(asset)
        // Cache even nulls — avoids re-attempting an upload that the
        // backend has explicitly refused (R2 disabled, key revoked).
        cache[asset.hash] = url
        return url
    }

    private fun doUpload(asset: BitmapCapture.EncodedAsset): String? {
        return try {
            val body = JSONObject().apply {
                put("dataUrl", buildDataUrl(asset))
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url(joinUrl(config.apiHost, "/v1/replay/assets/${asset.hash}"))
                .post(body)
                .header("x-replay-api-key", config.apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.string() ?: return null
                parseUrl(text)
            }
        } catch (t: Throwable) {
            android.util.Log.v(TAG, "asset upload failed: ${t.message}")
            null
        }
    }

    /**
     * Backend's endpoint accepts a base64 data URL (same format as
     * the thumbnail endpoint). Encoding base64 adds ~33% overhead vs
     * binary multipart — fine for snapshot-sized payloads (typically
     * 30-200 KB), saves the backend from needing a separate
     * multipart parser path.
     */
    private fun buildDataUrl(asset: BitmapCapture.EncodedAsset): String {
        val b64 = Base64.encodeToString(asset.bytes, Base64.NO_WRAP)
        return "data:${asset.contentType};base64,$b64"
    }

    /**
     * Backend response shape: { ok, data: { ok, url, hash } }. We
     * pull `data.url` when present, returns null otherwise.
     * Defensive — never throw on a misshapen response.
     */
    private fun parseUrl(body: String): String? {
        return try {
            val outer = JSONObject(body)
            val data = outer.optJSONObject("data") ?: return null
            val ok = data.optBoolean("ok", false)
            if (!ok) return null
            data.optString("url").takeIf { it.isNotEmpty() }
        } catch (_: Throwable) { null }
    }

    private fun joinUrl(host: String, path: String): String {
        val h = host.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return h + p
    }

    private companion object {
        private const val TAG = "ReplaySdk"
        private const val MAX_CACHED = 256
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
