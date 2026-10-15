package com.replayfy.android.internal.mobile

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.replayfy.android.internal.privacy.PrivacyRegistry
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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

    // ── Advanced multi-touch (pinch / rotate) ────────────────────────
    // Correlate exactly two pointers: capture their initial separation + angle
    // when the second finger lands (ACTION_POINTER_DOWN), track the latest on
    // move, and emit ONCE when a finger lifts. `gestureConsumed` suppresses the
    // single-touch tap/swipe classifier so a pinch isn't double-counted.
    private var pinchTracking = false
    private var gestureConsumed = false
    private var pinchInitialDist = 0f
    private var pinchInitialAngle = 0f
    private var pinchLastDist = 0f
    private var pinchLastAngle = 0f
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f
    private var pinchRawCenterX = 0f
    private var pinchRawCenterY = 0f

    companion object {
        /** Press held at least this long (with little movement) → long_press. */
        private const val LONG_PRESS_MS = 500L

        /**
         * Capture pinch + rotate gestures. Off by default — correlating the two
         * pointers costs nothing until a host opts in via
         * `Replay.enableAdvancedGestureRecognizer(true)`. tap/swipe/long_press
         * are always on regardless.
         */
        @Volatile
        @JvmStatic
        var advancedGesturesEnabled = false
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
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x; startY = ev.y
                pinchTracking = false
                gestureConsumed = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (advancedGesturesEnabled && ev.pointerCount == 2) {
                    pinchInitialDist = twoFingerDistance(ev)
                    pinchInitialAngle = twoFingerAngle(ev)
                    pinchLastDist = pinchInitialDist
                    pinchLastAngle = pinchInitialAngle
                    updatePinchCenter(ev)
                    pinchTracking = true
                    gestureConsumed = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinchTracking && ev.pointerCount >= 2) {
                    pinchLastDist = twoFingerDistance(ev)
                    pinchLastAngle = twoFingerAngle(ev)
                    updatePinchCenter(ev)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinchTracking) { emitPinchRotate(); pinchTracking = false }
            }
            MotionEvent.ACTION_UP -> {
                // A two-finger gesture already emitted — don't also classify the
                // last finger's lift as a tap/swipe.
                if (gestureConsumed) { gestureConsumed = false; return }
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
                // extractElementLabel returns null for those. ev.x/ev.y are
                // in the decor's coordinates (a Window.Callback sees the
                // window's events, with any adjustPan scroll already added
                // back), where Compose masks are kept.
                val label = extractElementLabel(hit, ev.x, ev.y) ?: return
                onGesture(label, x, y, kind, direction(dx, dy))
            }
            MotionEvent.ACTION_CANCEL -> { pinchTracking = false; gestureConsumed = false }
        }
    }

    private fun twoFingerDistance(ev: MotionEvent): Float =
        hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0))

    private fun twoFingerAngle(ev: MotionEvent): Float =
        atan2(ev.getY(1) - ev.getY(0), ev.getX(1) - ev.getX(0))

    /** Track the pinch center in both view-local and raw (screen) coords — the
     *  latter for hit-testing the labelled view. rawX/rawY are only exposed for
     *  pointer 0, so derive the offset from it. */
    private fun updatePinchCenter(ev: MotionEvent) {
        pinchCenterX = (ev.getX(0) + ev.getX(1)) / 2f
        pinchCenterY = (ev.getY(0) + ev.getY(1)) / 2f
        val offX = ev.rawX - ev.getX(0)
        val offY = ev.rawY - ev.getY(0)
        pinchRawCenterX = pinchCenterX + offX
        pinchRawCenterY = pinchCenterY + offY
    }

    private fun emitPinchRotate() {
        if (pinchInitialDist <= 1f) return
        val scale = pinchLastDist / pinchInitialDist
        var angleDelta = pinchLastAngle - pinchInitialAngle
        while (angleDelta > PI) angleDelta -= (2 * PI).toFloat()   // normalize to [-π, π]
        while (angleDelta < -PI) angleDelta += (2 * PI).toFloat()

        val hit = root?.get()?.let { findViewAtPosition(it, pinchRawCenterX, pinchRawCenterY) }
        val label = extractElementLabel(hit, pinchCenterX, pinchCenterY) ?: "view"
        val x = pinchCenterX.coerceAtLeast(0f).toLong()
        val y = pinchCenterY.coerceAtLeast(0f).toLong()
        // Pinch when separation changed ≥15%; rotate when the line between the
        // fingers turned ≥15°. Emit whichever crossed its threshold (both if the
        // user did both). Sub-threshold two-finger motion (scroll / accidental
        // touch) is dropped.
        if (abs(scale - 1f) >= 0.15f) {
            onGesture(label, x, y, "pinch", if (scale >= 1f) "out" else "in")
        }
        if (abs(angleDelta) >= (PI / 12).toFloat()) {
            onGesture(label, x, y, "rotate", if (angleDelta >= 0) "cw" else "ccw")
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
     *
     * A view a privacy rule masks in the frames (see
     * [PrivacyRegistry.isSensitive], or a Compose `replayOcclude` region under
     * the touch at decor coordinates [xInDecor], [yInDecor]) never names a tap
     * with what it shows: no text, no contentDescription, only the
     * resource-id or class name. The reference tracker never names a gesture
     * from an occluded view's text either. A FLAG_SECURE window records black
     * frames but keeps its tap labels, as the reference tracker's do.
     */
    private fun extractElementLabel(view: View?, xInDecor: Float, yInDecor: Float): String? {
        if (view == null) return "View"
        try {
            if (view.id != View.NO_ID) {
                val name = view.resources.getResourceEntryName(view.id)
                if (name.isNotBlank()) return name
            }
        } catch (_: Throwable) { /* unknown resource id */ }
        val masked = try {
            PrivacyRegistry.isSensitive(view) ||
                PrivacyRegistry.isSensitiveAtPoint(xInDecor.toInt(), yInDecor.toInt())
        } catch (_: Throwable) {
            true // can't tell: never risk the text
        }
        if (!masked) {
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
        }
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
