package com.replayfy.android.internal.capture

import android.graphics.Bitmap
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Strategy interface for taking a screenshot of an Android window.
 *
 * Two production implementations:
 *
 *   - [LegacyCapturePipeline] — `view.draw(Canvas)` on a Bitmap-
 *     backed Canvas. Synchronous, works on every API level, but
 *     CANNOT see hardware-composited surfaces (SurfaceView,
 *     TextureView, VideoView, ExoPlayer, MapView, camera preview).
 *     Those regions appear black in the captured Bitmap.
 *
 *   - [PixelCopyCapturePipeline] — `PixelCopy.request(Window, ...)`,
 *     the modern Android API that copies pixels from the actual
 *     framebuffer composition. SEES hardware surfaces correctly.
 *     Requires API 26+ and a non-null [Window]. Async — the callback
 *     fires on a background HandlerThread we own.
 *
 * [CapturePipelineSelector] picks one at runtime based on what the
 * source view + window can support.
 *
 * Mirrors the architecture the reference mobile SDK ships (CapturePipelineSelector +
 * FrameCapturePipeline interface + multiple pipelines) — without
 * which any customer using video / maps / camera / ExoPlayer ships
 * black snapshots from us.
 *
 * The result is the RAW captured Bitmap. Privacy overlays and any
 * downstream transforms are layered on by the caller after the
 * pipeline returns.
 */
internal interface CapturePipeline {

    /** Short identifier for logs / telemetry. */
    val name: String

    /**
     * Capture the pixels of [root]. Returns via [onResult] — null on
     * any failure (secure window, OOM, unsupported source, timeout).
     *
     * Threading: [onResult] may fire on the calling thread (Legacy)
     * OR on a background HandlerThread (PixelCopy). The caller MUST
     * NOT touch View state in the callback unless it re-dispatches to
     * main.
     *
     * @param root       The root [View] to capture (typically the
     *                   activity's decor view).
     * @param window     The [Window] that hosts [root]. PixelCopy
     *                   requires this; Legacy ignores it. Null when
     *                   the source is an overlay/dialog whose Window
     *                   we cannot reach — forces Legacy.
     * @param params     LayoutParams of the root window, used to honor
     *                   FLAG_SECURE + FLAG_DIM_BEHIND.
     * @param onResult   Receives the captured Bitmap or null. The
     *                   caller takes ownership of recycling.
     */
    fun capture(
        root: View,
        window: Window?,
        params: WindowManager.LayoutParams?,
        onResult: (Bitmap?) -> Unit,
    )
}
