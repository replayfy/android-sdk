package com.replayfy.android.internal

import com.replayfy.android.BuildConfig
import com.replayfy.android.ReplayConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the current session's in-memory state: id, segment id, sequence
 * counter, event buffer, start timestamp, SDK descriptor.
 *
 * One [SessionRuntime] per active session. The lifecycle observer
 * rotates the instance on foreground→background→foreground transitions.
 * Thread-safe — events are pushed from the main thread (lifecycle,
 * snapshots, taps) AND background threads (network capture, performance
 * observers), so the buffer + counters use atomic / synchronized
 * primitives.
 *
 * Mirrors the web SDK's `runtime.ts` design — kept intentionally close
 * so the two SDKs behave identically from the backend's perspective.
 */
internal class SessionRuntime(
    private val config: ReplayConfig,
    val deviceContext: ReplayPageContext,
    /** Host app's versionName from PackageInfo. Threaded into the
     *  sdk descriptor so the backend symbolication service can pick
     *  the right mapping.txt + NDK .so symbols for crash stacks
     *  from this batch. */
    val appVersion: String? = null,
    /** Host app's versionCode (as String for cross-platform parity
     *  with iOS CFBundleVersion which can be alphanumeric). */
    val appBuild: String? = null,
) {
    /** Public session id. Reused across all segments of this session. */
    val sessionId: String = "ses_" + UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * Segment id — every flush starts a new segment. The dashboard
     * uses segments to group events sharing a single batch upload,
     * which matters for partial-recovery scenarios (last segment lost
     * to a crash still leaves N-1 segments playable).
     */
    var segmentId: String = newSegmentId()
        private set

    /** Wall-clock millis when this session started. */
    val startedAt: Long = System.currentTimeMillis()

    /** Monotonic sequence per batch — backend dedupes on it. */
    private val sequence = AtomicInteger(0)

    /** Per-event id counter — combined with sessionId for global uniqueness. */
    private val eventCounter = AtomicLong(0)

    /** In-memory ring buffer of pending events. Drained on flush. */
    private val buffer = ArrayList<ReplayEvent>(128)
    private val bufferLock = Any()

    /** SDK descriptor sent in every envelope. */
    val sdk: ReplaySdkDescriptor = ReplaySdkDescriptor(
        name = BuildConfig.SDK_NAME,
        version = BuildConfig.SDK_VERSION,
        platform = PLATFORM_ANDROID,
        appVersion = appVersion,
        appBuild = appBuild,
    )

    /** Append an event. Returns the buffer size post-append so the
     *  caller can decide whether to force-flush. */
    fun push(event: ReplayEvent): Int = synchronized(bufferLock) {
        buffer.add(event)
        buffer.size
    }

    /** Drain the buffer atomically. Caller owns the returned list. */
    fun drain(): List<ReplayEvent> = synchronized(bufferLock) {
        if (buffer.isEmpty()) return emptyList()
        val out = buffer.toList()
        buffer.clear()
        out
    }

    /** Buffer size for force-flush decisions. */
    fun bufferSize(): Int = synchronized(bufferLock) { buffer.size }

    /** Generate the next event id. Format: `evt_<session-suffix>_<n>`. */
    fun makeEventId(): String =
        "evt_${sessionId.substring(4)}_${eventCounter.incrementAndGet()}"

    /** Advance the segment sequence and rotate the segment id. */
    fun nextSequence(): Int {
        segmentId = newSegmentId()
        return sequence.incrementAndGet()
    }

    /** Whether the buffer should be force-flushed based on cap. */
    fun shouldForceFlush(): Boolean =
        bufferSize() >= config.maxBufferSize

    private fun newSegmentId(): String =
        "seg_" + UUID.randomUUID().toString().replace("-", "").take(16)
}
