package com.replayfy.android.internal.perf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls process heap memory every 30s and emits `memory_rss_mb`.
 *
 * Uses [Runtime.totalMemory] - [Runtime.freeMemory] — the cheapest
 * way to read current JVM heap usage. Apple's iOS counterpart
 * (mach_task_basic_info.resident_size) includes off-heap memory too;
 * for parity we'd ideally use Android's Debug.MemoryInfo.getTotalPss
 * which captures the real RSS, but that's an expensive call
 * (parses /proc internally) — Runtime is the right tradeoff for the
 * 30s poll cadence.
 *
 * Background coroutine on [Dispatchers.Default] so we don't touch
 * the main thread.
 *
 * Mirrors the iOS ``MemoryPoller.swift``.
 */
internal class MemoryPoller(
    private val emit: (metric: String, value: Double, unit: String, rating: String?) -> Unit,
) {

    /** Matches the matrix doc — 30s. Background poll cadence above
     *  this starts showing up in Android Vitals battery reports. */
    private val intervalMs: Long = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Initial delay so we don't fire immediately on session
            // start (the first interval's measurement isn't very
            // informative — caches are warming).
            delay(intervalMs)
            while (isActive) {
                poll()
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Returns heap usage in MiB. Public for tests + potential
     *  cold-start emission. */
    fun currentMemoryMb(): Double {
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedBytes.toDouble() / (1024.0 * 1024.0)
    }

    private fun poll() {
        val mb = currentMemoryMb()
        emit("memory_rss_mb", mb, "mb", rating(mb))
    }

    /** Thresholds from mobile-vitals-matrix.md. */
    private fun rating(mb: Double): String = when {
        mb < 150 -> "good"
        mb < 300 -> "needs-improvement"
        else -> "poor"
    }
}
