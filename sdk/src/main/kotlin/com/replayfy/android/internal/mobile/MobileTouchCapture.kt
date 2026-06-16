package com.replayfy.android.internal.mobile

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Captures touches by wrapping the activity Window.Callback. On
 * touch-up it classifies tap vs swipe (start→end distance > 10 px) +
 * direction and forwards to the engine. Mirrors the iOS sendEvent
 * swizzle approach.
 */
class MobileTouchCapture(
    /** `kind` is "tap" / "long_press" / "swipe". */
    private val onGesture: (label: String, x: Long, y: Long, kind: String, direction: String) -> Unit,
) {
    private var startX = 0f
    private var startY = 0f
    private var root: WeakReference<View>? = null

    private companion object {
        /** Press held at least this long (with little movement) → long_press. */
        const val LONG_PRESS_MS = 500L
    }

    /** Install on an activity's window by wrapping its callback. */
    fun attach(activity: Activity) {
        val window = activity.window
        root = WeakReference(window.decorView)
        val existing = window.callback ?: return
        if (existing is WrappedCallback) return // already wrapped
        window.callback = WrappedCallback(existing, this)
    }

    fun handle(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = ev.x; startY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX; val dy = ev.y - startY
                val held = ev.eventTime - ev.downTime
                val kind = when {
                    hypot(dx, dy) > 10f -> "swipe"
                    held >= LONG_PRESS_MS -> "long_press"
                    else -> "tap"
                }
                val x = ev.x.coerceAtLeast(0f).toLong()
                val y = ev.y.coerceAtLeast(0f).toLong()
                // Hit-test the view under the touch (screen coords) and derive a
                // label, instead of the hard-coded "View".
                val hit = root?.get()?.let { findViewAtPosition(it, ev.rawX, ev.rawY) }
                // Drop taps on framework/system hosts (FlutterSurfaceView, …) —
                // extractElementLabel returns null for those.
                val label = extractElementLabel(hit) ?: return
                onGesture(label, x, y, kind, direction(dx, dy))
            }
        }
    }

    private fun direction(dx: Float, dy: Float): String = when {
        abs(dx) > abs(dy) -> if (dx > 0) "right" else "left"
        abs(dy) > abs(dx) -> if (dy > 0) "down" else "up"
        else -> "right"
    }

    /**
     * Recursive top-to-bottom hit-test for the deepest visible view under a
     * screen-space point (matches the reference tracker's findViewAtPosition).
     */
    private fun findViewAtPosition(view: View, x: Float, y: Float): View? {
        if (!view.isShown) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val inBounds =
            x >= loc[0] && x <= loc[0] + view.width && y >= loc[1] && y <= loc[1] + view.height
        if (!inBounds) return null
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val found = findViewAtPosition(view.getChildAt(i), x, y)
                if (found != null) return found
            }
        }
        return view
    }

    /**
     * Single best element label, most-specific first: resource-id → visible
     * text → contentDescription → class name. `contentDescription` is where
     * React Native's `accessibilityLabel` lands on Android. EditText uses its
     * hint, never the typed value (privacy).
     */
    private fun extractElementLabel(view: View?): String? {
        if (view == null) return "View"
        try {
            if (view.id != View.NO_ID) {
                val name = view.resources.getResourceEntryName(view.id)
                if (name.isNotBlank()) return name
            }
        } catch (_: Throwable) { /* unknown resource id */ }
        try {
            val text = when (view) {
                is EditText -> view.hint?.toString()
                is TextView -> view.text?.toString()
                is Button -> view.text?.toString()
                else -> null
            }
            if (!text.isNullOrBlank()) return text.trim().replace("\n", " ").take(50)
        } catch (_: Throwable) { /* ignore */ }
        try {
            val desc = view.contentDescription?.toString()
            if (!desc.isNullOrBlank()) return desc.trim().replace("\n", " ").take(50)
        } catch (_: Throwable) { /* ignore */ }
        // Class-name fallback — but drop framework/system hosts whose taps are
        // noise, not app interactions (returns null → the gesture is skipped).
        val cls = try { view.javaClass.simpleName } catch (_: Throwable) { "" }
        if (cls.isBlank()) return "View"
        return if (isSystemClassName(cls)) null else cls
    }

    /**
     * Framework/system view classes whose taps are noise rather than real app
     * interactions: the Flutter engine surfaces (`FlutterView`,
     * `FlutterSurfaceView`, `FlutterImageView`) and React Native internals
     * (`RNS…` / `RCT…`). Mirrors the ingest-side denylist so signal stays
     * consistent across SDKs.
     */
    private fun isSystemClassName(name: String): Boolean =
        name.startsWith("Flutter") || name.startsWith("RNS") || name.startsWith("RCT")

    /**
     * Kotlin interface delegation (`by inner`) auto-forwards every
     * Window.Callback method to the original; we override only
     * dispatchTouchEvent to sniff touches on the way through.
     */
    private class WrappedCallback(
        private val inner: Window.Callback,
        private val capture: MobileTouchCapture,
    ) : Window.Callback by inner {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            try { capture.handle(event) } catch (_: Throwable) {}
            return inner.dispatchTouchEvent(event)
        }
    }
}
