package com.replayfy.android.internal.privacy

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import com.replayfy.android.MaskRect
import com.replayfy.android.ReplayMaskStyle
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
 * API is static (matches the reference mobile SDK + the iOS SDK pattern). One registry
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
    //  Bulk-privacy flags (the reference mobile SDK parity)
    // -----------------------------------------------------------------
    //
    // the reference mobile SDK ships three opt-in switches that occlude broad classes of
    // views without per-view registration:
    //
    //   occludeAllTextFields  → every EditText is treated as sensitive
    //                           (input fields the user types into)
    //   occludeAllTextViews   → every TextView (and subclasses like
    //                           Button, EditText) is treated as sensitive
    //                           (anything that DISPLAYS text)
    //   occludeAllScreen      → whole captured snapshot is painted over
    //                           with one solid box
    //
    // Flags are checked in isSensitive() + BitmapCapture's overlay
    // pass; setting them mid-session takes effect on the NEXT snapshot.

    @Volatile var occludeAllTextFields: Boolean = false
    @Volatile var occludeAllTextViews: Boolean = false
    @Volatile var occludeAllScreen: Boolean = false

    /** The dashboard's "always mask input values", from every /v1/mobile/start
     *  (a start without it clears it). OR-ed with the app's
     *  [occludeAllTextFields]: neither one can clear the other, so the
     *  dashboard can only add masking. */
    @Volatile var remoteOccludeAllTextFields: Boolean = false

    /** Every EditText is masked: the app or the dashboard asked for it. */
    val textFieldsOccluded: Boolean
        get() = occludeAllTextFields || remoteOccludeAllTextFields

    // Default render style for masked regions that don't carry their own
    // (addPrivacyView, occludeAll*, applyOcclusion, occludeRectsOnNextFrame).
    // OVERLAY (the default) paints a solid box, as the reference tracker
    // does; BLUR / PIXELATE obscure the pixels. Per-region styles (React
    // Native / Flutter mask widgets) override this on their own rects.
    // Auto-masked fields and the whole-screen cover are always solid.
    @Volatile var maskStyle: ReplayMaskStyle = ReplayMaskStyle.OVERLAY
    /** Downscale factor for BLUR regions — smaller = stronger blur. */
    @Volatile var blurDownscale: Int = 12

    // -----------------------------------------------------------------
    //  Per-class occlusion (the reference mobile SDK applyOcclusion / removeOcclusion)
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
     *  next snapshot. Idempotent. Mirrors the reference mobile SDK's
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
    private var pendingFrameRects: List<com.replayfy.android.MaskRect> = emptyList()

    /** Bridge entry point — set the rects (each with its own render style) to
     *  paint on the next snapshot. Replaces any prior pending set. */
    fun setPendingFrameRects(rects: List<com.replayfy.android.MaskRect>) {
        synchronized(pendingRectsLock) {
            pendingFrameRects = rects.toList() // defensive copy
        }
    }

    /** Snapshot + clear atomically so we never paint the same rect
     *  twice (e.g. if two snapshots fire back-to-back). */
    fun consumePendingFrameRects(): List<com.replayfy.android.MaskRect> {
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
     * Whether [view] is masked in recorded frames: the view itself OR any
     * ancestor (via parent chain) is in the registry or an occluded class,
     * a bulk flag covers it, it is an always-sensitive field
     * ([SensitiveInputRules]), or its window is FLAG_SECURE. Catches the
     * common case where the customer marks a ViewGroup (e.g. the
     * LinearLayout for the payment method row) and expects every descendant
     * to inherit the mark.
     *
     * Typed values and tap labels consult this, so a view whose pixels are
     * masked never ships its text by another route.
     */
    fun isSensitive(view: View): Boolean = synchronized(lock) {
        // Whole-screen overlay → every view is sensitive. Cheap
        // short-circuit before the per-view checks.
        if (occludeAllScreen) return true
        // A secure window records as a black frame: all of it is masked.
        if (isSecureWindow(view)) return true
        // Bulk class-based occlusion. EditText extends TextView, so
        // the textViews check ALSO catches input fields; the
        // textFields flag is the narrower opt-in for cases where
        // the customer wants to hide ONLY user-typed content
        // (passwords, credit cards) without redacting labels +
        // buttons. Password / card / one-time-code fields are always
        // masked.
        if (view is EditText && SensitiveInputRules.isSensitiveEditText(view, textFieldsOccluded)) return true
        if (occludeAllTextViews && view is TextView) return true
        // Per-class occlusion (applyOcclusion) and registered views, on the
        // view or an ancestor: frames mask an occluded class's whole subtree.
        if (matchesOccludedClass(view)) return true
        if (table.containsKey(view)) return true
        var parent = view.parent
        while (parent != null) {
            if (parent is View && (table.containsKey(parent) || matchesOccludedClass(parent))) return true
            parent = parent.parent
        }
        return false
    }

    /** Whether FLAG_SECURE is set on the window [view] belongs to. The window
     *  attributes are the root view's layout params once it is attached. */
    fun isSecureWindow(view: View): Boolean =
        ((view.rootView?.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0) and
            WindowManager.LayoutParams.FLAG_SECURE != 0

    /** Whether FLAG_SECURE is set on [window], by its attributes or by its
     *  decor view's (which follow the attributes once attached). */
    fun isSecureWindow(window: Window?): Boolean {
        if (window == null) return false
        if ((window.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0) return true
        return window.peekDecorView()?.let { isSecureWindow(it) } ?: false
    }

    /**
     * Enumerate ALL views in the [root] tree whose class matches a
     * currently-enabled bulk-privacy flag (EditText if
     * `occludeAllTextFields`, TextView if `occludeAllTextViews`),
     * a registered occluded class, OR an always-sensitive field
     * ([SensitiveInputRules]). Rects are in [root]'s coordinates.
     *
     * Kept for the legacy capture path; the frames screenshotter uses
     * [bulkMaskRects], which also carries each rect's render style.
     */
    fun bulkBounds(root: View): List<Rect> = bulkMaskRects(root, maskStyle).map { it.rect }

    /**
     * [bulkBounds] with each rect's render style: always-sensitive fields
     * (password, card, one-time code) are always [ReplayMaskStyle.OVERLAY],
     * as the reference tracker paints them solid (a blur can still show a
     * password's length); everything else gets [style]. When [webViews] is
     * given, every WebView the walk meets is added to it, so the same
     * traversal finds them.
     *
     * Walks the tree once; cost is O(views-in-tree) per snapshot
     * which is the same order as ViewTreeSerializer's tree-walk
     * (negligible vs the bitmap-encode cost). Always walks — password
     * fields are masked unconditionally, so we can't early-return even
     * when no bulk flag / class is set. No cap on the number of fields.
     */
    fun bulkMaskRects(
        root: View,
        style: ReplayMaskStyle,
        webViews: MutableList<WebView>? = null,
    ): List<MaskRect> {
        val out = ArrayList<MaskRect>()
        if (!root.isShown) return out
        val walk = BulkWalk(
            root = root,
            wantFields = textFieldsOccluded,
            wantViews = occludeAllTextViews,
            hasClassFilter = occludedClasses.isNotEmpty(),
            style = style,
            out = out,
            webViews = webViews,
        )
        walkBulk(root, walk)
        return out
    }

    /** One [bulkMaskRects] traversal's settings and output. */
    private class BulkWalk(
        val root: View,
        val wantFields: Boolean,
        val wantViews: Boolean,
        val hasClassFilter: Boolean,
        val style: ReplayMaskStyle,
        val out: MutableList<MaskRect>,
        val webViews: MutableList<WebView>?,
    )

    private fun walkBulk(view: View, walk: BulkWalk) {
        // The walk starts at a shown root, so a visible view here is shown.
        // No size prune: a zero-size parent that doesn't clip can still draw
        // its children.
        if (view.visibility != View.VISIBLE) return
        if (view is WebView) walk.webViews?.add(view)
        // Password / card / one-time-code inputs are ALWAYS masked,
        // regardless of the occludeAll* flags.
        val auto = view is EditText && SensitiveInputRules.isSensitiveEditText(view, false)
        val matches = auto ||
            (walk.wantFields && view is EditText) ||
            // EditText extends TextView; if the customer enabled
            // both flags we'd add the same rect twice. The wantFields
            // branch handles EditText; wantViews picks up the rest.
            (walk.wantViews && view is TextView && view !is EditText) ||
            // Per-class occlusion (applyOcclusion). Wrapped in the
            // cached flag so empty-set apps don't pay the
            // instanceof loop cost on every view.
            (walk.hasClassFilter && matchesOccludedClass(view))
        if (matches) {
            visibleRectIn(walk.root, view)?.let {
                walk.out.add(MaskRect(it, if (auto) ReplayMaskStyle.OVERLAY else walk.style))
            }
            // Don't recurse — the rect covers the subtree, and text
            // widgets don't contain other text widgets meaningfully.
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                walkBulk(child, walk)
            }
        }
    }

    /**
     * Where [v] shows on screen, in [root]'s coordinates, or null when it
     * shows nothing. Same geometry the reference tracker uses: visible, same
     * window, clipped global visible rect. The global visible rect is in
     * window coordinates and follows scrolling, clipping by ancestors and
     * transforms (a view scaled up is masked at its drawn size, not its
     * layout size).
     */
    private fun visibleRectIn(root: View, v: View): Rect? {
        if (v.visibility != View.VISIBLE || !v.isShown) return null
        if (v.rootView !== root.rootView) return null
        val r = Rect()
        if (!v.getGlobalVisibleRect(r) || r.isEmpty) return null
        if (root !== root.rootView) {
            val o = IntArray(2).also { root.getLocationInWindow(it) }
            r.offset(-o[0], -o[1])
        }
        return r
    }

    /**
     * Enumerate currently-tracked views and return their bounds
     * relative to [root]. Used by BitmapCapture to know where to
     * paint the overlay. Skips views that aren't shown / show nothing /
     * aren't descendants of [root].
     */
    fun sensitiveBounds(root: View): List<Rect> = synchronized(lock) {
        val out = ArrayList<Rect>()
        // Snapshot keys to avoid mutation during iteration (the
        // WeakHashMap can drop entries between iterator advance + read).
        val snapshot = ArrayList(table.keys)
        for (v in snapshot) {
            if (v == null) continue
            if (!isAncestorOf(root, v)) continue
            visibleRectIn(root, v)?.let { out.add(it) }
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
     *  Used by the screenshotters to mask Compose-occluded regions.
     *  boundsInWindow is window-space and the decor is the window origin,
     *  so the offset is the root's position in the WINDOW: subtracting
     *  its screen position shifted every mask in split-screen and
     *  floating windows. */
    fun composeBoundsRelativeTo(root: View): List<Rect> = synchronized(lock) {
        if (composeBounds.isEmpty()) return emptyList()
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
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
