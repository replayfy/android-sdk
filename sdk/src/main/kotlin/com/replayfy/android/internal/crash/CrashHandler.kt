package com.replayfy.android.internal.crash

import android.content.Context
import java.io.File

/**
 * Catches JVM uncaught exceptions, persists a minimal record to
 * disk, and surfaces it as a `crash`-kind error event on the NEXT
 * process launch.
 *
 * Why next-launch rather than this-launch: a crashing process can't
 * reliably do anything async like an HTTP POST. The safest path is
 * to write the bare minimum from inside the handler (synchronous
 * disk write — no network), then let the next normal process
 * startup do the rich emission through the standard batch pipeline.
 *
 * Native signal handlers (SIGSEGV / SIGABRT) are NOT installed by
 * this class — those need NDK + a .so file. Out of scope for v1.
 * Customers running Crashlytics / Sentry already cover native
 * crashes; we cover Kotlin / Java throws which the OS bubbles up
 * through Thread.UncaughtExceptionHandler.
 *
 * Chains the previous handler so Crashlytics / Sentry / customer
 * handlers still fire — never owns the handler exclusively.
 *
 * Loosely mirrors the iOS [CrashHandler.swift] — same persistence-
 * to-disk + drain-on-next-launch pattern, simpler implementation
 * because the JVM handler runs in normal Kotlin context with full
 * runtime access (no async-signal-safety constraints).
 */
internal class CrashHandler(
    private val context: Context,
    private val onRecoveredCrash: (CrashRecord) -> Unit,
) {

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** Install the handler. Idempotent — repeated calls re-chain
     *  but don't double-install. Drains any record from a previous
     *  process crash FIRST so it ships on this session. */
    fun install() {
        drainPrevious()
        if (previousHandler != null) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeRecord(thread, throwable)
            } catch (_: Throwable) {
                // Best-effort — never let our handler bury the
                // original crash by throwing its own error.
            }
            // Chain to the previous handler so Crashlytics / Sentry
            // still fire. If there was none, fall through to the OS
            // default which terminates + produces a Logcat report.
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Read any crash record left over from a previous launch and
     *  forward it to ReplayCore. Called once during install(). */
    private fun drainPrevious() {
        val file = recordFile() ?: return
        if (!file.exists()) return
        val text = try { file.readText() } catch (_: Throwable) { null }
        // Always delete — even on parse failure we don't want to
        // get stuck retrying.
        try { file.delete() } catch (_: Throwable) {}
        if (text.isNullOrEmpty()) return

        // Format: "v1|<class>|<message>|<stack>|<ts>"
        // pipe-delimited, all newlines pre-escaped to '/n'.
        val parts = text.split('|', limit = 5)
        if (parts.size < 5 || parts[0] != "v1") return
        val record = CrashRecord(
            kind = "uncaught",
            className = parts[1],
            message = parts[2],
            stack = parts[3].replace("/n", "\n"),
            timestamp = parts[4].toLongOrNull() ?: System.currentTimeMillis(),
        )
        onRecoveredCrash(record)
    }

    private fun writeRecord(@Suppress("UNUSED_PARAMETER") thread: Thread, throwable: Throwable) {
        // `thread` reserved for future use (e.g. flagging crashes on
        // worker threads vs main); current wire format doesn't carry it.
        val file = recordFile() ?: return
        val className = throwable.javaClass.name.take(120)
        val message = (throwable.message ?: "").take(280)
            .replace('|', '/')
            .replace('\n', ' ')
        val stack = throwable.stackTraceToString()
            .take(8_000)   // Hard cap so a deep trace can't blow the file
            .replace("|", "/")
            // Newlines → "/n" sentinel so the pipe-delimited record
            // stays single-line; drainPrevious unescapes on read.
            .replace("\n", "/n")
        val ts = System.currentTimeMillis()
        val line = "v1|$className|$message|$stack|$ts"
        // Atomic write — tmp + rename. Mid-write process kill can't
        // leave a corrupt file the drainer would later parse-fail on.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(line)
            tmp.renameTo(file)
        } catch (_: Throwable) {}
    }

    private fun recordFile(): File? {
        val dir = File(context.filesDir, "replay").also { it.mkdirs() }
        return File(dir, "last-crash.txt")
    }
}

/** A crash record recovered on next launch — passed to ReplayCore
 *  for emission as an `error` event with `kind:"crash"`. */
internal data class CrashRecord(
    /** "uncaught" — only kind in v1. Native "signal" reserved for
     *  the future NDK signal-handler commit. */
    val kind: String,
    val className: String,
    val message: String,
    val stack: String,
    val timestamp: Long,
)
