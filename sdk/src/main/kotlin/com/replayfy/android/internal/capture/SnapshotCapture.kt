package com.replayfy.android.internal.capture

import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
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

    fun captureNow(trigger: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doCapture(trigger)
        } else {
            mainHandler.post { doCapture(trigger) }
        }
    }

    fun scheduleIdle() {
        val myGen = generation.incrementAndGet()
        mainHandler.postDelayed({
            if (generation.get() != myGen) return@postDelayed
            doCapture("idle")
        }, idleDebounceMs)
    }

    fun cancelPending() {
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Main-thread phase: capture tree + bitmap synchronously, hand
     * off to background for encode + upload + emit.
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

            // Bitmap capture is synchronous, must run on main. We do
            // it BEFORE handing off to background so the view tree
            // and pixels are from the same frame — otherwise a fast
            // scroll between tree-walk and bitmap could produce a
            // snapshot whose nodes don't match the painted pixels.
            val bitmap = if (captureBitmaps) {
                BitmapCapture.capture(primary.view, primary.params)
            } else null

            lastSnapshotAtMs = now

            // Hand off to background for encode + upload + emit. If
            // there's no bitmap (capture disabled or failed), emit
            // immediately on this thread — no async work needed.
            if (bitmap == null) {
                emitPayload(tree, primary, dm, trigger, imageRef = null)
                return
            }

            // Snapshot the property locals before going async — the
            // `var` could change between launch + body execution.
            val uploader = assetUploader
            backgroundScope.launch {
                val asset = BitmapCapture.encodeAndHash(bitmap) // recycles bitmap
                val imageRef = if (asset != null && uploader != null) {
                    uploader.uploadOrCached(asset)
                } else null
                emitPayload(tree, primary, dm, trigger, imageRef)
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
