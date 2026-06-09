package com.replayfy.android

import android.graphics.Rect

/**
 * How a masked (privacy-sensitive) region is rendered in the recording.
 * Mirrors the reference mobile SDK's occlusion types — replaces the previous
 * single hard-coded style:
 *
 *  - [BLUR]     — the underlying pixels are blurred (downscale → upscale,
 *                 smoothed). Hints at the shape/length of the content.
 *  - [OVERLAY]  — the region is painted with a solid box. Cleaner on a screen
 *                 full of fields, and the strongest guarantee — no source pixel
 *                 survives.
 *  - [PIXELATE] — coarse mosaic blocks (downscale → upscale, no smoothing).
 *                 Reads as "redacted" while keeping rough layout/colour.
 *
 * Set globally with [Replay.setMaskStyle] (the default for addPrivacyView,
 * occludeAllTextFields, …), or per region where the host supplies it (React
 * Native / Flutter mask widgets, [Replay.occludeRectsOnNextFrame]).
 */
enum class ReplayMaskStyle(val raw: Int) {
    BLUR(0),
    OVERLAY(1),
    PIXELATE(2);

    companion object {
        @JvmStatic
        fun from(raw: Int): ReplayMaskStyle =
            entries.firstOrNull { it.raw == raw } ?: BLUR
    }
}

/**
 * A sensitive region (decor-view pixel coords) plus how it should be rendered.
 * Flows from the privacy sources to the screenshotter. Public so host SDKs
 * (React Native / Flutter plugins) can build them for the engine's rect
 * providers.
 */
data class MaskRect(val rect: Rect, val style: ReplayMaskStyle)
