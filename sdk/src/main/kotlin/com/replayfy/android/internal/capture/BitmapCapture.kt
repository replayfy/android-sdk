package com.replayfy.android.internal.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.replayfy.android.internal.privacy.PrivacyRegistry
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Façade over the pluggable [CapturePipeline] strategies. Picks a
 * pipeline via [CapturePipelineSelector], runs it, paints the
 * privacy overlay over the result, and hands the Bitmap to the
 * caller via callback.
 *
 * Why a façade — async + privacy must coordinate:
 *   - Pipelines vary in threading. Legacy returns synchronously on
 *     the calling thread; PixelCopy returns asynchronously on a
 *     background HandlerThread.
 *   - Privacy rects MUST be computed on the main thread BEFORE
 *     dispatch (View.getLocationOnScreen / isShown / width / height
 *     are only safe to read from main).
 *   - The painted overlay MUST be applied AFTER the pipeline writes
 *     pixels (otherwise the view-draw or PixelCopy result paints
 *     over our overlay).
 *
 * Flow:
 *   1. (caller thread, main) Compute privacy rects + capture scale
 *      against the live View tree.
 *   2. (caller thread) Dispatch the pipeline.
 *   3. (callback thread, may be background) Apply privacy overlay
 *      to the returned Bitmap, then invoke [onResult].
 *
 * The overlay step uses [Canvas] on the Bitmap — safe off-main
 * because the Bitmap is OUR allocation and no one else holds a
 * reference to it.
 *
 * [encodeAndHash] remains synchronous + must be called on background
 * — it allocates a large byte[] + runs SHA-256.
 */
internal object BitmapCapture {

    /** Cap on the longest edge of the captured bitmap. Pipelines
     *  apply the same cap internally; we mirror it here so the
     *  privacy-overlay canvas matches what the pipeline produced. */
    private const val MAX_DIMENSION = 1024

    /** PNG compression effort (0..100). 90 = good size/quality balance. */
    private const val PNG_QUALITY = 90

    private const val TAG = "ReplaySdk"

    /**
     * Capture pixels asynchronously through the selected pipeline,
     * paint the privacy overlay, then invoke [onResult].
     *
     * @param root      Root View whose pixels to capture.
     * @param window    Hosting [Window], when known. Pass
     *                  `activity.window` for activity decor roots;
     *                  null for dialog / popup roots whose Window we
     *                  cannot reach (forces Legacy).
     * @param params    LayoutParams of the root window — pipelines
     *                  use it to honor FLAG_SECURE + FLAG_DIM_BEHIND.
     * @param onResult  Receives the captured + occlusion-painted
     *                  Bitmap, or null on any failure. May fire on a
     *                  background thread; receiver takes ownership
     *                  of recycling.
     */
    fun captureAsync(
        root: View,
        window: Window?,
        params: WindowManager.LayoutParams?,
        onResult: (Bitmap?) -> Unit,
    ) {
        if (root.width <= 0 || root.height <= 0) { onResult(null); return }

        // Compute privacy rects + scale on main BEFORE pipeline
        // dispatch. The pipeline's callback may land on a background
        // thread where View walking is unsafe.
        val privacyRects = try {
            // Whole-screen-occlude opt-in (industry-standard): replace
            // the per-view rect set with a single rect covering the
            // entire root. Hides absolutely everything regardless
            // of class-based opt-ins.
            if (PrivacyRegistry.occludeAllScreen) {
                // Drain pending bridge rects even on full-screen path
                // so they don't carry into the NEXT (non-full-screen)
                // snapshot. Result discarded.
                PrivacyRegistry.consumePendingFrameRects()
                listOf(android.graphics.Rect(0, 0, root.width, root.height))
            } else {
                // Combine explicit per-view marks + Compose-marker
                // bounds + bulk class-based bounds (TextView /
                // EditText when occludeAllText* flags are set) +
                // one-shot bridge-supplied rects (RN / Flutter) —
                // consumed here so back-to-back snapshots don't
                // double-paint.
                PrivacyRegistry.sensitiveBounds(root) +
                    PrivacyRegistry.composeBoundsRelativeTo(root) +
                    PrivacyRegistry.bulkBounds(root) +
                    PrivacyRegistry.consumePendingFrameRects()
            }
        } catch (t: Throwable) {
            // Privacy lookup should never throw, but soft-fail
            // rather than killing the whole capture.
            android.util.Log.w(TAG, "privacy lookup failed: ${t.message}")
            emptyList()
        }
        val scale = computeScale(root.width, root.height)

        val pipeline = CapturePipelineSelector.selectFor(window)
        pipeline.capture(root, window, params) { bitmap ->
            if (bitmap == null) { onResult(null); return@capture }
            if (privacyRects.isNotEmpty()) {
                try {
                    // New Canvas has no transform. Apply the same
                    // scale the pipeline used internally so the rects
                    // (in root-pixel coords) land at the correct
                    // location in the (possibly downscaled) Bitmap.
                    val canvas = Canvas(bitmap)
                    if (scale != 1f) canvas.scale(scale, scale)
                    paintPrivacyOverlay(canvas, privacyRects)
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "overlay paint failed: ${t.message}")
                    // Pixels are still good for non-sensitive
                    // regions; ship them rather than dropping the
                    // whole frame. Revisit if leakage is reported.
                }
            }
            onResult(bitmap)
        }
    }

    /** Synchronous Legacy-only capture path. Kept for debug /
     *  tooling that can't do async. DO NOT use for production
     *  snapshots — bypasses PixelCopy, so video / maps / camera
     *  render black. */
    @Suppress("unused")
    fun captureLegacy(root: View, params: WindowManager.LayoutParams?): Bitmap? {
        return LegacyCapturePipeline().captureSync(root, params)
    }

    /** Diagonal-stripe pattern over privacy regions. Cheaper than a
     *  real noise pattern (no per-pixel cost) and visually obvious
     *  in playback — viewers immediately recognise the "this region
     *  was hidden" signal. Mirrors the iOS implementation. */
    private fun paintPrivacyOverlay(
        canvas: Canvas,
        rects: List<android.graphics.Rect>,
    ) {
        val fill = android.graphics.Paint().apply {
            color = Color.argb(255, 38, 38, 38)
            style = android.graphics.Paint.Style.FILL
        }
        val stripe = android.graphics.Paint().apply {
            color = Color.argb(255, 89, 89, 89)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
        }
        val spacing = 8f
        for (rect in rects) {
            // Solid dark base so any pixel that leaked through the
            // pipeline is overwritten.
            canvas.drawRect(rect, fill)
            // Diagonal stripes at 45° spaced 8px apart. Clip to the
            // rect so strokes don't bleed.
            canvas.save()
            canvas.clipRect(rect)
            val diag = rect.width() + rect.height()
            var x = rect.left.toFloat() - rect.height()
            while (x < rect.left + diag) {
                canvas.drawLine(
                    x, rect.top.toFloat(),
                    x + rect.height(), rect.bottom.toFloat(),
                    stripe,
                )
                x += spacing
            }
            canvas.restore()
        }
    }

    /** Encode the bitmap to PNG bytes + compute SHA-256 in one pass.
     *  Bitmap is recycled afterwards — caller doesn't reuse it. */
    fun encodeAndHash(bitmap: Bitmap): EncodedAsset? {
        return try {
            val baos = ByteArrayOutputStream(estimateInitialSize(bitmap))
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, baos)
            if (!ok) return null
            val bytes = baos.toByteArray()
            val hash = sha256Hex(bytes)
            EncodedAsset(bytes = bytes, hash = hash, contentType = "image/png")
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "encode failed: ${t.message}")
            null
        } finally {
            try { bitmap.recycle() } catch (_: Throwable) {}
        }
    }

    /** Solid color when the view has no background — used by the
     *  player to draw a backdrop while pixel bytes are loading. */
    @Suppress("unused")
    fun guessBackgroundColor(root: View): Int =
        when (val bg = root.background) {
            is android.graphics.drawable.ColorDrawable -> bg.color
            else -> Color.WHITE
        }

    private fun computeScale(w: Int, h: Int): Float {
        val longest = maxOf(w, h)
        if (longest <= MAX_DIMENSION) return 1f
        return MAX_DIMENSION.toFloat() / longest
    }

    private fun estimateInitialSize(bitmap: Bitmap): Int {
        // Rough heuristic — PNGs of UI screenshots compress to
        // ~10-20% of raw pixel bytes. Pre-size the stream so we
        // don't grow it 5 times during compression.
        return (bitmap.width * bitmap.height * 4) / 8
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val sb = StringBuilder(64)
        for (b in digest) {
            val i = b.toInt() and 0xFF
            sb.append(HEX[i ushr 4]).append(HEX[i and 0xF])
        }
        return sb.toString()
    }

    private val HEX = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    )

    /** Output of [encodeAndHash] — bytes + the SHA-256 hex hash +
     *  MIME for the asset upload endpoint. */
    data class EncodedAsset(
        val bytes: ByteArray,
        val hash: String,
        val contentType: String,
    )
}
