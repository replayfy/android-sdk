package com.replayfy.android.internal.capture

import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import com.replayfy.android.internal.NativeSnapshotEventData
import com.replayfy.android.internal.NativeViewNode
import com.replayfy.android.internal.tracker.WindowRootDiscovery
import com.replayfy.android.internal.tracker.WindowRootDiscovery.WindowRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Schedules + executes view-tree snapshots, optionally with a
 * full-screen bitmap attached as the root node's `imageRef`.
 *
 * Flow per capture:
 *   1. (main thread) Build the tree via [ViewTreeSerializer] —
 *      always emits, even if bitmap capture later fails.
 *   2. (main thread) Capture the bitmap via [BitmapCapture] —
 *      synchronous, Legacy View.draw path.
 *   3. (background) Encode to PNG + SHA-256 hash via
 *      [BitmapCapture.encodeAndHash].
 *   4. (background) Upload via [AssetUploader.uploadOrCached] —
 *      cached hits skip the network round-trip.
 *   5. (background) Emit the snapshot event with `root.imageRef`
 *      set to the resolved URL (or null when capture/upload failed,
 *      in which case the player falls back to wireframe).
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

    /** Wired in by [ReplayCore.init] once config + apiKey arrive.
     *  Null pre-init — snapshots fall back to tree-only. */
    @Volatile
    var assetUploader: AssetUploader? = null

    /** Posts the first bitmap of each session as the dashboard
     *  Recordings-list thumbnail. Reset by ReplayCore on each new
     *  session. */
    @Volatile
    var thumbnailUploader: ThumbnailUploader? = null

    /** Provides the current session's publicId — needed by the
     *  thumbnail endpoint. Snapshot-time lookup so we always use
     *  the live session id (background→foreground rotations
     *  would otherwise stamp the previous session). */
    @Volatile
    var sessionIdProvider: (() -> String?)? = null

    /** Reflects [com.replayfy.android.ReplayConfig.captureSnapshotPixels].
     *  False until config lands or when remote config flips it off. */
    @Volatile
    var captureBitmaps: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    private val backgroundScope = CoroutineScope(Dispatchers.IO)

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
     * Main-thread phase: walk the View tree, then kick off the
     * pluggable capture pipeline. The pipeline returns asynchronously
     * (the PixelCopy path callbacks on a background HandlerThread);
     * the [BitmapCapture.captureAsync] callback handles the
     * background-side encode + upload + emit.
     *
     * Tree walk runs FIRST so the tree and the bitmap reflect the
     * same frame as closely as possible. PixelCopy may pick up the
     * NEXT vsync's framebuffer, which means a one-frame mismatch is
     * possible during heavy scrolls. UXCam exhibits the same
     * behavior — acceptable given the alternative is black surfaces.
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

            // No pixel capture requested → emit tree-only immediately.
            if (!captureBitmaps) {
                emitPayload(tree, primary, dm, trigger, imageRef = null)
                return
            }

            // Resolve the Window to feed PixelCopy. Only safe to use
            // the activity's window if the picked root IS the
            // activity's decor view — otherwise PixelCopy would
            // capture the wrong source. For dialog / popup roots the
            // selector falls back to Legacy (which doesn't need a
            // Window).
            val activityDecor: View? = try {
                activity.window?.peekDecorView()
            } catch (_: Throwable) { null }
            val window: android.view.Window? =
                if (activityDecor != null && primary.view === activityDecor) {
                    activity.window
                } else null

            // Snapshot property locals before going async — the
            // `var`s could change between dispatch + callback.
            val uploader = assetUploader
            val thumbUploader = thumbnailUploader
            val sessionId = sessionIdProvider?.invoke()

            BitmapCapture.captureAsync(
                root = primary.view,
                window = window,
                params = primary.params,
            ) { bitmap ->
                // Callback may land on main (Legacy) OR on the
                // PixelCopy HandlerThread. Either way, hop to IO
                // for the encode + upload + emit work — encodeAndHash
                // is CPU + memory heavy.
                if (bitmap == null) {
                    // Emit must still happen so the player has a
                    // wireframe; do it on the same dispatcher we'd
                    // have used for the asset path for consistency.
                    backgroundScope.launch {
                        emitPayload(tree, primary, dm, trigger, imageRef = null)
                    }
                    return@captureAsync
                }
                backgroundScope.launch {
                    // Thumbnail FIRST — it borrows the bitmap (makes
                    // its own scaled copy internally), doesn't
                    // recycle. No-op after the first send per session.
                    if (thumbUploader != null && sessionId != null) {
                        thumbUploader.sendIfFirst(bitmap, sessionId)
                    }
                    // Now hand bitmap to encodeAndHash, which DOES
                    // recycle. Order matters — calling thumbnail
                    // after encodeAndHash would deref a recycled
                    // bitmap.
                    val asset = BitmapCapture.encodeAndHash(bitmap)
                    val imageRef = if (asset != null && uploader != null) {
                        uploader.uploadOrCached(asset)
                    } else null
                    emitPayload(tree, primary, dm, trigger, imageRef)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "snapshot failed: ${t.message}")
        }
    }

    /**
     * Stitch the bitmap URL into the root node + emit the event.
     * The root's `imageRef` lets the player render a pixel-accurate
     * background; nested image nodes (Image / Icon widgets) will be
     * populated in a follow-up commit that does per-node bitmap
     * extraction.
     */
    private fun emitPayload(
        tree: NativeViewNode,
        primary: WindowRoot,
        dm: DisplayMetrics,
        trigger: String,
        imageRef: String?,
    ) {
        val rootWithImage = if (imageRef != null) {
            tree.copy(imageRef = imageRef)
        } else {
            tree
        }
        emit(
            NativeSnapshotEventData(
                recorder = "native",
                width = primary.view.width,
                height = primary.view.height,
                pixelRatio = dm.density.toDouble(),
                trigger = trigger,
                root = rootWithImage,
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
