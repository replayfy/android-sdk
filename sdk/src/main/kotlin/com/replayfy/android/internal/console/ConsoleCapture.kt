package com.replayfy.android.internal.console

import com.replayfy.android.internal.ConsoleEventData
import java.io.OutputStream
import java.io.PrintStream

/**
 * Captures console-style output from the host app.
 *
 * Android's `android.util.Log.d/i/w/e` writes to the system logd
 * buffer which requires `READ_LOGS` permission to read back — that
 * permission is granted only to platform apps post-Jelly-Bean
 * (4.1+), so user-installed apps cannot read their own Log output.
 * the reference mobile SDK has the same constraint.
 *
 * What we CAN capture in user-space without permissions:
 *
 *   1. `System.out` + `System.err` — `println()`, Kotlin `print()`,
 *      Java `System.out.println()`. Many smaller apps + dev builds
 *      route here; some Timber configurations redirect to stdout.
 *      We swap in a [TeePrintStream] that forwards to the original
 *      stream AND emits a console event per non-empty line.
 *
 *   2. Explicit [com.replayfy.android.Replay.log] calls — public API
 *      a customer can call directly from any logger of their choice
 *      (Timber tree, kotlin-logging, custom). Bridges the
 *      android.util.Log gap for customers willing to do one line of
 *      adapter wiring:
 *
 *        Timber.plant(object : Timber.Tree() {
 *          override fun log(p: Int, t: String?, m: String, e: Throwable?) {
 *            Replay.log(levelFor(p), m, e?.stackTraceToString())
 *          }
 *        })
 *
 * Mirrors the iOS SDK's `ConsoleCapture.swift` so the dashboard
 * Console panel shows the same event shape from both platforms.
 *
 * Lifecycle: [start] swaps the streams; [stop] restores the
 * originals. Idempotent — `start` after `start` is a no-op,
 * `stop` after `stop` likewise.
 */
internal class ConsoleCapture(
    private val emit: (ConsoleEventData) -> Unit,
) {

    @Volatile
    private var running: Boolean = false

    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null

    fun start() {
        if (running) return
        running = true
        originalOut = System.out
        originalErr = System.err
        System.setOut(TeePrintStream(originalOut!!, level = "log", emit = emit))
        System.setErr(TeePrintStream(originalErr!!, level = "error", emit = emit))
    }

    fun stop() {
        if (!running) return
        running = false
        originalOut?.let { System.setOut(it) }
        originalErr?.let { System.setErr(it) }
        originalOut = null
        originalErr = null
    }

    /** Explicit emit path — called by the public [Replay.log] API. */
    fun emitExplicit(level: String, message: String, stack: String?) {
        if (!running) return
        if (message.isBlank()) return
        try {
            emit(ConsoleEventData(level = level, message = message, stack = stack))
        } catch (_: Throwable) {
            // Never let the emit closure crash console capture —
            // worst case we drop a log line.
        }
    }

    /**
     * [PrintStream] subclass that forwards every write to the
     * `delegate` AND emits a console event per terminated line.
     *
     * We buffer per-thread via [ThreadLocal] so partial writes
     * (println splits the message + newline into TWO calls) coalesce
     * into one event. The buffer flushes on newline OR on `flush()`.
     *
     * Why subclass [PrintStream] and not wrap via [OutputStream]:
     * [System.setOut] insists on a [PrintStream] type. Subclassing
     * lets us reuse its formatter logic (println(Object) handles
     * `null`, primitives, arrays) without re-implementing it.
     */
    private class TeePrintStream(
        private val delegate: PrintStream,
        private val level: String,
        private val emit: (ConsoleEventData) -> Unit,
    ) : PrintStream(NullOutputStream, /*autoFlush*/ false) {

        // Per-thread line buffer. Avoids interleaving across
        // background threads that share System.out.
        private val buffer = ThreadLocal.withInitial { StringBuilder(128) }

        // The bare `print(...)` overloads call write() with raw bytes
        // — we intercept at write() so EVERY print path lands here
        // regardless of which overload the caller used.
        override fun write(b: Int) {
            try {
                delegate.write(b)
            } catch (_: Throwable) {}
            appendByte(b)
        }

        override fun write(buf: ByteArray, off: Int, len: Int) {
            try {
                delegate.write(buf, off, len)
            } catch (_: Throwable) {}
            for (i in 0 until len) appendByte(buf[off + i].toInt() and 0xFF)
        }

        override fun flush() {
            try { delegate.flush() } catch (_: Throwable) {}
            // Emit any pending partial line on explicit flush so
            // print() without a trailing newline still reaches us.
            flushLine()
        }

        override fun close() {
            flush()
            try { delegate.close() } catch (_: Throwable) {}
        }

        private fun appendByte(b: Int) {
            // ASCII newline ends a line. We intentionally treat \r as
            // a no-op rather than a line terminator — Windows-style
            // \r\n flushes on the \n.
            if (b == 0x0A) {
                flushLine()
            } else if (b != 0x0D) {
                val sb = buffer.get()
                sb.append(b.toChar())
                // Cap per-line buffer at 8 KB to bound a runaway
                // log that never emits a newline.
                if (sb.length >= 8 * 1024) flushLine()
            }
        }

        private fun flushLine() {
            val sb = buffer.get()
            if (sb.isEmpty()) return
            val line = sb.toString()
            sb.clear()
            // Skip whitespace-only lines — typical print() at the end
            // of a buffered write emits an extra blank.
            if (line.isBlank()) return
            try {
                emit(ConsoleEventData(level = level, message = line))
            } catch (_: Throwable) {
                // Emit failures must NEVER break the host app's logging
                // — silent drop is the only safe behavior.
            }
        }
    }

    /** Sink for PrintStream's own internal buffering — every byte
     *  also goes through our overridden write() before reaching here. */
    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) { /* discarded — write(int) handles forwarding */ }
        override fun write(b: ByteArray, off: Int, len: Int) { /* same */ }
    }
}
