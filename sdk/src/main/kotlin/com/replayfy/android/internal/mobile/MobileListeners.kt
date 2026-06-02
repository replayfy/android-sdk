package com.replayfy.android.internal.mobile

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodic performance sampling → performanceEvent {name, value}
 * messages, matching the reference metrics. One scheduler samples
 * main-thread CPU, memory (PSS), thermal status, battery level, and
 * low-power mode every 5 s.
 */
class MobilePerfMonitor(
    private val context: Context,
    private val emit: (name: String, value: Long) -> Unit,
) {
    private var scheduler: ScheduledExecutorService? = null

    // CPU sampling state: previous (utime+stime) jiffies + wall clock, so
    // each sample reports the % busy over the elapsed interval.
    private var prevCpuTicks = -1L
    private var prevCpuWallNs = 0L
    private val clkTck = try { Os.sysconf(OsConstants._SC_CLK_TCK) } catch (_: Throwable) { 100L }
    private var prevLowPower = -1L

    fun start() {
        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate({ sample() }, 0, 5, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        scheduler?.shutdownNow(); scheduler = null
    }

    private fun sample() {
        try {
            // Main-thread CPU % (the reference's mainThreadCPU; iOS-parity).
            sampleCpu()

            // Memory — total PSS in bytes.
            emit("memoryUsage", Debug.getPss() * 1024L)

            // Thermal status (API 29+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.let { emit("thermalState", it.currentThermalStatus.toLong()) }
            }

            // Battery level (0–100).
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) emit("batteryLevel", (level * 100L / scale))

            // Low-power mode — emit only on change (matches the reference's
            // change-driven isLowPowerModeEnabled, avoids per-sample noise).
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val low = if (pm?.isPowerSaveMode == true) 1L else 0L
            if (low != prevLowPower) {
                emit("isLowPowerModeEnabled", low)
                prevLowPower = low
            }
        } catch (e: Throwable) { /* skip sample */ }
    }

    /**
     * Main-thread CPU usage %. The main thread's TID equals the process
     * PID on Linux, so /proc/<pid>/task/<pid>/stat fields 14+15 (utime,
     * stime, in clock ticks) give its busy time; the delta over the
     * elapsed wall-clock interval is the busy %.
     */
    private fun sampleCpu() {
        try {
            val pid = Process.myPid()
            val stat = File("/proc/$pid/task/$pid/stat").readText()
            // comm (field 2) may contain spaces/parens — split after the last ')'.
            val parts = stat.substring(stat.lastIndexOf(')') + 1).trim().split(" ")
            val ticks = parts[11].toLong() + parts[12].toLong() // utime + stime
            val nowNs = System.nanoTime()
            if (prevCpuTicks >= 0 && clkTck > 0) {
                val dSec = (nowNs - prevCpuWallNs) / 1_000_000_000.0
                if (dSec > 0) {
                    val pct = (ticks - prevCpuTicks).toDouble() / clkTck / dSec * 100.0
                    emit("mainThreadCPU", pct.coerceIn(0.0, 100.0).toLong())
                }
            }
            prevCpuTicks = ticks
            prevCpuWallNs = nowNs
        } catch (_: Throwable) { /* /proc unreadable on some devices — skip */ }
    }
}

/**
 * Chains an uncaught-exception handler that emits a crash message
 * before delegating to the previous handler (which lets the process
 * die normally).
 */
object MobileCrashHandler {
    private var emit: ((name: String, reason: String, stack: String) -> Unit)? = null
    private var installed = false

    fun install(emit: (name: String, reason: String, stack: String) -> Unit) {
        this.emit = emit
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                this.emit?.invoke(
                    throwable.javaClass.name,
                    throwable.message ?: "",
                    throwable.stackTrace.joinToString("\n") { it.toString() },
                )
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }
    }
}
