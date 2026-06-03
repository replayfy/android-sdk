package com.replayfy.android.internal.capture

import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import com.replayfy.android.internal.NativeSnapshotEventData
import com.replayfy.android.internal.NativeViewNode
import com.replayfy.android.internal.tracker.WindowRootDiscovery
import com.replayfy.android.internal.tracker.WindowRootDiscovery.WindowRoot
import java.util.concurrent.atomic.AtomicLong

/**
 * Schedules + executes view-tree snapshots.
 *
 * Flow per capture (main thread): build the tree via
 * [ViewTreeSerializer] and emit it. (The legacy full-screen-bitmap
 * branch — encode + hash + asset/thumbnail upload — was removed along
 * with the /v1/replay/assets + /v1/replay/thumbnail endpoints; the
 * current SDK streams pixels over the binary /v1/mobile protocol via
 * MobileEngine, not through this path.)
 *
 * Trigger policies (from docs/native-snapshot-format.md):
 *   • `screen_appeared` — called on activity resume + manual setRoute
 *   • `idle` — debounced 500ms after the last tap
 *   • `tap` — currently unused (idle covers it)
 *   • `manual` — Replay.captureSnapshot() (future API)
 *
 * Coalescing: a generation counter ensures the most-recent trigger
 * supersedes older pending captures. minIntervalMs (200ms) prevents
 * thrashing on devices that fire onResume twice in quick succession.
 */
internal class SnapshotCapture(
    private val activityProvider: () -> android.app.Activity?,
    private val emit: (NativeSnapshotEventData) -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)

    private val idleDebounceMs = 500L
    private val minIntervalMs = 200L

    @Volatile
    private var lastSnapshotAtMs: Long = 0L

    /**
     * Runtime gate that ReplayCore flips when the customer calls
     * `pauseRecording()` / `Replay.optOutSchematicRecordings(true)`.
     * Defaults to true (recording). When false: every entry point
     * — captureNow, scheduleIdle, screen-resume callback — early-
     * exits without touching the view tree or the bitmap pipeline.
     * Independent of the SDK's overall opt-out state (which short-
     * circuits at the push() level).
     */
    @Volatile var enabled: Boolean = true

    /**
     * UXCam-style periodic capture cadence.
     *
     * UXCam reverse-engineered: their default capture rate is
     * ~2 FPS (every 500 ms) during an active session. Without this,
     * a session that doesn't change screens produces ONE snapshot
     * total and the player feels like a static image. With it, the
     * player gets a continuous-feeling timeline of frames matching
     * the user's perception of "video".
     *
     * Wired by [start] / [stop]; the periodic callback fires
     * `captureNow("periodic")` which goes through the same gate
     * (enabled flag + minIntervalMs throttle) as every other
     * trigger. Pause-recording / opt-out automatically silence it
     * via the `enabled` short-circuit.
     */
    @Volatile var periodicIntervalMs: Long = 500L
    private var periodicRunnable: Runnable? = null

    /**
     * Start the periodic capture loop. Idempotent — repeat calls
     * cancel the previous Runnable so we never double-schedule.
     * Called by [com.replayfy.android.internal.ReplayCore] after
     * init lands and the snapshot uploader is wired.
     */
    fun startPeriodic() {
        stopPeriodic()
        val task = object : Runnable {
            override fun run() {
                if (enabled) {
                    // Don't post if a recent snapshot fired (minIntervalMs
                    // throttle already gates this in doCapture, but we
                    // skip the Handler hop too).
                    val now = System.currentTimeMillis()
                    if (now - lastSnapshotAtMs >= periodicIntervalMs) {
                        doCapture("periodic")
                    }
                }
                mainHandler.postDelayed(this, periodicIntervalMs)
            }
        }
        periodicRunnable = task
        mainHandler.postDelayed(task, periodicIntervalMs)
    }

    /**
     * Stop the periodic loop. Called from ReplayCore.stop /
     * pauseRecording so we don't churn out captures while the SDK
     * is paused or torn down.
     */
    fun stopPeriodic() {
        periodicRunnable?.let { mainHandler.removeCallbacks(it) }
        periodicRunnable = null
    }

    fun captureNow(trigger: String) {
        if (!enabled) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doCapture(trigger)
        } else {
            mainHandler.post { doCapture(trigger) }
        }
    }

    fun scheduleIdle() {
        if (!enabled) return
        val myGen = generation.incrementAndGet()
        mainHandler.postDelayed({
            if (generation.get() != myGen) return@postDelayed
            if (!enabled) return@postDelayed
            doCapture("idle")
        }, idleDebounceMs)
    }

    fun cancelPending() {
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Main-thread phase: walk the View tree and emit the snapshot.
     * Tree-only — pixel capture for this path was retired with the
     * asset/thumbnail upload endpoints; MobileEngine streams the
     * actual screenshot bundle over the binary protocol.
     */
    private fun doCapture(trigger: String) {
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAtMs < minIntervalMs) return
        val activity = activityProvider() ?: return

        try {
            val roots = WindowRootDiscovery.rootsFor(activity)
            val primary = pickPrimaryRoot(roots) ?: return

            val tree = ViewTreeSerializer.serialize(primary.view) ?: return
            val dm: DisplayMetrics = activity.resources.displayMetrics

            lastSnapshotAtMs = now
            emitPayload(tree, primary, dm, trigger)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "snapshot failed: ${t.message}")
        }
    }

    /** Emit the view-tree snapshot event. */
    private fun emitPayload(
        tree: NativeViewNode,
        primary: WindowRoot,
        dm: DisplayMetrics,
        trigger: String,
    ) {
        emit(
            NativeSnapshotEventData(
                recorder = "native",
                width = primary.view.width,
                height = primary.view.height,
                pixelRatio = dm.density.toDouble(),
                trigger = trigger,
                root = tree,
            ),
        )
    }

    private fun pickPrimaryRoot(roots: List<WindowRoot>): WindowRoot? {
        if (roots.isEmpty()) return null
        for (i in roots.indices.reversed()) {
            val r = roots[i]
            if (r.view.isShown && r.view.width > 0 && r.view.height > 0) {
                return r
            }
        }
        return roots.firstOrNull()
    }

    private companion object {
        private const val TAG = "ReplaySdk"
    }
}
