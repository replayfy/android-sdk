package com.replayfy.android.internal.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicInteger

/**
 * PixelCopy-backed screenshot — copies pixels from the actual
 * compositor output, NOT from the view-painting code path. Why
 * this matters: SurfaceView / TextureView / VideoView / ExoPlayer /
 * MapView / camera preview / GLSurfaceView all live on hardware-
 * composited surfaces that `view.draw(Canvas)` cannot see (those
 * regions paint as black with the Legacy pipeline).
 *
 * Two-pass capture for SurfaceView correctness:
 *
 *   1. `PixelCopy.request(Window, ...)` reads the window's
 *      framebuffer — captures every regular View accurately, but
 *      leaves SurfaceView regions transparent (default Z-order
 *      "hole-punches" the window's surface; the SurfaceView's
 *      pixels live on a SEPARATE compositor layer that the
 *      Window-based PixelCopy variant cannot reach). This was
 *      validated end-to-end on emulator with diagonal-strip tests
 *      that showed 9% opacity in the SurfaceView region after a
 *      single Window pass.
 *
 *   2. For every SurfaceView in the tree, do an additional
 *      `PixelCopy.request(SurfaceView, ...)` which DOES reach the
 *      SurfaceView's own surface. Composite each result onto the
 *      main bitmap at the SurfaceView's window-relative position.
 *
 * UXCam ships the equivalent multi-pass for WebView and similar
 * hardware surfaces — visible in their decompiled
 * PixelCopyCapturePipeline (e.g. `webViewDeferred` / `webViewRects`
 * branches around `performPixelCopyCapture`).
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
 *     output, so PixelCopy captures it for free.
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

    /**
     * Plan for one SurfaceView to capture in the second pass.
     * Position + size are snapshotted on the main thread BEFORE
     * pipeline dispatch — view metrics aren't safe to read from the
     * PixelCopy callback thread. We keep the SurfaceView reference
     * because `PixelCopy.request(SurfaceView, ...)` needs the live
     * instance; if the view is detached by callback time PixelCopy
     * just errors and we skip that view harmlessly.
     */
    private data class SurfaceViewPlan(
        val view: SurfaceView,
        val xInWindow: Int,
        val yInWindow: Int,
        val w: Int,
        val h: Int,
    )

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

        // SECOND-PASS prep: walk the view tree NOW (on the calling
        // main thread) to find every SurfaceView + capture its
        // window-relative bounds. We MUST do this on main — view
        // metrics aren't safe to read from the PixelCopy callback
        // thread.
        val surfaceViewPlans = try {
            collectSurfaceViewPlans(root)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "SurfaceView discovery failed: ${t.message}")
            emptyList()
        }

        try {
            PixelCopy.request(
                window,
                bitmap,
                { copyResult ->
                    if (copyResult != PixelCopy.SUCCESS) {
                        // Non-success codes: ERROR_DESTINATION_INVALID,
                        // ERROR_SOURCE_INVALID (secure), ERROR_SOURCE_NO_DATA,
                        // ERROR_TIMEOUT, ERROR_UNKNOWN. All mean the
                        // caller should fall back to Legacy. Recycle
                        // the bitmap since we're not handing it over.
                        android.util.Log.w(TAG, "PixelCopy result=$copyResult")
                        bitmap.recycle()
                        onResult(null)
                        return@request
                    }
                    // First pass done — bitmap holds the View tree.
                    // Now run per-SurfaceView PixelCopy + composite.
                    if (surfaceViewPlans.isEmpty()) {
                        onResult(bitmap)
                    } else {
                        captureSurfaceViewsAndComposite(
                            mainBitmap = bitmap,
                            scale = scale,
                            plans = surfaceViewPlans,
                            onAllDone = { onResult(bitmap) },
                        )
                    }
                },
                handler(),
            )
        } catch (t: Throwable) {
            // PixelCopy throws IAE when source/dest disagree,
            // IllegalStateException when the window isn't attached.
            // Always recycle + fall back.
            android.util.Log.w(TAG, "PixelCopy.request threw: ${t.message}")
            bitmap.recycle()
            onResult(null)
        }
    }

    /**
     * Pass 2: for each SurfaceView, PixelCopy its own surface into a
     * temporary bitmap, then composite onto [mainBitmap] at the
     * window-relative position recorded in the plan (scaled to match
     * the main bitmap's downsampled coordinate system).
     *
     * Sequential rather than parallel — most apps have 0-1
     * SurfaceViews per screen; the simpler control flow is worth more
     * than the marginal wall-clock win of overlapping PixelCopy
     * requests.
     *
     * Runs entirely on our [callbackHandler]'s thread — same thread
     * the caller is on when this is invoked from the main PixelCopy
     * success callback.
     */
    private fun captureSurfaceViewsAndComposite(
        mainBitmap: Bitmap,
        scale: Float,
        plans: List<SurfaceViewPlan>,
        onAllDone: () -> Unit,
    ) {
        val remaining = AtomicInteger(plans.size)
        // Single Canvas shared across composites — saves the
        // allocation per SurfaceView. Canvas + Bitmap are thread-
        // confined to this method's invocation chain so concurrent
        // access isn't a concern.
        val canvas = Canvas(mainBitmap)
        if (scale != 1f) canvas.scale(scale, scale)

        for (plan in plans) {
            if (plan.w <= 0 || plan.h <= 0) {
                if (remaining.decrementAndGet() == 0) onAllDone()
                continue
            }
            val svBitmap = try {
                Bitmap.createBitmap(plan.w, plan.h, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                android.util.Log.w(TAG, "SV PixelCopy: OOM ${plan.w}x${plan.h}")
                if (remaining.decrementAndGet() == 0) onAllDone()
                continue
            }
            try {
                PixelCopy.request(
                    plan.view,
                    svBitmap,
                    { svResult ->
                        if (svResult == PixelCopy.SUCCESS) {
                            try {
                                // Composite at window-relative coords;
                                // canvas.scale() above mapped us into
                                // root-pixel space (same as the rect
                                // values), so plan coordinates land
                                // exactly where the View occupies on
                                // the main bitmap.
                                canvas.drawBitmap(
                                    svBitmap,
                                    plan.xInWindow.toFloat(),
                                    plan.yInWindow.toFloat(),
                                    null,
                                )
                            } catch (t: Throwable) {
                                android.util.Log.w(TAG, "SV composite failed: ${t.message}")
                            }
                        } else {
                            android.util.Log.w(TAG, "SV PixelCopy result=$svResult — leaving region as-is")
                        }
                        try { svBitmap.recycle() } catch (_: Throwable) {}
                        if (remaining.decrementAndGet() == 0) onAllDone()
                    },
                    handler(),
                )
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "SV PixelCopy.request threw: ${t.message}")
                try { svBitmap.recycle() } catch (_: Throwable) {}
                if (remaining.decrementAndGet() == 0) onAllDone()
            }
        }
    }

    /**
     * Walk the view tree from [root] depth-first. For every
     * visible, non-zero-size SurfaceView, record its window-relative
     * bounds. Called from the main thread.
     *
     * We deliberately skip hidden + zero-size SurfaceViews — they
     * won't contribute to the captured frame regardless and would
     * just waste a PixelCopy round-trip.
     */
    private fun collectSurfaceViewPlans(root: View): List<SurfaceViewPlan> {
        val out = ArrayList<SurfaceViewPlan>(2)
        walkSurfaceViews(root, out)
        return out
    }

    private fun walkSurfaceViews(v: View, out: MutableList<SurfaceViewPlan>) {
        if (!v.isShown) return
        if (v is SurfaceView) {
            if (v.width > 0 && v.height > 0) {
                val loc = IntArray(2)
                v.getLocationInWindow(loc)
                out.add(SurfaceViewPlan(v, loc[0], loc[1], v.width, v.height))
            }
            // SurfaceViews don't have children we care about — skip
            // recursing into them.
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i) ?: continue
                walkSurfaceViews(child, out)
            }
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
