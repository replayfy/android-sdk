package com.replayfy.android.internal.privacy

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import java.util.Collections
import java.util.WeakHashMap

/**
 * Tracks Android Views that the customer has marked as privacy-
 * sensitive via [com.replayfy.android.Replay.addPrivacyView]. Consulted by:
 *
 *   - [com.replayfy.android.internal.tracker.TapTracker] — taps
 *     on/inside a marked view get `isSensitive: true` with
 *     uiClass/uiValue/uiId blanked.
 *   - [com.replayfy.android.internal.capture.ViewTreeSerializer] —
 *     nodes inside a marked subtree get `occluded: true` with text
 *     + ariaLabel blanked.
 *   - [com.replayfy.android.internal.capture.BitmapCapture] — paints
 *     a diagonal-stripe pattern over each privacy view's bounds AFTER
 *     view.draw(canvas) so the captured PNG never carries the pixels.
 *
 * Weak refs throughout — never retains the customer's views. Views
 * removed from the hierarchy + GC'd drop out of the registry
 * automatically.
 *
 * Singleton because the public [com.replayfy.android.Replay.addPrivacyView]
 * API is static (matches UXCam + the iOS SDK pattern). One registry
 * per process.
 *
 * Mirrors the iOS [PrivacyRegistry.swift] — same triple-layer
 * defense pattern so iOS + Android sessions render identically on
 * the dashboard.
 */
internal object PrivacyRegistry {

    // WeakHashMap gives us weak-key Set semantics. We synchronize
    // for safety since add/remove/isSensitive can fire from main
    // (customer code, tap tracker) AND background (snapshot capture
    // running encode + paint off the UI thread).
    private val table: MutableMap<View, Unit> =
        Collections.synchronizedMap(WeakHashMap())
    private val lock = Any()

    // -----------------------------------------------------------------
    //  Bulk-privacy flags (UXCam parity)
    // -----------------------------------------------------------------
    //
    // UXCam ships three opt-in switches that occlude broad classes of
    // views without per-view registration:
    //
    //   occludeAllTextFields  → every EditText is treated as sensitive
    //                           (input fields the user types into)
    //   occludeAllTextViews   → every TextView (and subclasses like
    //                           Button, EditText) is treated as sensitive
    //                           (anything that DISPLAYS text)
    //   occludeAllScreen      → whole captured snapshot is painted over
    //                           with the diagonal-stripe overlay
    //
    // Flags are checked in isSensitive() + BitmapCapture's overlay
    // pass; setting them mid-session takes effect on the NEXT snapshot.

    @Volatile var occludeAllTextFields: Boolean = false
    @Volatile var occludeAllTextViews: Boolean = false
    @Volatile var occludeAllScreen: Boolean = false

    // -----------------------------------------------------------------
    //  Per-class occlusion (UXCam applyOcclusion / removeOcclusion)
    // -----------------------------------------------------------------
    //
    // Customers can register arbitrary [View] subclass keys and every
    // instance of that class (or its descendants) gets occluded.
    // Goes beyond the bulk text-fields / text-views flags — useful
    // when the customer has a custom widget (e.g. CreditCardInput,
    // MedicalRecordCard) they want default-occluded across the app.
    //
    // Read by isSensitive() AFTER the bulk flags + BEFORE the
    // per-view ancestor walk. instanceof check is O(class-set-size)
    // per node; class sets are tiny in practice (single-digit count).

    private val occludedClasses: MutableSet<Class<out View>> =
        Collections.synchronizedSet(HashSet())

    /** Add a View subclass — every instance gets occluded on the
     *  next snapshot. Idempotent. Mirrors UXCam's
     *  `applyOcclusion(Class<? extends View>)`. */
    fun applyOcclusion(viewClass: Class<out View>) {
        synchronized(occludedClasses) { occludedClasses.add(viewClass) }
    }

    /** Remove a previously-registered class. Safe to call even when
     *  the class was never added. */
    fun removeOcclusion(viewClass: Class<out View>) {
        synchronized(occludedClasses) { occludedClasses.remove(viewClass) }
    }

    /** Test whether [view] is an instance of any registered class.
     *  Hot path — kept allocation-free. */
    private fun matchesOccludedClass(view: View): Boolean {
        if (occludedClasses.isEmpty()) return false
        synchronized(occludedClasses) {
            for (cls in occludedClasses) {
                if (cls.isInstance(view)) return true
            }
        }
        return false
    }

    // -----------------------------------------------------------------
    //  Pending one-shot rects (RN / Flutter bridges)
    // -----------------------------------------------------------------
    //
    // React Native + Flutter components don't have a native View we
    // can register via add(view). The cross-platform bridge passes
    // raw root-relative rects (calculated on the JS / Dart side via
    // measureInWindow / RenderBox.localToGlobal) and asks the SDK to
    // paint over them on the NEXT captured frame.
    //
    // One-shot semantics: BitmapCapture drains the list on every
    // snapshot. Bridges call setPendingFrameRects(...) every frame
    // before triggering a snapshot; if no snapshot fires the rects
    // stay buffered until the next capture (no leakage — they get
    // overwritten by the next setPendingFrameRects call).

    private val pendingRectsLock = Any()
    private var pendingFrameRects: List<Rect> = emptyList()

    /** Bridge entry point — set the rects to paint on the next
     *  snapshot. Replaces (does not append to) any prior pending set. */
    fun setPendingFrameRects(rects: List<Rect>) {
        synchronized(pendingRectsLock) {
            pendingFrameRects = rects.toList() // defensive copy
        }
    }

    /** Snapshot + clear atomically so we never paint the same rect
     *  twice (e.g. if two snapshots fire back-to-back). Called by
     *  BitmapCapture once per capture. */
    fun consumePendingFrameRects(): List<Rect> {
        return synchronized(pendingRectsLock) {
            val snapshot = pendingFrameRects
            pendingFrameRects = emptyList()
            snapshot
        }
    }

    /** Mark a view as sensitive. Idempotent — adding the same view
     *  twice is a no-op. */
    fun add(view: View) {
        synchronized(lock) { table[view] = Unit }
    }

    /** Remove a previously-marked view. Safe to call even if the
     *  view was never added. */
    fun remove(view: View) {
        synchronized(lock) { table.remove(view) }
    }

    /**
     * Whether the view itself OR any ancestor (via parent chain) is
     * in the registry. Catches the common case where the customer
     * marks a ViewGroup (e.g. the LinearLayout for the payment
     * method row) and expects every descendant to inherit the mark.
     */
    fun isSensitive(view: View): Boolean = synchronized(lock) {
        // Whole-screen overlay → every view is sensitive. Cheap
        // short-circuit before the per-view checks.
        if (occludeAllScreen) return true
        // Bulk class-based occlusion. EditText extends TextView, so
        // the textViews check ALSO catches input fields; the
        // textFields flag is the narrower opt-in for cases where
        // the customer wants to hide ONLY user-typed content
        // (passwords, credit cards) without redacting labels +
        // buttons.
        if (occludeAllTextFields && view is android.widget.EditText) return true
        if (occludeAllTextViews && view is android.widget.TextView) return true
        // Per-class occlusion (applyOcclusion). Cheap when the class
        // set is empty (most apps).
        if (matchesOccludedClass(view)) return true
        if (table.containsKey(view)) return true
        var parent = view.parent
        while (parent != null) {
            if (parent is View && table.containsKey(parent)) return true
            parent = parent.parent
        }
        return false
    }

    /**
     * Enumerate ALL views in the [root] tree whose class matches a
     * currently-enabled bulk-privacy flag (EditText if
     * `occludeAllTextFields`, TextView if `occludeAllTextViews`).
     * Used by BitmapCapture to paint stripe overlays over the
     * matching regions in the captured bitmap.
     *
     * Walks the tree once; cost is O(views-in-tree) per snapshot
     * which is the same order as ViewTreeSerializer's tree-walk
     * (negligible vs the bitmap-encode cost). Skipped entirely
     * when neither flag is set — zero overhead for customers not
     * using bulk privacy.
     */
    fun bulkBounds(root: View): List<Rect> {
        val wantFields = occludeAllTextFields
        val wantViews = occludeAllTextViews
        val hasClassFilter = occludedClasses.isNotEmpty()
        if (!wantFields && !wantViews && !hasClassFilter) return emptyList()
        val out = ArrayList<Rect>()
        val rootLoc = IntArray(2).also { root.getLocationOnScreen(it) }
        walkBulk(root, rootLoc, wantFields, wantViews, hasClassFilter, out)
        return out
    }

    private fun walkBulk(
        view: View,
        rootLoc: IntArray,
        wantFields: Boolean,
        wantViews: Boolean,
        hasClassFilter: Boolean,
        out: MutableList<Rect>,
    ) {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return
        val matches = (wantFields && view is android.widget.EditText) ||
            // EditText extends TextView; if the customer enabled
            // both flags we'd add the same rect twice. The wantFields
            // branch handles EditText; wantViews picks up the rest.
            (wantViews && view is android.widget.TextView &&
                view !is android.widget.EditText) ||
            // Per-class occlusion (applyOcclusion). Wrapped in the
            // cached flag so empty-set apps don't pay the
            // instanceof loop cost on every view.
            (hasClassFilter && matchesOccludedClass(view))
        if (matches) {
            val loc = IntArray(2).also { view.getLocationOnScreen(it) }
            out.add(
                Rect(
                    loc[0] - rootLoc[0],
                    loc[1] - rootLoc[1],
                    loc[0] - rootLoc[0] + view.width,
                    loc[1] - rootLoc[1] + view.height,
                ),
            )
            // Don't recurse — text widgets don't contain other
            // text widgets meaningfully, and stopping here keeps
            // the bounds list small.
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                walkBulk(child, rootLoc, wantFields, wantViews, hasClassFilter, out)
            }
        }
    }

    /**
     * Enumerate currently-tracked views and return their bounds
     * relative to [root]. Used by BitmapCapture to know where to
     * paint the overlay. Skips views that aren't shown / have zero
     * size / aren't descendants of [root].
     */
    fun sensitiveBounds(root: View): List<Rect> = synchronized(lock) {
        val out = ArrayList<Rect>()
        val rootLoc = IntArray(2).also { root.getLocationOnScreen(it) }
        // Snapshot keys to avoid mutation during iteration (the
        // WeakHashMap can drop entries between iterator advance + read).
        val snapshot = ArrayList(table.keys)
        for (v in snapshot) {
            if (v == null) continue
            if (!v.isShown) continue
            if (v.width <= 0 || v.height <= 0) continue
            if (!isAncestorOf(root, v)) continue
            val vLoc = IntArray(2).also { v.getLocationOnScreen(it) }
            // Convert screen coords → root-relative coords.
            val left = vLoc[0] - rootLoc[0]
            val top = vLoc[1] - rootLoc[1]
            out.add(Rect(left, top, left + v.width, top + v.height))
        }
        return out
    }

    /** Walk up the child's parent chain checking if [root] is on it.
     *  Faster than View.isDescendantOf for our hot path because we
     *  don't recurse into siblings. */
    private fun isAncestorOf(root: View, child: View): Boolean {
        var p: android.view.ViewParent? = child.parent
        while (p != null) {
            if (p === root) return true
            p = p.parent
        }
        // Edge case: child IS root.
        return child === root
    }

    // -----------------------------------------------------------------
    //  Jetpack Compose support — Compose composables aren't Views,
    //  so the View-ancestor walk above won't catch
    //  Modifier.replayOcclude. The Compose wrapper registers the
    //  composable's window-relative bounds keyed by an opaque token
    //  and unregisters on dispose. TapTracker + ViewTreeSerializer
    //  + BitmapCapture all consult [isSensitiveAtPoint] / [composeBounds]
    //  in addition to the View-based checks.
    // -----------------------------------------------------------------

    /** Window-relative rect for one Compose composable marked via
     *  Modifier.replayOcclude. Keyed by an opaque token (typically
     *  the modifier's remembered instance) so the same composable
     *  can update its rect on re-layout without growing the table. */
    private val composeBounds: MutableMap<Any, Rect> =
        Collections.synchronizedMap(HashMap())

    /** Register or update Compose-composable bounds. Called from
     *  Modifier.replayOcclude's onGloballyPositioned callback. */
    fun addComposeBounds(token: Any, rectInWindow: Rect) {
        synchronized(lock) { composeBounds[token] = rectInWindow }
    }

    /** Unregister. Called from the modifier's DisposableEffect.onDispose. */
    fun removeComposeBounds(token: Any) {
        synchronized(lock) { composeBounds.remove(token) }
    }

    /** Whether the given screen-relative point falls inside any
     *  currently-registered Compose marker. Called by TapTracker at
     *  tap time (after converting touch coords to window coords). */
    fun isSensitiveAtPoint(xInWindow: Int, yInWindow: Int): Boolean =
        synchronized(lock) {
            for (rect in composeBounds.values) {
                if (rect.contains(xInWindow, yInWindow)) return true
            }
            return false
        }

    /** Whether any registered Compose marker's bounds intersect the
     *  given rect. Used by ViewTreeSerializer at snapshot time to
     *  mark nodes whose painted region overlaps a Compose-occluded
     *  composable. */
    fun isSensitiveByBoundsIntersect(rectInWindow: Rect): Boolean =
        synchronized(lock) {
            for (rect in composeBounds.values) {
                if (Rect.intersects(rect, rectInWindow)) return true
            }
            return false
        }

    /** Snapshot of all currently-registered Compose bounds in
     *  root-relative coords (subtracting [root]'s window position).
     *  Used by BitmapCapture to paint the diagonal-stripe overlay
     *  over Compose-occluded regions in the captured PNG. */
    fun composeBoundsRelativeTo(root: View): List<Rect> = synchronized(lock) {
        if (composeBounds.isEmpty()) return emptyList()
        val rootLoc = IntArray(2).also { root.getLocationOnScreen(it) }
        val out = ArrayList<Rect>(composeBounds.size)
        for (rect in composeBounds.values) {
            out.add(Rect(
                rect.left - rootLoc[0],
                rect.top - rootLoc[1],
                rect.right - rootLoc[0],
                rect.bottom - rootLoc[1],
            ))
        }
        return out
    }

    /** Wipe the registry. Used on opt-out / SDK stop. */
    fun clear() {
        synchronized(lock) {
            table.clear()
            composeBounds.clear()
        }
    }

    // Suppress unused — ViewGroup import used in doc; future
    // ancestor optimizations may use it directly.
    @Suppress("unused")
    private val _viewGroupRef: Class<ViewGroup>? = null
}
