package com.replayfy.android.internal.perf

import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Measures cold-start time — process start → first foreground.
 *
 * On API 24+ we use [Process.getStartUptimeMillis] which gives us
 * the actual zygote-fork time. On older Android we approximate via
 * [SystemClock.elapsedRealtime] captured at module init (when
 * ReplayContentProvider.onCreate fires the SDK auto-bootstrap) —
 * within milliseconds of process start because ContentProviders
 * are instantiated by the OS BEFORE Application.onCreate.
 *
 * Emits ``PerformanceEventData`` ONCE per process — the first
 * onAppForegrounded after ReplayCore.init. Subsequent foregrounds
 * don't re-emit; "cold start" is by definition the cold one.
 *
 * Mirrors the iOS ``ColdStartTracker.swift``.
 */
internal object ColdStartTracker {

    private val emitted = AtomicBoolean(false)
    private val fallbackStartMs: Long = SystemClock.elapsedRealtime()

    /** Returns the cold-start measurement on the FIRST call.
     *  Subsequent calls return null — caller emits the
     *  PerformanceEventData only when this returns non-null. */
    fun recordFirstForeground(): Double? {
        if (!emitted.compareAndSet(false, true)) return null
        val nowMs = SystemClock.elapsedRealtime()
        val startMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Process.getStartUptimeMillis()
        } else {
            fallbackStartMs
        }
        return (nowMs - startMs).coerceAtLeast(0).toDouble()
    }

    /** Thresholds from mobile-vitals-matrix.md. */
    fun rating(ms: Double): String = when {
        ms < 1500 -> "good"
        ms < 2500 -> "needs-improvement"
        else -> "poor"
    }
}
