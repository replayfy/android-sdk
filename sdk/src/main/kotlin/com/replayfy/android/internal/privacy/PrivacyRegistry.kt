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

    /** Wipe the registry. Used on opt-out / SDK stop. */
    fun clear() {
        synchronized(lock) { table.clear() }
    }

    // Suppress unused — ViewGroup import used in doc; future
    // ancestor optimizations may use it directly.
    @Suppress("unused")
    private val _viewGroupRef: Class<ViewGroup>? = null
}
