package com.replayfy.android.internal.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Best-effort whole-screen bitmap capture. Single strategy in v1
 * (Legacy View.draw on a Canvas-backed Bitmap) — runs synchronously
 * on the main thread, no PixelCopy callbacks to coordinate around.
 *
 * Limitations vs the modern PixelCopy path (follow-up commit):
 *   • Cannot capture SurfaceView / TextureView / VideoView content
 *     (those are hardware-composited; only PixelCopy sees the
 *     surface). Such regions appear as black or the parent's
 *     background.
 *   • Runs on main thread — for very large windows this can drop
 *     a frame. Mitigated by the downsampling step below.
 *
 * What it does:
 *   1. Allocate an ARGB_8888 Bitmap sized to the root view (capped
 *      at MAX_DIMENSION on the longest edge for memory + upload
 *      size — most native UIs render fine at 1024 px wide).
 *   2. Wrap it in a Canvas, optionally scale.
 *   3. Pre-paint with the background color if the window has
 *      FLAG_DIM_BEHIND set (so playback shows the dim correctly
 *      under dialogs).
 *   4. Call view.draw(canvas) — the same painting code Android
 *      uses for every frame.
 *   5. Return the Bitmap to the caller.
 */
internal object BitmapCapture {

    /** Cap on the longest edge of the captured bitmap. Above this we
     *  downsample at capture time, before painting — cheaper than
     *  drawing full-res and scaling after. */
    private const val MAX_DIMENSION = 1024

    /** PNG compression quality. PNG is lossless so the parameter is
     *  only the encoder's effort level (0..100); 90 = good balance. */
    private const val PNG_QUALITY = 90

    /** Returns null when the view has no measured size (not yet
     *  laid out) or capture failed. */
    fun capture(
        root: View,
        params: WindowManager.LayoutParams? = null,
    ): Bitmap? {
        if (root.width <= 0 || root.height <= 0) return null
        // Skip secure windows entirely — they contain content the OS
        // explicitly forbids capturing (banking app pin pads,
        // DRM-protected video, etc.). Same flag the OS itself
        // enforces against MediaProjection.
        if (params != null && (params.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            return null
        }

        val scale = computeScale(root.width, root.height)
        val w = (root.width * scale).toInt().coerceAtLeast(1)
        val h = (root.height * scale).toInt().coerceAtLeast(1)

        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            // Soft-fail rather than crashing the host app. Snapshot
            // ships as tree-only this frame.
            android.util.Log.w(TAG, "OOM allocating ${w}x${h} bitmap")
            return null
        }

        val canvas = Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)

        // FLAG_DIM_BEHIND honored: pre-paint a black overlay at the
        // window's dimAmount so dialogs+dim show in playback
        // (matches what the user sees on screen).
        if (params != null && (params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
            val alpha = (255 * params.dimAmount).toInt().coerceIn(0, 255)
            canvas.drawARGB(alpha, 0, 0, 0)
        }

        // Collect privacy-view bounds BEFORE drawing so they're
        // computed against the live view hierarchy on the main
        // thread. The paint pass below runs in the same scope so
        // the rects are still valid.
        val privacyRects =
            com.replayfy.android.internal.privacy.PrivacyRegistry
                .sensitiveBounds(root)

        return try {
            root.draw(canvas)
            // Paint a noise-pattern overlay over each privacy view's
            // bounds AFTER view.draw so it sits on top of the captured
            // pixels. The overlay only ever exists in the saved
            // bitmap — never visible on the user's screen.
            if (privacyRects.isNotEmpty()) {
                paintPrivacyOverlay(canvas, privacyRects)
            }
            bitmap
        } catch (t: Throwable) {
            // View painting can throw (custom Views with bugs, OEM
            // quirks). Recycle + bail rather than crashing.
            android.util.Log.w(TAG, "view.draw failed: ${t.message}")
            bitmap.recycle()
            null
        }
    }

    /** Diagonal-stripe pattern over privacy regions. Cheaper than a
     *  real noise pattern (no per-pixel cost) and visually obvious
     *  in playback — viewers immediately recognise the "this region
     *  was hidden" signal. Mirrors the iOS implementation. */
    private fun paintPrivacyOverlay(
        canvas: android.graphics.Canvas,
        rects: List<android.graphics.Rect>,
    ) {
        val fill = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(255, 38, 38, 38)
            style = android.graphics.Paint.Style.FILL
        }
        val stripe = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(255, 89, 89, 89)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
        }
        val spacing = 8f
        for (rect in rects) {
            // Solid dark base so any pixel that leaked through draw()
            // is overwritten.
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

    private const val TAG = "ReplaySdk"

    /** Output of [encodeAndHash] — bytes + the SHA-256 hex hash +
     *  MIME for the asset upload endpoint. */
    data class EncodedAsset(
        val bytes: ByteArray,
        val hash: String,
        val contentType: String,
    )
}
