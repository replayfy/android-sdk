package com.replayfy.android.internal.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Synchronous `view.draw(Canvas)` capture — the only strategy that
 * works on every API level (and the only one available pre-API 26).
 *
 * The painting code path here is the SAME one Android invokes every
 * vsync; it produces pixel-perfect output for software-composited
 * Views (everything except SurfaceView / TextureView / VideoView /
 * MediaPlayer / Camera2 / MapView / GLSurfaceView / ExoPlayer).
 *
 * Honors:
 *   - `FLAG_SECURE` — bails entirely (matches the OS's own
 *     MediaProjection refusal).
 *   - `FLAG_DIM_BEHIND` — pre-paints a black overlay at the window's
 *     dimAmount so dialogs+dim show in playback.
 *
 * Downsampling to [MAX_DIMENSION] before drawing keeps memory + the
 * eventual PNG byte count bounded. Most native UIs render fine at
 * 1024 px on the longest edge.
 */
internal class LegacyCapturePipeline : CapturePipeline {

    override val name: String = "legacy"

    override fun capture(
        root: View,
        window: Window?,
        params: WindowManager.LayoutParams?,
        onResult: (Bitmap?) -> Unit,
    ) {
        onResult(captureSync(root, params))
    }

    /** Exposed for callers that want the sync return — internal use. */
    fun captureSync(root: View, params: WindowManager.LayoutParams?): Bitmap? {
        if (root.width <= 0 || root.height <= 0) return null
        // Secure windows: OS forbids capture — we honor it.
        if (params != null && (params.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            return null
        }

        val scale = computeScale(root.width, root.height)
        val w = (root.width * scale).toInt().coerceAtLeast(1)
        val h = (root.height * scale).toInt().coerceAtLeast(1)

        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            android.util.Log.w(TAG, "Legacy: OOM allocating ${w}x${h} bitmap")
            return null
        }

        val canvas = Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)

        if (params != null && (params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
            val alpha = (255 * params.dimAmount).toInt().coerceIn(0, 255)
            canvas.drawARGB(alpha, 0, 0, 0)
        }

        return try {
            root.draw(canvas)
            bitmap
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Legacy: view.draw failed: ${t.message}")
            bitmap.recycle()
            null
        }
    }

    /** Returns the scale factor used so the caller can scale
     *  privacy overlay rects accordingly. */
    fun scaleFor(width: Int, height: Int): Float = computeScale(width, height)

    private fun computeScale(w: Int, h: Int): Float {
        val longest = maxOf(w, h)
        if (longest <= MAX_DIMENSION) return 1f
        return MAX_DIMENSION.toFloat() / longest
    }

    companion object {
        const val MAX_DIMENSION = 1024
        private const val TAG = "ReplaySdk"
    }
}
