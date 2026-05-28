package com.replayfy.android.internal.perf

import android.content.Context
import com.replayfy.android.internal.PerformanceEventData

/**
 * Coordinator for the four perf collectors that ship in v1:
 *
 *   • [ColdStartTracker] — one-shot on first foreground
 *   • [FrameRateMonitor] — frame_drop_pct + frozen_frame_count
 *   • [MemoryPoller] — memory_rss_mb every 30s
 *   • [ThermalMonitor] — thermal_state on change + baseline at start
 *
 * Each collector calls a shared `emit` closure with
 * `(metric, value, unit, rating?)`. We wrap into the standard
 * [PerformanceEventData] shape and forward to ReplayCore for
 * inclusion in the next batch.
 *
 * Mirrors the iOS ``PerfMetricsManager.swift``. Deferred to
 * follow-up commits: tap_response_ms, first_network_ttfb_ms,
 * battery_drain_pct_per_min, time_to_first_meaningful_render_ms —
 * each needs other hooks (TapTracker → frame timing, network
 * capture, UIDevice battery monitoring, ViewController appearance
 * swizzle) which aren't wired yet.
 */
internal class PerfMetricsManager(
    context: Context,
    private val onMetric: (PerformanceEventData) -> Unit,
) {

    private val frames = FrameRateMonitor { metric, value, unit, rating ->
        onMetric(PerformanceEventData(
            kind = "perf", metric = metric, value = value, unit = unit, rating = rating,
        ))
    }

    private val memory = MemoryPoller { metric, value, unit, rating ->
        onMetric(PerformanceEventData(
            kind = "perf", metric = metric, value = value, unit = unit, rating = rating,
        ))
    }

    private val thermal = ThermalMonitor(context) { metric, value, unit, rating ->
        onMetric(PerformanceEventData(
            kind = "perf", metric = metric, value = value, unit = unit, rating = rating,
        ))
    }

    /** Start every collector. Called from [com.replayfy.android.internal.ReplayCore]
     *  init after the session runtime is set up. */
    fun start() {
        frames.start()
        memory.start()
        thermal.start()
        // Cold start fires once on first foreground; ReplayCore
        // calls reportFirstForeground from its lifecycle hook so
        // we measure cold-launch → user-sees-app time, not just
        // process-init.
    }

    /** Stop every collector. Called on session-end / Replay.stop. */
    fun stop() {
        frames.stop()
        memory.stop()
        thermal.stop()
    }

    /** Emit the cold-start metric exactly once per process.
     *  Idempotent — repeated calls after the first are no-ops. */
    fun reportFirstForeground() {
        val ms = ColdStartTracker.recordFirstForeground() ?: return
        onMetric(PerformanceEventData(
            kind = "perf",
            metric = "cold_start_ms",
            value = ms,
            unit = "ms",
            rating = ColdStartTracker.rating(ms),
        ))
    }
}
