package com.replayfy.android.internal.mobile

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Accumulates encoded binary messages and flushes them to
 * /v1/mobile/i on a 5-second cadence (or when the buffer crosses ~80%
 * of the 500 KB batch cap). Each flush is prefixed with a batchMeta
 * carrying the monotonic message index, then gzipped. Mirrors iOS.
 *
 * [lateStore] keeps the session's final (/late) batch on disk until the
 * server accepts it (see [MobileLateMessages]); null disables that.
 */
class MobileCollector(
    private val transport: MobileUploading,
    private val lateStore: MobileLateMessages? = null,
) {
    private val lock = Any()
    private val waiting = ArrayList<ByteArray>()
    private var nextIndex = 0L
    private var scheduler: ScheduledExecutorService? = null
    private val sender = Executors.newSingleThreadExecutor()

    private val maxBatchBytes = 500_000
    private val flushIntervalMs = 5_000L
    /** Batches one drain may send. Bounds a drain whose queue refills as fast
     *  as it uploads, so it still ends. */
    private val maxDrainRounds = 20

    // Send state, guarded by `lock`. One request is in flight at a time (the
    // loop runs on the single `sender` thread), so a failed batch put back at
    // the front of the queue keeps message order.
    private var sending = false
    private var roundsLeft = 0
    /** Session end: the batch that empties the queue goes to /late. */
    private var lateRequested = false
    /** discard() ran: drop everything and send nothing more. */
    private var discarded = false
    private val sendWaiters = ArrayList<() -> Unit>()

    fun start() {
        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate({ flush() }, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Session end: send everything queued, the last batch via /late (the
     *  server's end-of-session signal). The send loop holds `this`, so the
     *  drain finishes after ReplayCore drops its reference. [completion] runs
     *  once that drain settles. */
    fun stop(completion: (() -> Unit)? = null) {
        scheduler?.shutdownNow(); scheduler = null
        request(drain = true, late = true, completion = completion)
    }

    /** Drop everything buffered WITHOUT sending — the cancelSession counterpart
     *  to [stop] (which flushes). Stops the flush scheduler so nothing else
     *  leaves, and a drain already running stops before its next batch. */
    fun discard() {
        scheduler?.shutdownNow(); scheduler = null
        synchronized(lock) {
            discarded = true
            waiting.clear()
        }
    }

    fun add(message: ByteArray) {
        val total: Int
        synchronized(lock) {
            if (discarded) return
            if (waiting.size < 10_000) waiting.add(message)
            total = waiting.sumOf { it.size }
        }
        if (total > (maxBatchBytes * 0.8).toInt()) flush()
    }

    /** Send one batch (at most `maxBatchBytes`). A no-op while a send is in
     *  flight: the 5 s timer or the next size trigger picks the queue up. */
    fun flush() {
        request(drain = false, late = false, completion = null)
    }

    /** Send EVERY queued batch, one at a time, until the queue is empty, a send
     *  fails (that batch goes back to the front of the queue), or
     *  `maxDrainRounds` is hit. [flush] sends a single batch, which on
     *  backgrounding left the rest of the queue for a later timer tick.
     *  [completion] runs once the drain settles. */
    fun drain(completion: (() -> Unit)? = null) {
        request(drain = true, late = false, completion = completion)
    }

    private fun request(drain: Boolean, late: Boolean, completion: (() -> Unit)?) {
        val startLoop = synchronized(lock) {
            if (discarded) {
                false
            } else {
                // A session end sends the whole queue, however long: the engine
                // has let go of this collector, so nothing else would, and /late
                // must close it. Each round takes at least one message and the
                // queue no longer refills, so these rounds always empty it.
                // maxOf: a background drain racing the session end must not
                // shrink its budget.
                val rounds = when {
                    late -> waiting.size + maxDrainRounds
                    drain -> maxDrainRounds
                    else -> 0
                }
                roundsLeft = maxOf(roundsLeft, rounds)
                if (late) lateRequested = true
                completion?.let { sendWaiters.add(it) }
                // The send in flight continues into the wider request.
                if (sending) return
                sending = true
                if (!drain) roundsLeft = 1
                true
            }
        }
        // After discard() nothing is sent, but a caller waiting on the drain
        // still hears back.
        if (startLoop) sender.execute { sendLoop() } else completion?.let { runWaiters(listOf(it)) }
    }

    private fun sendLoop() {
        while (true) {
            val batch = ArrayList<ByteArray>()
            var late = false
            var startIndex = 0L
            var done: List<() -> Unit>? = null
            synchronized(lock) {
                if (discarded || roundsLeft <= 0 || waiting.isEmpty()) {
                    done = endSendingLocked()
                } else {
                    roundsLeft--
                    var size = 0
                    // Always take at least one message: one larger than the cap
                    // would otherwise never leave the head of the queue, and every
                    // send would be an empty batch.
                    while (waiting.isNotEmpty() && (batch.isEmpty() || size + waiting[0].size <= maxBatchBytes)) {
                        val m = waiting.removeAt(0); batch.add(m); size += m.size
                    }
                    late = lateRequested && waiting.isEmpty()
                    startIndex = nextIndex
                    nextIndex += batch.size
                }
            }
            done?.let { return runWaiters(it) }
            val raw = encode(startIndex, batch)

            if (late) {
                // Written before the send and deleted once it lands, so a session
                // end that never reached the server (offline, or the process died
                // mid-send) is resent on the next launch.
                val saved = transport.token?.let { lateStore?.save(it, raw) }
                val ok = try { transport.sendLateMessages(raw) } catch (_: Throwable) { false }
                if (ok) saved?.let { lateStore?.delete(it) }
                return runWaiters(synchronized(lock) { endSendingLocked() })
            }

            val ok = try { transport.sendMessages(MobileFrames.gzip(raw)) } catch (_: Throwable) { false }
            if (ok) continue

            var leftovers: List<ByteArray>? = null
            synchronized(lock) {
                if (!discarded) {
                    // Re-queue on failure (front of line, preserve order) and stop.
                    waiting.addAll(0, batch)
                    if (lateRequested) {
                        // Session end and the network just failed. The engine has
                        // released this collector, so nothing would retry the queue:
                        // keep it on disk as late batches for the next launch.
                        leftovers = ArrayList(waiting)
                        waiting.clear()
                    }
                }
            }
            leftovers?.let { persistLate(it) }
            return runWaiters(synchronized(lock) { endSendingLocked() })
        }
    }

    /** Pack [messages] into late batches of at most `maxBatchBytes` and save
     *  them without sending (see the /i failure path in [sendLoop]). */
    private fun persistLate(messages: List<ByteArray>) {
        val token = transport.token ?: return
        val store = lateStore ?: return
        var i = 0
        while (i < messages.size) {
            val batch = ArrayList<ByteArray>()
            var size = 0
            while (i < messages.size && (batch.isEmpty() || size + messages[i].size <= maxBatchBytes)) {
                batch.add(messages[i]); size += messages[i].size; i++
            }
            val startIndex = synchronized(lock) { nextIndex.also { nextIndex += batch.size } }
            store.save(token, encode(startIndex, batch))
        }
    }

    private fun encode(startIndex: Long, batch: List<ByteArray>): ByteArray {
        val content = ByteArrayOutputStream()
        content.write(MobileWire.batchMeta(startIndex, System.currentTimeMillis()))
        for (m in batch) content.write(m)
        return content.toByteArray()
    }

    /** Ends the send loop and hands back its waiters. Call with `lock` held,
     *  in the same hold as the check that ended the loop, so a request that
     *  arrives in between can't be dropped; run the waiters after releasing. */
    private fun endSendingLocked(): List<() -> Unit> {
        val waiters = ArrayList(sendWaiters)
        sendWaiters.clear()
        sending = false
        roundsLeft = 0
        lateRequested = false
        return waiters
    }

    private fun runWaiters(waiters: List<() -> Unit>) {
        waiters.forEach { runCatching { it() } }
    }

    /** Messages waiting to send. For tests. */
    internal val waitingCount: Int
        get() = synchronized(lock) { waiting.size }

    /**
     * Synchronous flush on the CALLING thread — used by the crash handler
     * so the crash message reaches the server before the process is torn
     * down (an async [flush] would be killed mid-send). Sends via the
     * un-gzipped /late path; bounded by the transport's socket timeout.
     */
    fun flushBlocking() {
        val batch = ArrayList<ByteArray>()
        val startIndex: Long
        synchronized(lock) {
            if (waiting.isEmpty()) return
            batch.addAll(waiting); waiting.clear()
            startIndex = nextIndex; nextIndex += batch.size
        }
        val raw = encode(startIndex, batch)
        // The crash handler runs on the main thread; a direct network call
        // there throws NetworkOnMainThreadException. Send on the background
        // sender and block the caller (up to 3s) until it completes.
        val done = java.util.concurrent.CountDownLatch(1)
        sender.execute {
            try { transport.sendLateMessages(raw) } catch (_: Throwable) {}
            done.countDown()
        }
        try { done.await(3, TimeUnit.SECONDS) } catch (_: Throwable) {}
    }
}
