package com.replayfy.android.internal.capture

import android.graphics.Bitmap
import android.util.Base64
import com.replayfy.android.ReplayConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Posts the FIRST captured bitmap of each session to
 * `POST /v1/replay/thumbnail` — populates the dashboard's Recordings
 * list thumbnail for native Android sessions.
 *
 * The thumbnail endpoint already exists (used by the web SDK's
 * rrweb-rendered first-frame thumbnail). For native we just call
 * it once per session with a downsized JPEG of the first snapshot
 * bitmap.
 *
 * Once-per-session: AtomicBoolean flips after the first successful
 * upload. Subsequent snapshots in the same session skip this path.
 * ReplayCore calls [reset] when a new session starts so the flag
 * goes back to `false` for the new session's first snapshot.
 *
 * Mirrors the iOS ``ThumbnailUploader.swift``.
 */
internal class ThumbnailUploader(
    private val config: ReplayConfig,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val alreadySent = AtomicBoolean(false)

    /** Target longest-edge for the thumbnail. Matches the web SDK's
     *  recordings-list rendering size (cards are ~160-320 wide on
     *  most viewports). Keeps payload tiny. */
    private val targetLongestEdge: Int = 320

    /** JPEG quality (0..100). Thumbnails don't need lossless;
     *  JPEG at 70 yields ~5-10 KB per thumbnail vs ~50 KB for PNG. */
    private val jpegQuality: Int = 70

    /** Reset the once-per-session flag — called by ReplayCore when
     *  a new session starts. */
    fun reset() {
        alreadySent.set(false)
    }

    /**
     * Send the bitmap as the session's thumbnail. No-op if a
     * thumbnail was already sent for this session, or if [source]
     * is unsuitable.
     *
     * IMPORTANT: does NOT recycle [source]. The caller's bitmap is
     * still owned by the caller. We make our own scaled copy
     * internally and recycle that.
     *
     * Synchronous on the calling thread — caller should invoke
     * from a background coroutine (which SnapshotCapture already
     * does).
     */
    fun sendIfFirst(source: Bitmap, sessionPublicId: String) {
        if (!alreadySent.compareAndSet(false, true)) return
        try {
            val bytes = encodeThumbnail(source) ?: run {
                // Flip back so next snapshot retries.
                alreadySent.set(false)
                return
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$b64"
            val body = JSONObject().apply {
                put("sessionPublicId", sessionPublicId)
                put("dataUrl", dataUrl)
            }.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url(joinUrl(config.apiHost, "/v1/replay/thumbnail"))
                .post(body)
                .header("x-replay-api-key", config.apiKey)
                .build()
            client.newCall(request).execute().use { response ->
                // Backend returns 200 even when R2 is disabled in dev
                // (response body has ok:false). We don't bother
                // parsing — best-effort, dashboard falls back to a
                // blank card if the URL never lands on Session.
                if (!response.isSuccessful) {
                    // Server-side rejection → flip flag back so the
                    // NEXT snapshot retries. Avoids a one-shot loss
                    // when the customer's API key was momentarily
                    // invalid (e.g. just-rotated).
                    alreadySent.set(false)
                }
            }
        } catch (t: Throwable) {
            // Network error — flip flag back so next snapshot
            // retries.
            alreadySent.set(false)
            android.util.Log.v(TAG, "thumbnail upload failed: ${t.message}")
        }
    }

    /** Downsize the source bitmap to thumbnail dimensions and
     *  re-encode as JPEG. Preserves aspect ratio. Returns null on
     *  encode failure. Recycles the intermediate scaled bitmap;
     *  leaves [source] alone. */
    private fun encodeThumbnail(source: Bitmap): ByteArray? {
        if (source.width <= 0 || source.height <= 0) return null
        val longest = maxOf(source.width, source.height)
        // Scale factor — 1.0 if already small enough.
        val scale = if (longest > targetLongestEdge) {
            targetLongestEdge.toFloat() / longest
        } else 1f
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)

        val scaled: Bitmap = try {
            if (scale == 1f) source
            else Bitmap.createScaledBitmap(source, w, h, /* filter */ true)
        } catch (oom: OutOfMemoryError) {
            android.util.Log.w(TAG, "thumbnail OOM at ${w}x${h}")
            return null
        }

        return try {
            val baos = ByteArrayOutputStream(8 * 1024)
            val ok = scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
            if (!ok) null else baos.toByteArray()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "thumbnail encode failed: ${t.message}")
            null
        } finally {
            // Only recycle the scaled copy if we made one; the
            // source bitmap belongs to the caller.
            if (scaled !== source) {
                try { scaled.recycle() } catch (_: Throwable) {}
            }
        }
    }

    private fun joinUrl(host: String, path: String): String {
        val h = host.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return h + p
    }

    private companion object {
        private const val TAG = "ReplaySdk"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
