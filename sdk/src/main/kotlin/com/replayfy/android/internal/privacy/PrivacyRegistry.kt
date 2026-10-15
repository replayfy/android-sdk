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
     * Whether a privacy rule masks [view] in recorded frames: the view itself
     * OR any ancestor (via parent chain) is in the registry or an occluded
     * class, a bulk flag covers it, or it is an always-sensitive field
     * ([SensitiveInputRules]). Catches the common case where the customer
     * marks a ViewGroup (e.g. the LinearLayout for the payment method row)
     * and expects every descendant to inherit the mark.
     *
     * Typed values and tap labels consult this, so a view a rule masks never
     * ships its text by another route. A FLAG_SECURE window
     * ([isSecureWindow]) is not one of these rules: its frames are black, but
     * its taps keep their labels, as the reference tracker's do. Typed values
     * check it on their own.
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
        walkBulk(root, walk, null)
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

    /** Below a view whose legacy Animation runs: matches there are masked by
     *  [box], where the animated drawing stays (null: it shows nothing). */
    private class Moving(val box: Rect?)

    private fun walkBulk(view: View, walk: BulkWalk, moving: Moving?) {
        // The walk starts at a shown root, so a visible view here is shown.
        // No size prune: a zero-size parent that doesn't clip can still draw
        // its children.
        if (view.visibility != View.VISIBLE) return
        // The outermost animated view decides where its subtree can draw.
        val under = moving ?: if (isAnimating(view)) Moving(animatedBox(walk.root, view)) else null
        // Collected, and still walked into: a native view placed inside a
        // WebView is masked like any other.
        if (view is WebView) walk.webViews?.add(view)
        // Password / card / one-time-code inputs are ALWAYS masked,
        // regardless of the occludeAll* flags.
        val auto = view is EditText && SensitiveInputRules.isSensitiveEditText(view, false)
        val matches = auto ||
            (walk.wantFields && view is EditText) ||
            // occludeAllTextView masks everything that shows text, input
            // fields included (EditText extends TextView), as isSensitive
            // does. One test per view, so a field both flags cover still
            // gives one rect.
            (walk.wantViews && view is TextView) ||
            // Per-class occlusion (applyOcclusion). Wrapped in the
            // cached flag so empty-set apps don't pay the
            // instanceof loop cost on every view.
            (walk.hasClassFilter && matchesOccludedClass(view))
        if (matches) {
            val style = if (auto) ReplayMaskStyle.OVERLAY else walk.style
            if (under == null) {
                visibleRectIn(walk.root, view)?.let { walk.out.add(MaskRect(it, style)) }
            } else {
                under.box?.let { box ->
                    val mask = MaskRect(Rect(box), style)
                    if (mask !in walk.out) walk.out.add(mask)
                }
            }
            // Don't recurse — the rect covers the subtree, and text
            // widgets don't contain other text widgets meaningfully.
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                walkBulk(child, walk, under)
            }
        }
    }

    /**
     * Where [v] shows on screen, in [root]'s coordinates, or null when it
     * shows nothing. Same geometry the reference tracker uses: visible, same
     * window, clipped global visible rect. The global visible rect is in the
     * root view's (the decor's) coordinates and follows scrolling, clipping
     * by ancestors and transforms (a view scaled up is masked at its drawn
     * size, not its layout size). It leaves out the window's adjustPan
     * scroll: see [frameOffset].
     */
    private fun visibleRectIn(root: View, v: View): Rect? {
        if (v.visibility != View.VISIBLE || !v.isShown) return null
        if (v.rootView !== root.rootView) return null
        val r = Rect()
        if (!v.getGlobalVisibleRect(r) || r.isEmpty) return null
        if (root !== root.rootView) {
            val o = locationInDecor(root)
            r.offset(-o[0], -o[1])
        }
        return r
    }

    /** Whether a legacy view Animation (View.startAnimation, a Fragment's
     *  anim-resource transition) moves [v]'s drawing: it runs, or it ended
     *  and keeps its last frame (fillAfter). */
    private fun isAnimating(v: View): Boolean {
        val a = v.animation ?: return false
        return !a.hasEnded() || a.fillAfter
    }

    /**
     * Where [animating], whose legacy Animation runs, can draw, in [root]'s
     * coordinates; null when that is nowhere on screen. Such an animation
     * moves the pixels but not the view's geometry, so the visible rect of a
     * view inside it says nothing about where it shows. Its drawing stays
     * inside the nearest ancestor that its own parent clips to its bounds
     * (clipChildren, on by default), so that ancestor's visible rect covers
     * it; with no such ancestor, the whole [root].
     */
    private fun animatedBox(root: View, animating: View): Rect? {
        var box: View = animating
        while (box !== root) {
            box = box.parent as? View ?: break
            val holder = box.parent
            if (holder is ViewGroup && holder.clipChildren) break
        }
        return visibleRectIn(root, if (box === animating) root else box)
    }

    /** The outermost view from [v] up to [root] whose legacy Animation moves
     *  its drawing (see [isAnimating]), or null. */
    private fun animatingAncestor(root: View, v: View): View? {
        var found: View? = null
        var cur: View? = v
        while (cur != null) {
            if (isAnimating(cur)) found = cur
            if (cur === root) break
            cur = cur.parent as? View
        }
        return found
    }

    /**
     * Enumerate currently-tracked views and return their bounds
     * relative to [root]. Used by BitmapCapture to know where to
     * paint the overlay. Skips views that aren't shown / show nothing /
     * aren't descendants of [root]. A view moved by a legacy Animation is
     * covered where that animation can draw it (see [animatedBox]).
     */
    fun sensitiveBounds(root: View): List<Rect> = synchronized(lock) {
        val out = ArrayList<Rect>()
        // Snapshot keys to avoid mutation during iteration (the
        // WeakHashMap can drop entries between iterator advance + read).
        val snapshot = ArrayList(table.keys)
        for (v in snapshot) {
            if (v == null) continue
            if (!isAncestorOf(root, v)) continue
            if (v.visibility != View.VISIBLE || !v.isShown) continue
            val animating = animatingAncestor(root, v)
            if (animating == null) {
                visibleRectIn(root, v)?.let { out.add(it) }
            } else {
                animatedBox(root, animating)?.let { if (it !in out) out.add(it) }
            }
        }
        return out
    }

    // -----------------------------------------------------------------
    //  Frame coordinates
    // -----------------------------------------------------------------
    //
    // The rects above are in the root view's (the decor's) own coordinates:
    // the window's content as laid out. A recorded frame is the window's
    // surface, which PixelCopy copies. The two differ while the window is
    // panned for the keyboard (adjustPan): the content is drawn moved up by
    // the pan, which the visible rects leave out, so a mask placed with them
    // would land below the field it covers.

    /**
     * Where [decor]'s content shows in its window's surface, and so in a
     * recorded frame: (0, 0), except while the window is panned for the
     * keyboard (adjustPan), when it is (0, -pan). It is the decor's location
     * in its window, which counts the pan.
     */
    fun frameOffset(decor: View): IntArray = IntArray(2).also { decor.getLocationInWindow(it) }

    /** [rects], in [decor]'s coordinates, moved to where a recorded frame
     *  shows them (see [frameOffset]). */
    fun inFrame(rects: List<MaskRect>, decor: View): List<MaskRect> {
        val o = frameOffset(decor)
        if (o[0] == 0 && o[1] == 0) return rects
        return rects.map { MaskRect(Rect(it.rect).apply { offset(o[0], o[1]) }, it.style) }
    }

    /** [v]'s top-left in its root view's (the decor's) coordinates: its
     *  location in the window less the decor's, which drops the window's
     *  adjustPan scroll. The space of the visible rects above, of the
     *  Compose masks, and of the touches a Window.Callback sees. */
    fun locationInDecor(v: View): IntArray {
        val out = IntArray(2).also { v.getLocationInWindow(it) }
        val decor = IntArray(2).also { v.rootView.getLocationInWindow(it) }
        out[0] -= decor[0]
        out[1] -= decor[1]
        return out
    }

    /** Where [v] shows, in its root view's (the decor's) coordinates: its
     *  clipped visible rect, or its laid-out bounds when it shows nothing. */
    fun decorRectOf(v: View): Rect {
        val r = Rect()
        if (v.getGlobalVisibleRect(r) && !r.isEmpty) return r
        val o = locationInDecor(v)
        return Rect(o[0], o[1], o[0] + v.width, o[1] + v.height)
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
    //  composable's bounds in the decor's coordinates (its window
    //  bounds less the window's adjustPan scroll, see
    //  ComposeBridge.registerOccludedBounds) keyed by an opaque token
    //  and unregisters on dispose. TapTracker + ViewTreeSerializer
    //  + BitmapCapture all consult [isSensitiveAtPoint] / [composeBounds]
    //  in addition to the View-based checks.
    // -----------------------------------------------------------------

    /** Decor-relative rect for one Compose composable marked via
     *  Modifier.replayOcclude. Keyed by an opaque token (typically
     *  the modifier's remembered instance) so the same composable
     *  can update its rect on re-layout without growing the table. */
    private val composeBounds: MutableMap<Any, Rect> =
        Collections.synchronizedMap(HashMap())

    /** Register or update Compose-composable bounds, in the decor's
     *  coordinates. Called from Modifier.replayOcclude's
     *  onGloballyPositioned callback. */
    fun addComposeBounds(token: Any, rectInDecor: Rect) {
        synchronized(lock) { composeBounds[token] = rectInDecor }
    }

    /** Unregister. Called from the modifier's DisposableEffect.onDispose. */
    fun removeComposeBounds(token: Any) {
        synchronized(lock) { composeBounds.remove(token) }
    }

    /** Whether the given point, in the decor's coordinates (a touch's x/y
     *  as a Window.Callback sees it), falls inside any currently-registered
     *  Compose marker. Called by the tap trackers at tap time. */
    fun isSensitiveAtPoint(xInDecor: Int, yInDecor: Int): Boolean =
        synchronized(lock) {
            for (rect in composeBounds.values) {
                if (rect.contains(xInDecor, yInDecor)) return true
            }
            return false
        }

    /** Whether any registered Compose marker's bounds intersect the
     *  given rect, in the decor's coordinates. Used by
     *  ViewTreeSerializer at snapshot time to mark nodes whose painted
     *  region overlaps a Compose-occluded composable, and by typed values
     *  for a field under such a region. */
    fun isSensitiveByBoundsIntersect(rectInDecor: Rect): Boolean =
        synchronized(lock) {
            for (rect in composeBounds.values) {
                if (Rect.intersects(rect, rectInDecor)) return true
            }
            return false
        }

    /** Snapshot of all currently-registered Compose bounds in
     *  root-relative coords. Used by the screenshotters to mask
     *  Compose-occluded regions. The bounds are decor-relative, so they
     *  are already [root]'s when it is the decor; another root subtracts
     *  its own place in the decor. (Subtracting the root's screen
     *  position shifted every mask in split-screen and floating windows.) */
    fun composeBoundsRelativeTo(root: View): List<Rect> = synchronized(lock) {
        if (composeBounds.isEmpty()) return emptyList()
        val rootLoc = locationInDecor(root)
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
