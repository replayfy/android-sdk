package com.replayfy.android.internal.capture

import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import com.replayfy.android.internal.NativeSnapshotEventData
import com.replayfy.android.internal.tracker.WindowRootDiscovery
import com.replayfy.android.internal.tracker.WindowRootDiscovery.WindowRoot
import java.util.concurrent.atomic.AtomicLong

/**
 * Schedules + executes view-tree snapshots and emits the resulting
 * payloads to ReplayCore.
 *
 * Trigger policies (from docs/native-snapshot-format.md):
 *   • `screen_appeared` — called by the orchestrator on activity
 *     resume + manual setRoute. Runs immediately on the next vsync
 *     so the tree we capture matches what the user sees.
 *   • `idle` — debounced 500ms after the last tap. Catches
 *     post-interaction state (modal opened, list scrolled to a new
 *     position, etc.).
 *   • `tap` — request a snapshot if a tap mutated the tree (out of
 *     scope for v1; the idle trigger covers it).
 *   • `manual` — Replay.captureSnapshot() (future API).
 *
 * Coalescing: when multiple triggers fire close together (e.g. tap
 * during a screen transition fires both `screen_appeared` and
 * `idle`), only the most-recent trigger's snapshot is emitted. We
 * deduplicate by a monotonic generation counter; older scheduled
 * captures bail out on wake.
 *
 * All capture work runs on the main thread because View tree access
 * is main-thread-only on Android. JSON serialization happens later
 * in BatchSender on Dispatchers.IO.
 */
internal class SnapshotCapture(
    /** Source of the currently-resumed Activity for window-root
     *  lookup. Same provider TapTracker uses. */
    private val activityProvider: () -> android.app.Activity?,
    /** Called with the assembled payload to be relayed as a
     *  `native_snapshot` event. */
    private val emit: (NativeSnapshotEventData) -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)

    /** Debounce delay for idle-triggered snapshots, matches the
     *  500ms documented in the snapshot-format spec. */
    private val idleDebounceMs = 500L

    /** Last snapshot serialization timestamp. Used to skip rapid
     *  back-to-back captures (e.g. transition fires `screen_appeared`
     *  twice in quick succession on some devices). */
    @Volatile
    private var lastSnapshotAtMs: Long = 0L

    /** Minimum gap between snapshots, regardless of trigger. */
    private val minIntervalMs = 200L

    /**
     * Snapshot the current screen NOW. Caller specifies the trigger
     * for the player's render heuristic. No-op if no activity is
     * resumed (e.g. app is fully backgrounded).
     */
    fun captureNow(trigger: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doCapture(trigger)
        } else {
            mainHandler.post { doCapture(trigger) }
        }
    }

    /**
     * Schedule a snapshot to fire on the next idle window. Cancels
     * any previously-scheduled idle capture so only the latest
     * trigger fires.
     */
    fun scheduleIdle() {
        val myGen = generation.incrementAndGet()
        mainHandler.postDelayed({
            // Bail if a newer trigger superseded us before the delay
            // elapsed.
            if (generation.get() != myGen) return@postDelayed
            doCapture("idle")
        }, idleDebounceMs)
    }

    /** Cancel any pending idle capture without firing. */
    fun cancelPending() {
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun doCapture(trigger: String) {
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAtMs < minIntervalMs) return
        val activity = activityProvider() ?: return

        try {
            val roots = WindowRootDiscovery.rootsFor(activity)
            val primary = pickPrimaryRoot(roots) ?: return
            val tree = ViewTreeSerializer.serialize(primary.view) ?: return
            val dm: DisplayMetrics = activity.resources.displayMetrics
            val payload = NativeSnapshotEventData(
                recorder = "native",
                width = primary.view.width,
                height = primary.view.height,
                pixelRatio = dm.density.toDouble(),
                trigger = trigger,
                root = tree,
            )
            emit(payload)
            lastSnapshotAtMs = now
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "snapshot failed: ${t.message}")
        }
    }

    /**
     * Pick the "primary" window root to snapshot. Heuristic:
     *
     *   1. Dialogs / popups when present — they're what the user is
     *      currently interacting with.
     *   2. The first non-system window otherwise (typically the
     *      Activity's content view).
     *
     * In v2 we may capture ALL roots and ship them as a layered
     * tree (so the dashboard can render the underlying activity
     * behind a dialog). For v1, single-root keeps payload size
     * predictable.
     */
    private fun pickPrimaryRoot(roots: List<WindowRoot>): WindowRoot? {
        if (roots.isEmpty()) return null
        // Dialogs / popups have window type ≥ FIRST_SUB_WINDOW (1000)
        // or FIRST_APPLICATION_WINDOW + N. Simplest heuristic: take
        // the LAST root that's currently shown — Android's window
        // list is ordered such that newer windows (dialogs) come
        // after older ones (the Activity).
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

    // Suppress unused-warning for the import resolution; View is
    // referenced via WindowRoot.view downstream.
    @Suppress("unused")
    private val viewClassReference: Class<View>? = null
}
