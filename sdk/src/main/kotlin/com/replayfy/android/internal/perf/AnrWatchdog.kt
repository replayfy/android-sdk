package com.replayfy.android.internal.perf

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Detects Android ANR (Application Not Responding) windows — periods
 * where the main thread is blocked long enough that the OS would
 * eventually show the "App isn't responding" dialog.
 *
 * Mechanism — heartbeat from a dedicated watchdog thread:
 *   1. Watchdog thread posts a sentinel [Runnable] to the main
 *      [Looper] every [pingIntervalMs].
 *   2. The sentinel, when it runs on main, stamps
 *      [lastMainRespondedAtMs]. If main is blocked, the sentinel
 *      sits in the message queue — [lastMainRespondedAtMs] grows
 *      stale.
 *   3. After posting, the watchdog sleeps. On wake it checks
 *      `now - lastMainRespondedAtMs`. If that exceeds
 *      [anrThresholdMs] (default 5s — same threshold the Android
 *      framework uses for input dispatching ANRs), it flags an ANR:
 *      captures the main-thread stack via [Thread.getStackTrace],
 *      and emits a `anr_ms` perf event with the duration + stack.
 *   4. Debounce: a single ANR doesn't fire repeatedly. We only
 *      emit again after main has responded at least once
 *      (so an 8s freeze fires one event, not two).
 *
 * Why a dedicated [Thread] instead of HandlerThread + postDelayed
 * polling: we want the watchdog to be unkillable by main-thread
 * pressure. A separate Thread is the simplest construct that
 * cannot be starved by anything happening on main.
 *
 * the reference mobile SDK DOES NOT ship ANR detection (verified via decompilation of
 * the reference mobile SDK-android.aar 3.10.2 — no Anr/Watchdog/ApplicationNotResponding
 * classes). Datadog RUM, Firebase Performance, and Sentry all do.
 * Building it here closes a gap vs the reference mobile SDK + matches the industry
 * standard.
 *
 * Mirrors no iOS equivalent — iOS uses a different mechanism for
 * hang detection (MXHangDiagnostic on MetricKit). We'll add the
 * iOS counterpart in a follow-up.
 *
 * @param anrThresholdMs   Main-thread block duration above which we
 *                         flag an ANR. Android's own input dispatch
 *                         ANR threshold is 5000ms; we mirror that.
 * @param pingIntervalMs   How often the watchdog posts a sentinel
 *                         to main. 1000ms is a good balance — small
 *                         enough to keep the timestamp fresh, large
 *                         enough to not spam the main message queue.
 * @param emit             Callback fired when an ANR window
 *                         resolves. `frozenMs` is the time main
 *                         was blocked; `mainStack` is a
 *                         newline-separated stack trace of where
 *                         main was stuck. Fires from the watchdog
 *                         thread.
 */
internal class AnrWatchdog(
    private val anrThresholdMs: Long = DEFAULT_ANR_THRESHOLD_MS,
    private val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
    private val emit: (frozenMs: Long, mainStack: String) -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var watcher: Thread? = null

    /** Updated each time the sentinel actually executes on main.
     *  Reads + writes are volatile-safe because we only ever
     *  compare-then-act on this value, never read-modify-write. */
    @Volatile
    private var lastMainRespondedAtMs: Long = SystemClock.uptimeMillis()

    /** True between flagging an ANR + main responding again — used
     *  to debounce so a single freeze fires one event, not many. */
    @Volatile
    private var anrInProgress: Boolean = false

    /** Sentinel posted to main. Cheap — just timestamp + flag flip. */
    private val sentinel = Runnable {
        lastMainRespondedAtMs = SystemClock.uptimeMillis()
        anrInProgress = false
    }

    fun start() {
        if (running) return
        running = true
        // Prime the timestamp so a freeze at boot doesn't immediately
        // appear as a 5s ANR.
        lastMainRespondedAtMs = SystemClock.uptimeMillis()
        val t = Thread({ loop() }, "ReplayAnrWatchdog").apply {
            isDaemon = true
            // Slightly below NORM_PRIORITY (5) so we never starve
            // the host app's threads, but still high enough to wake
            // when our sleep elapses.
            priority = Thread.NORM_PRIORITY - 1
        }
        watcher = t
        t.start()
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(sentinel)
        watcher?.interrupt()
        watcher = null
    }

    private fun loop() {
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                // Post the sentinel. If main is healthy it will run
                // quickly and update lastMainRespondedAtMs. If main
                // is blocked it sits in the queue.
                mainHandler.post(sentinel)
                Thread.sleep(pingIntervalMs)

                val sinceLast = SystemClock.uptimeMillis() - lastMainRespondedAtMs
                if (sinceLast >= anrThresholdMs && !anrInProgress) {
                    anrInProgress = true
                    val mainStack = captureMainStack()
                    try {
                        emit(sinceLast, mainStack)
                    } catch (t: Throwable) {
                        // emit must never crash the watchdog.
                        android.util.Log.w(TAG, "ANR emit threw: ${t.message}")
                    }
                }
            } catch (_: InterruptedException) {
                // stop() called.
                break
            } catch (t: Throwable) {
                // Defensive — any unexpected error must not kill
                // the watchdog. Sleep a bit and try again.
                android.util.Log.w(TAG, "watchdog loop hiccup: ${t.message}")
                try { Thread.sleep(pingIntervalMs) } catch (_: InterruptedException) { break }
            }
        }
    }

    /**
     * Capture the main thread's stack trace at the moment of
     * detection. This is the most diagnostic field on an ANR — it
     * tells the customer WHERE main was stuck.
     *
     * `Thread.getStackTrace()` walks the live stack without pausing
     * the thread; the result is a snapshot. The trace may shift by
     * a few frames between when we read it and when the JVM
     * materializes the array — that's fine, the deepest meaningful
     * frame is what matters.
     */
    private fun captureMainStack(): String {
        return try {
            val mainThread = Looper.getMainLooper().thread
            val frames = mainThread.stackTrace
            buildString(frames.size * 64) {
                append("ANR — main thread stack at detection:\n")
                for (f in frames) {
                    append("  at ")
                    append(f.className)
                    append('.')
                    append(f.methodName)
                    append('(')
                    append(f.fileName ?: "Unknown")
                    if (f.lineNumber > 0) {
                        append(':')
                        append(f.lineNumber)
                    }
                    append(")\n")
                }
            }
        } catch (t: Throwable) {
            "(stack capture failed: ${t.message})"
        }
    }

    companion object {
        /** Matches Android's own input-dispatching ANR threshold. */
        const val DEFAULT_ANR_THRESHOLD_MS: Long = 5_000L

        /** Frequent enough that lastMainRespondedAtMs stays fresh,
         *  infrequent enough that the watchdog doesn't crowd the
         *  main message queue. */
        const val DEFAULT_PING_INTERVAL_MS: Long = 1_000L

        private const val TAG = "ReplaySdk"
    }
}
