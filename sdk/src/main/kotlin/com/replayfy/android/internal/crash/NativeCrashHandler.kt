package com.replayfy.android.internal.crash

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Native-signal crash handler. Catches the fatal signals JNI / NDK
 * code in the host app can raise (SIGSEGV, SIGBUS, SIGABRT,
 * SIGFPE, SIGILL, SIGTRAP) — the ones `Thread.UncaughtExceptionHandler`
 * (see [CrashHandler]) can NOT see because the JVM is dead before
 * the handler can fire.
 *
 * Two-phase design — same persistence-to-disk pattern as the JVM
 * handler:
 *
 *   - **install()** loads `libreplay_crash.so` and asks the native
 *     side to register sigaction handlers, telling it the path
 *     where a fatal-signal record should be written. The native
 *     handler chains to the prior action so Crashlytics / Sentry
 *     / Google Breakpad still fire after us.
 *   - **drainPrevious()** runs on next launch: reads the record
 *     file (if any), parses the pipe-delimited line, calls
 *     [onRecoveredCrash] with a [CrashRecord] of kind="signal".
 *
 * The .so is OPTIONAL — if the host app strips the `arm64-v8a` /
 * `armeabi-v7a` / `x86_64` variants we ship (some apps do
 * aggressive APK trimming), `System.loadLibrary` throws and we
 * just log + carry on. The JVM-side [CrashHandler] still catches
 * what it can.
 *
 * Mirrors the architectural shape of the iOS SDK's PLCrashReporter
 * adoption — async-signal-safe write on the crash side, rich
 * decode + emit on the next-launch side.
 *
 * Wire format (single line, written by the native handler):
 *
 *     v1|<signo>|<si_code>|<faulting_addr_hex>|<ts_ms>|<thread_name>|<frame0_hex,frame1_hex,...>
 *
 * Stack frames are raw program-counter values. Symbolication
 * happens on the dashboard side via the addr2line tool against the
 * uploaded .so's debug symbols (future work — for now we ship the
 * hex PCs and the dashboard renders them as-is, which Crashlytics
 * users are already accustomed to).
 */
internal class NativeCrashHandler(
    private val context: Context,
    private val onRecoveredCrash: (CrashRecord) -> Unit,
) {

    private companion object {
        const val TAG = "ReplaySdk"
        const val RECORD_FILE = "last-native-crash.txt"

        /** Lazy + idempotent. JNI library load throws
         *  [UnsatisfiedLinkError] when the .so isn't present in
         *  the APK — caught in [install] so the SDK doesn't kill
         *  the host app over a stripped binary. */
        @Volatile private var loaded: Boolean = false
        @Volatile private var loadFailed: Boolean = false

        private fun loadLibraryOnce(): Boolean {
            if (loaded) return true
            if (loadFailed) return false
            synchronized(this) {
                if (loaded) return true
                if (loadFailed) return false
                try {
                    System.loadLibrary("replay_crash")
                    loaded = true
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "NDK crash handler unavailable: ${t.message}")
                    loadFailed = true
                    return false
                }
            }
        }

        /** Bound by the C side via JNI's standard naming convention.
         *  Static (companion @JvmStatic) so the JNI signature
         *  receives `jclass` rather than `jobject`. Returns true on
         *  success; false when sigaction failed for every signal. */
        @JvmStatic
        external fun nativeInstall(filePath: String): Boolean
    }

    /** Install the native handler + drain any record left from a
     *  prior process crash. Safe to call from any thread; the JNI
     *  install registers sigaction which is itself signal-safe. */
    fun install() {
        // ORDER MATTERS: drain first so a crash that happened
        // BEFORE this install() (recovered from disk) is read
        // BEFORE we potentially overwrite the file. The native
        // handler opens the file with O_TRUNC, so any pending
        // record would be lost if we installed before draining.
        drainPrevious()
        if (!loadLibraryOnce()) return
        val path = recordFile().absolutePath
        try {
            val ok = nativeInstall(path)
            if (!ok) Log.w(TAG, "nativeInstall returned false")
        } catch (t: Throwable) {
            // Shouldn't happen — loadLibraryOnce guarantees the
            // method is bound. Catch anyway so a corrupt .so can't
            // crash the host app at startup.
            Log.w(TAG, "nativeInstall threw: ${t.message}")
        }
    }

    /** Read any signal record left over from a previous launch and
     *  forward it to ReplayCore. Called once from [install]. */
    private fun drainPrevious() {
        val file = recordFile()
        if (!file.exists()) return
        val text = try { file.readText().trim() } catch (_: Throwable) { null }
        // Always delete — even on parse failure we don't want to
        // get stuck retrying.
        try { file.delete() } catch (_: Throwable) {}
        if (text.isNullOrEmpty()) return

        // Format: v1|<signo>|<si_code>|<addr>|<ts>|<thread>|<frames>
        val parts = text.split('|', limit = 7)
        if (parts.size < 7 || parts[0] != "v1") {
            Log.w(TAG, "drainPreviousNative: unrecognised format")
            return
        }
        val signo = parts[1].toIntOrNull() ?: return
        val siCode = parts[2].toIntOrNull() ?: 0
        val addr = parts[3]
        val ts = parts[4].toLongOrNull() ?: System.currentTimeMillis()
        val thread = parts[5].ifBlank { "unknown" }
        val frames = parts[6]

        val sigName = signalName(signo)
        val codeMeaning = signalCodeMeaning(signo, siCode)
        // Compose a human-readable stack from the raw PCs. The
        // dashboard symbolicates these against uploaded debug
        // symbols (future) — until then they render as hex which
        // is what Crashlytics + Sentry users already expect.
        val stack = buildString {
            append("Fatal signal $signo ($sigName), code $siCode ($codeMeaning)")
            append(", fault addr $addr")
            append(" on thread '$thread'\n")
            // Frames separated by commas in the on-disk format —
            // expand to one per line for readability.
            for (frame in frames.split(',')) {
                if (frame.isNotBlank()) append("  at $frame\n")
            }
        }
        val record = CrashRecord(
            kind = "signal",
            className = "Signal.$sigName",
            message = "$sigName at $addr (code $codeMeaning)",
            stack = stack.trimEnd(),
            timestamp = ts,
        )
        onRecoveredCrash(record)
    }

    private fun recordFile(): File {
        val dir = File(context.filesDir, "replay").also { it.mkdirs() }
        return File(dir, RECORD_FILE)
    }

    /** Map signal numbers to their POSIX names. Hard-coded rather
     *  than referencing `android.system.OsConstants` so the lookup
     *  never throws on a buggy device. */
    private fun signalName(signo: Int): String = when (signo) {
        4 -> "SIGILL"
        6 -> "SIGABRT"
        7 -> "SIGBUS"   // Bionic value; differs from glibc (10)
        8 -> "SIGFPE"
        11 -> "SIGSEGV"
        5 -> "SIGTRAP"
        else -> "SIG$signo"
    }

    /** Decode the si_code field. Values differ per signal — these
     *  are the most-common meaningful ones from <bits/siginfo.h>. */
    private fun signalCodeMeaning(signo: Int, code: Int): String = when (signo) {
        11 /* SIGSEGV */ -> when (code) {
            1 -> "SEGV_MAPERR (address not mapped)"
            2 -> "SEGV_ACCERR (invalid permissions)"
            else -> "code=$code"
        }
        7 /* SIGBUS */ -> when (code) {
            1 -> "BUS_ADRALN (invalid address alignment)"
            2 -> "BUS_ADRERR (nonexistent address)"
            3 -> "BUS_OBJERR (object-specific error)"
            else -> "code=$code"
        }
        8 /* SIGFPE */ -> when (code) {
            1 -> "FPE_INTDIV (integer divide-by-zero)"
            3 -> "FPE_FLTDIV (float divide-by-zero)"
            else -> "code=$code"
        }
        else -> "code=$code"
    }

}
