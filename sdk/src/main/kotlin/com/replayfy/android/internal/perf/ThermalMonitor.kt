package com.replayfy.android.internal.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Observes [PowerManager.getCurrentThermalStatus] and emits
 * `thermal_state` when it changes.
 *
 * Available on API 29+ (Android 10+). Skips entirely on older
 * versions — the backend column is nullable, and the matrix doc
 * treats missing thermal data as acceptable.
 *
 * Maps Android's 7-tier status (NONE..SHUTDOWN) to the
 * cross-platform 0-3 scale documented in mobile-vitals-matrix.md
 * so iOS + Android render identically on the dashboard.
 *
 * Higher = worse = MAX-of-session is the right server-side
 * aggregation (matches what the backend does for worst* metrics).
 *
 * Mirrors the iOS ``ThermalMonitor.swift``.
 */
internal class ThermalMonitor(
    private val context: Context,
    private val emit: (metric: String, value: Double, unit: String, rating: String?) -> Unit,
) {

    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    private var lastEmitted: Int = -1

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return
        // Baseline emit so every session has a thermal reading on
        // record, even sessions that never cross a threshold.
        emitState(pm.currentThermalStatus)
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            emitState(status)
        }
        try {
            pm.addThermalStatusListener(l)
            listener = l
        } catch (_: Throwable) {
            // Rare OEM quirk — log + continue, baseline already shipped.
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        listener?.let {
            try { pm?.removeThermalStatusListener(it) } catch (_: Throwable) {}
        }
        listener = null
        lastEmitted = -1
    }

    private fun emitState(status: Int) {
        val value = mapToScale(status)
        if (value == lastEmitted) return
        lastEmitted = value
        emit("thermal_state", value.toDouble(), "", rating(value))
    }

    /**
     * Map Android's PowerManager status to the matrix-doc 0-3 scale:
     *   NONE=0, LIGHT=1, MODERATE=2, SEVERE/CRITICAL/EMERGENCY/SHUTDOWN=3
     */
    private fun mapToScale(status: Int): Int = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> 0
        PowerManager.THERMAL_STATUS_LIGHT -> 1
        PowerManager.THERMAL_STATUS_MODERATE -> 2
        PowerManager.THERMAL_STATUS_SEVERE,
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> 3
        else -> 0
    }

    private fun rating(scale: Int): String = when (scale) {
        0 -> "good"
        1 -> "needs-improvement"
        else -> "poor"
    }
}
