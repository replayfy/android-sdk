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
        if (table.containsKey(view)) return true
        var parent = view.parent
        while (parent != null) {
            if (parent is View && table.containsKey(parent)) return true
            parent = parent.parent
        }
        return false
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
