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
 */
class MobileCollector(private val transport: MobileTransport) {
    private val lock = Any()
    private val waiting = ArrayList<ByteArray>()
    private var nextIndex = 0L
    private var scheduler: ScheduledExecutorService? = null
    private val sender = Executors.newSingleThreadExecutor()

    private val maxBatchBytes = 500_000
    private val flushIntervalMs = 5_000L

    fun start() {
        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate({ flush() }, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        scheduler?.shutdownNow(); scheduler = null
        flush(late = true)
    }

    fun add(message: ByteArray) {
        val total: Int
        synchronized(lock) {
            if (waiting.size < 10_000) waiting.add(message)
            total = waiting.sumOf { it.size }
        }
        if (total > (maxBatchBytes * 0.8).toInt()) flush()
    }

    fun flush(late: Boolean = false) {
        val batch = ArrayList<ByteArray>()
        val startIndex: Long
        synchronized(lock) {
            if (waiting.isEmpty()) return
            var size = 0
            while (waiting.isNotEmpty() && size + waiting[0].size <= maxBatchBytes) {
                val m = waiting.removeAt(0); batch.add(m); size += m.size
            }
            startIndex = nextIndex
            nextIndex += batch.size
        }

        val content = ByteArrayOutputStream()
        content.write(MobileWire.batchMeta(startIndex, System.currentTimeMillis()))
        for (m in batch) content.write(m)
        val raw = content.toByteArray()

        sender.execute {
            val ok = if (late) {
                transport.sendLateMessages(raw)
            } else {
                transport.sendMessages(MobileFrames.gzip(raw))
            }
            if (!ok && !late) {
                synchronized(lock) { waiting.addAll(0, batch) }
            }
        }
    }
}
