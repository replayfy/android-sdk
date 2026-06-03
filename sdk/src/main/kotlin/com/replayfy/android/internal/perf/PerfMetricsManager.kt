package com.replayfy.android.internal.perf

import android.content.Context
import com.replayfy.android.internal.PerformanceEventData

/**
 * Coordinator for the perf collectors that ship in v1:
 *
 *   • [ColdStartTracker] — one-shot on first foreground
 *   • [FrameRateMonitor] — frame_drop_pct + frozen_frame_count
 *   • [MemoryPoller] — memory_rss_mb every 30s
 *   • [ThermalMonitor] — thermal_state on change + baseline at start
 *   • [AnrWatchdog]    — anr_ms when main thread blocks ≥5s
 *
 * Each collector calls a shared `emit` closure with
 * `(metric, value, unit, rating?)`. We wrap into the standard
 * [PerformanceEventData] shape and forward to LegacyCore for
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

    private val anr = AnrWatchdog { frozenMs, mainStack ->
        // Ship duration via PerformanceEventData (rating reflects
        // severity: 5–10s = needs-improvement, >10s = poor — Google's
        // Android Vitals thresholds for ANR). The main-thread stack
        // ships in `details` so the dashboard can group ANRs by
        // stack signature (same UX Crashlytics gives crashes) and
        // render the "main thread was stuck here" line-by-line.
        val rating = when {
            frozenMs >= 10_000 -> "poor"
            else -> "needs-improvement"
        }
        android.util.Log.w("ReplaySdk", "ANR detected (${frozenMs}ms)\n$mainStack")
        // Cap details at ~8 KB — a deep trace shouldn't blow the
        // batch envelope. Same cap CrashHandler uses.
        val safeStack = mainStack.take(8_000)
        onMetric(PerformanceEventData(
            kind = "perf",
            metric = "anr_ms",
            value = frozenMs.toDouble(),
            unit = "ms",
            rating = rating,
            details = safeStack,
        ))
    }

    /** Start every collector. Called from [com.replayfy.android.internal.LegacyCore]
     *  init after the session runtime is set up. */
    fun start() {
        frames.start()
        memory.start()
        thermal.start()
        anr.start()
        // Cold start fires once on first foreground; LegacyCore
        // calls reportFirstForeground from its lifecycle hook so
        // we measure cold-launch → user-sees-app time, not just
        // process-init.
    }

    /** Stop every collector. Called on session-end / Replay.stop. */
    fun stop() {
        frames.stop()
        memory.stop()
        thermal.stop()
        anr.stop()
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
