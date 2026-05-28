package com.replayfy.android.internal.tracker

import android.view.MotionEvent
import android.view.View
import java.lang.reflect.Field

/**
 * Wraps a View's existing [View.OnTouchListener] so we can observe
 * taps without interfering with the host app's gesture handling.
 *
 * On ACTION_DOWN we capture the tap → forward to [onTap]. We always
 * delegate to the original listener afterwards and return whatever
 * it returns (or false if there isn't one). The host app sees the
 * exact same touch behaviour with or without us installed.
 *
 * Why per-View wrapping instead of `Application.dispatchTouchEvent`
 * or `Window.Callback` swizzling: Activity-level callbacks don't see
 * touches that land on Views inside dialogs, popups, or custom
 * overlays added directly to WindowManager. Per-View attachment
 * (after walking every window root via WindowRootDiscovery) catches
 * everything.
 *
 * Mirrors `com.uxcam.internals.screenaction.bt` from the UXCam
 * decompiled source.
 */
internal class TouchListenerWrapper(
    /** The original listener (if any) — preserved so app behaviour
     *  is identical with or without us installed. */
    private val original: View.OnTouchListener?,
    /** Index of this view in its parent at attach time. Helps
     *  generate stable ids when classification fails. */
    @Suppress("unused") private val positionInParent: Int,
    /** Called on ACTION_DOWN with the (view, x, y) for capture. */
    private val onTap: (view: View, x: Float, y: Float) -> Unit,
) : View.OnTouchListener {

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        // Fire on ACTION_DOWN so we capture widget state BEFORE the
        // tap can mutate it (e.g. a button that re-labels itself in
        // its click handler still shows the pre-click label in our
        // tap event). Same rationale as the Flutter SDK's
        // _fireTapImmediately design note.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            try {
                onTap(view, event.x, event.y)
            } catch (t: Throwable) {
                // Never propagate a capture failure to the host app.
                android.util.Log.v(TAG, "tap capture failed: ${t.message}")
            }
        }
        // Always delegate. Returning false (rather than the original's
        // result) when there's no original listener lets the View's
        // own touch handling proceed normally.
        return try {
            original?.onTouch(view, event) ?: false
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "wrapped onTouch threw: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "ReplaySdk"

        /** Lazily-resolved reflection of `View.mListenerInfo`. Cached
         *  because it's used on every interactive view per scan. */
        private val listenerInfoField: Field? by lazy {
            try {
                val f = View::class.java.getDeclaredField("mListenerInfo")
                f.isAccessible = true
                f
            } catch (_: Throwable) { null }
        }

        /**
         * Read the existing OnTouchListener off a View without
         * triggering `setOnTouchListener` (which would otherwise
         * blow it away). View.mListenerInfo is private; we reflect.
         *
         * Returns null when the view has no listener attached OR when
         * reflection failed (OEM-modified class, future API change).
         */
        fun readExistingListener(view: View): View.OnTouchListener? {
            val infoField = listenerInfoField ?: return null
            return try {
                val info = infoField.get(view) ?: return null
                val touchListenerField = info.javaClass
                    .getDeclaredField("mOnTouchListener")
                touchListenerField.isAccessible = true
                touchListenerField.get(info) as? View.OnTouchListener
            } catch (_: Throwable) { null }
        }
    }
}
