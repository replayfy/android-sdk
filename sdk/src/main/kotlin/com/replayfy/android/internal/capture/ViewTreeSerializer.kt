package com.replayfy.android.internal.capture

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.replayfy.android.internal.NativeNodeBounds
import com.replayfy.android.internal.NativeViewNode
import com.replayfy.android.internal.tracker.ValueExtractor
import com.replayfy.android.internal.tracker.WidgetClassifier

/**
 * Walks an Android View tree and produces the platform-neutral
 * [NativeViewNode] hierarchy that the dashboard player renders.
 *
 * Lean output — every byte multiplied by tree size. Optimizations:
 *   • Skip hidden / zero-alpha / zero-size nodes entirely
 *   • Omit `opacity` when 1.0, `backgroundColor` when transparent,
 *     `children` when empty (handled by data-class encoder)
 *   • Stop recursing into nodes outside the parent's bounds (off-
 *     screen content in a scroll viewport)
 *
 * Image bytes are NOT captured here. Image nodes carry an
 * `imageRef` only when bitmap capture is wired (follow-up commit);
 * for now they ship as classified nodes with `text` or `ariaLabel`
 * filled from contentDescription or resource name, no pixel data.
 *
 * Mirrors the algorithm in
 * replay-web-sdk/docs/native-snapshot-format.md and the per-platform
 * pseudocode from uxcam-flutter's widget_extractor adapted to
 * Android's View hierarchy.
 */
internal object ViewTreeSerializer {

    /** Serialize the root view and return the tree. Returns null
     *  when the view isn't suitable (hidden, no measured size). */
    fun serialize(root: View): NativeViewNode? = walk(root, "0", root)

    /**
     * Depth-first walk. `idPath` builds a sibling-index path like
     * "0/2/0/1" so taps can later resolve to the exact node by id.
     * `screenRoot` is the root window view — used for visibility
     * filtering (skip nodes outside the screen).
     */
    private fun walk(view: View, idPath: String, screenRoot: View): NativeViewNode? {
        if (!view.isShown || view.alpha < 0.01f) return null
        if (view.width <= 0 || view.height <= 0) return null

        // Screen-relative bounds. Computed via getLocationOnScreen
        // because parent transforms (scrolling, scaling) skew the
        // simple left/top fields.
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val absoluteBounds = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        // Skip nodes entirely outside the screen — common when
        // RecyclerView caches off-screen items.
        if (!absoluteBounds.intersects(0, 0, screenRoot.width, screenRoot.height)) {
            return null
        }

        // Bounds we ship are screen-relative — same coordinate system
        // as TapEventData.point/bounds so the player can overlay
        // taps on snapshot nodes without re-mapping.
        val bounds = NativeNodeBounds(
            x = loc[0], y = loc[1], w = view.width, h = view.height,
        )

        val type = WidgetClassifier.classify(view)

        // Privacy check: two paths.
        //   (a) View-ancestor walk: catches Replay.addPrivacyView(view).
        //   (b) Compose bounds intersection: catches
        //       Modifier.replayOcclude — the marker isn't in the
        //       view tree, so we check whether the view's window-
        //       relative frame overlaps any registered Compose Rect.
        //       Compose registers window-coords; `loc` here is
        //       SCREEN coords, so we compute window-coords separately.
        val winLoc = IntArray(2).also { view.getLocationInWindow(it) }
        val winRect = android.graphics.Rect(
            winLoc[0], winLoc[1],
            winLoc[0] + view.width, winLoc[1] + view.height,
        )
        val occluded = com.replayfy.android.internal.privacy.PrivacyRegistry
            .isSensitive(view) ||
            com.replayfy.android.internal.privacy.PrivacyRegistry
                .isSensitiveByBoundsIntersect(winRect)

        // Text content: same extractor the tap tracker uses. Field
        // contents NEVER leak — only hint / placeholder. Occluded
        // views ship without text — player paints a noise pattern
        // over them anyway.
        val text = if (occluded) null
        else if (type == WidgetClassifier.UiType.TEXT ||
            type == WidgetClassifier.UiType.BUTTON ||
            type == WidgetClassifier.UiType.FIELD
        ) {
            ValueExtractor.extract(view, type).takeIf { it.isNotEmpty() }
        } else null

        // TextView color extraction — only emit when readable text
        // exists. Saves bytes per leaf when the view is non-text.
        val bgColor = extractBackgroundColor(view)
        val opacity = if (view.alpha < 1f) view.alpha.toDouble() else null

        val children = if (view is ViewGroup && view.childCount > 0) {
            val out = ArrayList<NativeViewNode>(view.childCount.coerceAtMost(32))
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                val node = walk(child, "$idPath/$i", screenRoot) ?: continue
                out.add(node)
            }
            out.takeIf { it.isNotEmpty() }
        } else null

        val ariaLabel = if (occluded) null
        else view.contentDescription?.toString()
            ?.takeIf { it.isNotEmpty() && it != text }

        return NativeViewNode(
            id = idPath,
            type = type.wireName,
            className = view.javaClass.simpleName,
            bounds = bounds,
            text = text,
            // Will be populated by the asset-capture pipeline in a
            // follow-up commit; image-bearing nodes ship today
            // without bytes.
            imageRef = null,
            backgroundColor = bgColor,
            opacity = opacity,
            // Ship `true` only when this view is itself sensitive
            // (the dashboard inherits occluded down the subtree
            // automatically via a parent's `occluded:true` mark).
            // Omitted when false to save bytes.
            occluded = if (occluded) true else null,
            ariaLabel = ariaLabel,
            children = children,
        )
    }

    /**
     * Extract a solid backgroundColor if present. Drawables that
     * aren't ColorDrawable (gradient, image, shape, ripple) return
     * null — the player renders those as transparent and falls back
     * to the imageRef pipeline.
     *
     * For TextView, we ALSO consider the text-color contribution
     * via the foreground tint — but only as a secondary signal
     * (skipped today to keep size budget tight).
     */
    private fun extractBackgroundColor(view: View): String? {
        val bg = view.background ?: return null
        if (bg is ColorDrawable) {
            val c = bg.color
            if (Color.alpha(c) == 0) return null
            return colorToHex(c)
        }
        // TextView.getCurrentTextColor() is the readable text color,
        // useful for label nodes that have no explicit background.
        // Skipped here to stay lean — re-enable if dashboard playback
        // looks washed-out.
        return null
    }

    private fun colorToHex(c: Int): String {
        // ARGB hex — alpha first to match CSS rgba() ordering used by
        // the player.
        val a = Color.alpha(c)
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return "#%02x%02x%02x%02x".format(a, r, g, b)
    }

    /** Helper for tests / debug logging — count nodes in a tree. */
    @Suppress("unused")
    fun countNodes(node: NativeViewNode): Int {
        val children = node.children ?: return 1
        var n = 1
        for (child in children) n += countNodes(child)
        return n
    }
}
