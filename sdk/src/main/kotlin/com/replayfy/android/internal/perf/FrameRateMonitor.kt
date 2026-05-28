package com.replayfy.android.internal.perf

import android.view.Choreographer

/**
 * Tracks UI frame timing via [Choreographer.FrameCallback]. Same
 * primitive Google Play Console uses internally for "jank". Produces
 * two metrics:
 *
 *   - `frame_drop_pct` — rolling 10s ratio of dropped to expected
 *     frames. Compared against the screen's target frame rate
 *     (assumed 60Hz for v1 — high-refresh displays will under-report
 *     drops slightly, will refine when we add display-mode detection).
 *   - `frozen_frame_count` — total frames in the session with
 *     interval > 700ms. Matches Google's "frozen frame" definition
 *     for Android Vitals.
 *
 * Choreographer fires synchronously on the main thread once per
 * vsync (when our callback is scheduled). We do the bare minimum
 * work in the callback (a counter bump + delta math) — never call
 * out to the network or do allocations in the hot path.
 *
 * Mirrors the iOS ``FrameRateMonitor.swift`` (CADisplayLink-based).
 */
internal class FrameRateMonitor(
    private val emit: (metric: String, value: Double, unit: String, rating: String?) -> Unit,
) {

    // Assume 60Hz for v1. 16.67ms per frame.
    private val expectedFrameNs: Long = 16_666_666L

    /** Threshold above which a frame is considered "frozen" — 700ms
     *  matches Google's Android Vitals definition. */
    private val frozenThresholdNs: Long = 700_000_000L

    /** Rolling window for frame_drop_pct emits — 10s. */
    private val emitWindowMs: Long = 10_000L

    private var lastFrameNs: Long = 0
    private var droppedThisWindow: Int = 0
    private var expectedThisWindow: Int = 0
    private var lastEmitMs: Long = 0
    private var frozenTotal: Int = 0
    private var lastFrozenEmit: Int = 0

    private var running = false
    private val callback = Choreographer.FrameCallback { ns -> onFrame(ns) }

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
        lastFrameNs = 0
        droppedThisWindow = 0
        expectedThisWindow = 0
    }

    private fun onFrame(frameTimeNs: Long) {
        if (!running) return
        // Re-post for next vsync. Choreographer callbacks are
        // one-shot — must re-post each tick.
        Choreographer.getInstance().postFrameCallback(callback)

        val nowMs = System.currentTimeMillis()

        // First tick — nothing to compare against.
        if (lastFrameNs == 0L) {
            lastFrameNs = frameTimeNs
            lastEmitMs = nowMs
            return
        }

        val intervalNs = frameTimeNs - lastFrameNs
        lastFrameNs = frameTimeNs

        // A "dropped" frame: interval > 1.5× expected. The vsync
        // missed at least one frame slot.
        if (intervalNs > expectedFrameNs * 1.5) {
            val missed = (intervalNs / expectedFrameNs).toInt().coerceAtLeast(2) - 1
            droppedThisWindow += missed
        }
        expectedThisWindow += 1

        if (intervalNs > frozenThresholdNs) {
            frozenTotal += 1
        }

        // Periodic emit — only ship when there's something to
        // report. Silent windows save bandwidth.
        if (nowMs - lastEmitMs >= emitWindowMs) {
            emitWindow()
            lastEmitMs = nowMs
            droppedThisWindow = 0
            expectedThisWindow = 0
        }
    }

    private fun emitWindow() {
        if (expectedThisWindow > 0 && droppedThisWindow > 0) {
            val pct = droppedThisWindow.toDouble() / expectedThisWindow.toDouble()
            emit("frame_drop_pct", pct, "pct", rateFrameDrop(pct))
        }
        val delta = frozenTotal - lastFrozenEmit
        if (delta > 0) {
            emit("frozen_frame_count", delta.toDouble(), "count", rateFrozen(frozenTotal))
            lastFrozenEmit = frozenTotal
        }
    }

    /** Thresholds from mobile-vitals-matrix.md. */
    private fun rateFrameDrop(pct: Double): String = when {
        pct < 0.05 -> "good"
        pct < 0.15 -> "needs-improvement"
        else -> "poor"
    }

    private fun rateFrozen(total: Int): String = when {
        total == 0 -> "good"
        total < 3 -> "needs-improvement"
        else -> "poor"
    }
}
