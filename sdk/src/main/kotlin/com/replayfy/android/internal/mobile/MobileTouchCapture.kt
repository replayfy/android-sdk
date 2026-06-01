package com.replayfy.android.internal.mobile

import android.app.Activity
import android.view.MotionEvent
import android.view.Window
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Captures touches by wrapping the activity Window.Callback. On
 * touch-up it classifies tap vs swipe (start→end distance > 10 px) +
 * direction and forwards to the engine. Mirrors the iOS sendEvent
 * swizzle approach.
 */
class MobileTouchCapture(
    private val onGesture: (label: String, x: Long, y: Long, isSwipe: Boolean, direction: String) -> Unit,
) {
    private var startX = 0f
    private var startY = 0f

    /** Install on an activity's window by wrapping its callback. */
    fun attach(activity: Activity) {
        val window = activity.window
        val existing = window.callback ?: return
        if (existing is WrappedCallback) return // already wrapped
        window.callback = WrappedCallback(existing, this)
    }

    fun handle(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = ev.x; startY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - startX; val dy = ev.y - startY
                val isSwipe = hypot(dx, dy) > 10f
                val x = ev.x.coerceAtLeast(0f).toLong()
                val y = ev.y.coerceAtLeast(0f).toLong()
                onGesture("View", x, y, isSwipe, direction(dx, dy))
            }
        }
    }

    private fun direction(dx: Float, dy: Float): String = when {
        abs(dx) > abs(dy) -> if (dx > 0) "right" else "left"
        abs(dy) > abs(dx) -> if (dy > 0) "down" else "up"
        else -> "right"
    }

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
