package com.replayfy.android.internal.mobile

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodic performance sampling → performanceEvent {name, value}
 * messages, matching the reference metrics. One scheduler samples
 * memory (PSS), thermal status, and battery level every 5 s.
 */
class MobilePerfMonitor(
    private val context: Context,
    private val emit: (name: String, value: Long) -> Unit,
) {
    private var scheduler: ScheduledExecutorService? = null

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
        } catch (e: Throwable) { /* skip sample */ }
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
