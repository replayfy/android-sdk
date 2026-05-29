package com.replayfy.android.internal.capture

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * PixelCopy-backed screenshot — copies pixels from the actual
 * compositor output, NOT from the view-painting code path. Why
 * this matters: SurfaceView / TextureView / VideoView / ExoPlayer /
 * MapView / camera preview / GLSurfaceView all live on hardware-
 * composited surfaces that `view.draw(Canvas)` cannot see (those
 * regions paint as black with the Legacy pipeline). PixelCopy reads
 * the same pixels the user sees on screen, so those regions come
 * through correctly.
 *
 * Requires API 26+ for `PixelCopy.request(Window, ...)`. Async — the
 * OS reads from the framebuffer on a binder thread + calls back on
 * the [Handler] we provide. We own a dedicated [HandlerThread] so the
 * callback never lands on the main thread.
 *
 * Honors:
 *   - `FLAG_SECURE` — bails before requesting. PixelCopy itself
 *     returns ERROR_SOURCE_INVALID on secure windows anyway, but
 *     we save the round-trip.
 *   - `FLAG_DIM_BEHIND` — dim already exists in the compositor
 *     output, so PixelCopy captures it for free (unlike Legacy
 *     which has to pre-paint).
 *
 * Bitmap sizing matches [LegacyCapturePipeline] — both pipelines
 * produce the same-dimension Bitmap so downstream encoding +
 * privacy overlay work identically.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class PixelCopyCapturePipeline : CapturePipeline {

    override val name: String = "pixelcopy"

    // Nullable backing fields + a lock so shutdown() can tell whether
    // we ever booted a thread. `lazy` doesn't compose well with explicit
    // teardown because reading isInitialized still works fine but
    // touching .value materializes it — we want zero allocation when
    // the pipeline was never used.
    private val lock = Any()
    private var handlerThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    private fun handler(): Handler {
        // Double-checked init — most calls hit the cached path with
        // no synchronization.
        var h = callbackHandler
        if (h != null) return h
        synchronized(lock) {
            h = callbackHandler
            if (h == null) {
                val ht = HandlerThread("ReplayPixelCopy").also { it.start() }
                handlerThread = ht
                h = Handler(ht.looper)
                callbackHandler = h
            }
        }
        return h!!
    }

    override fun capture(
        root: View,
        window: Window?,
        params: WindowManager.LayoutParams?,
        onResult: (Bitmap?) -> Unit,
    ) {
        if (root.width <= 0 || root.height <= 0) { onResult(null); return }
        if (window == null) { onResult(null); return }
        if (params != null && (params.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            onResult(null); return
        }

        // Downsample at allocation time — cheaper than capturing full
        // res then scaling. Same heuristic + cap as Legacy.
        val scale = computeScale(root.width, root.height)
        val w = (root.width * scale).toInt().coerceAtLeast(1)
        val h = (root.height * scale).toInt().coerceAtLeast(1)

        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            android.util.Log.w(TAG, "PixelCopy: OOM allocating ${w}x${h} bitmap")
            onResult(null); return
        }

        try {
            PixelCopy.request(
                window,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        onResult(bitmap)
                    } else {
                        // Non-success codes: ERROR_DESTINATION_INVALID,
                        // ERROR_SOURCE_INVALID (secure), ERROR_SOURCE_NO_DATA,
                        // ERROR_TIMEOUT, ERROR_UNKNOWN. All mean the caller
                        // should fall back to Legacy. Recycle the bitmap
                        // since we're not handing it over.
                        android.util.Log.w(TAG, "PixelCopy result=$copyResult")
                        bitmap.recycle()
                        onResult(null)
                    }
                },
                handler(),
            )
        } catch (t: Throwable) {
            // PixelCopy throws IAE when source/dest disagree, IllegalStateException
            // when the window isn't attached. Always recycle + fall back.
            android.util.Log.w(TAG, "PixelCopy.request threw: ${t.message}")
            bitmap.recycle()
            onResult(null)
        }
    }

    /** Stop the background HandlerThread on SDK shutdown.
     *  Idempotent — safe to call when the thread was never started. */
    fun shutdown() {
        synchronized(lock) {
            handlerThread?.quitSafely()
            handlerThread = null
            callbackHandler = null
        }
    }

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
