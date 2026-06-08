package com.replayfy.android.internal.capture

import android.os.Build
import android.view.Window

/**
 * Runtime decision: which [CapturePipeline] should handle this frame?
 *
 * Decision matrix:
 *   - API ≥ 26 AND a [Window] is available → [PixelCopyCapturePipeline]
 *     (sees SurfaceView / TextureView / VideoView / MapView / ExoPlayer
 *     / camera preview / GLSurfaceView correctly)
 *   - Otherwise → [LegacyCapturePipeline]
 *     (`view.draw` — software path, can't see hardware surfaces)
 *
 * Both pipelines produce the same Bitmap dimensions + the same
 * colour space, so downstream encoding + privacy overlay code does
 * NOT need to know which one ran.
 *
 * Single instance per process — both pipelines are stateless on the
 * frame level, and [PixelCopyCapturePipeline] caches its
 * [android.os.HandlerThread] across calls.
 *
 * Mirrors the reference mobile SDK's `CapturePipelineSelector` (which adds a third
 * SurfaceControl-experimental pipeline for API 30+ — we don't ship
 * that path yet; PixelCopy covers the same hardware-surface gap).
 */
internal object CapturePipelineSelector {

    private val legacy = LegacyCapturePipeline()

    // Lazy because constructing the PixelCopy pipeline pre-API-26 is
    // a compile-time error (@RequiresApi). Materialised once on first
    // use, kept for the SDK lifetime.
    private val pixelCopy: PixelCopyCapturePipeline? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PixelCopyCapturePipeline()
        } else null
    }

    /** Pick a pipeline for the given source window.
     *
     *  @param window  The hosting [Window], when known. Required for
     *                 PixelCopy — if null, we fall back to Legacy
     *                 because PixelCopy.request(Window, ...) needs
     *                 one. Pass null for dialog / popup roots whose
     *                 Window we cannot reach. */
    fun selectFor(window: Window?): CapturePipeline {
        val pc = pixelCopy
        return if (pc != null && window != null) pc else legacy
    }

    /** Release the PixelCopy HandlerThread on SDK shutdown. */
    fun shutdown() {
        pixelCopy?.shutdown()
    }
}
