package com.replayfy.android.internal.tracker

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.lang.reflect.Field
import kotlin.math.abs

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
 * Mirrors `com.the reference mobile SDK.internals.screenaction.bt` from the the reference mobile SDK
 * decompiled source.
 */
internal class TouchListenerWrapper(
    /** The original listener (if any) — preserved so app behaviour
     *  is identical with or without us installed. */
    private val original: View.OnTouchListener?,
    /** Index of this view in its parent at attach time. Helps
     *  generate stable ids when classification fails. */
    @Suppress("unused") private val positionInParent: Int,
    /** Called on ACTION_DOWN with the (view, x, y) for the default
     *  tap, or on advanced-gesture detection with the gesture +
     *  optional pinchScale. */
    private val onTap: (
        view: View,
        x: Float,
        y: Float,
        gesture: String?,
        pinchScale: Double?,
    ) -> Unit,
) : View.OnTouchListener {

    /** Lazily-built GestureDetector for long-press + fling. Only
     *  allocated when the customer enables advanced gestures (via
     *  [advancedGesturesEnabled]) — keeps the default-tap-only
     *  path zero-overhead. */
    private var gestureDetector: GestureDetector? = null
    /** Pinch detector — separate API from GestureDetector. Lazy
     *  like above. */
    private var scaleDetector: ScaleGestureDetector? = null
    /** Cached current view + last-known coords so the detector
     *  callbacks (which don't get the View) can pass them through
     *  to `onTap`. */
    private var currentView: View? = null
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (advancedGesturesEnabled) {
            // Lazy-init the detectors on first advanced-gestures
            // touch so toggling the flag mid-session takes effect
            // immediately for all subsequently-attached views.
            ensureDetectors(view.context)
            currentView = view
            lastX = event.x
            lastY = event.y
            gestureDetector?.onTouchEvent(event)
            scaleDetector?.onTouchEvent(event)
        }
        // Fire on ACTION_DOWN so we capture widget state BEFORE the
        // tap can mutate it (e.g. a button that re-labels itself in
        // its click handler still shows the pre-click label in our
        // tap event). Same rationale as the Flutter SDK's
        // _fireTapImmediately design note.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            try {
                onTap(view, event.x, event.y, null, null)
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

    private fun ensureDetectors(context: android.content.Context) {
        if (gestureDetector != null) return
        gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    val v = currentView ?: return
                    fire(v, e.x, e.y, "long_press", null)
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velX: Float,
                    velY: Float,
                ): Boolean {
                    val v = currentView ?: return false
                    // Direction by dominant axis + sign of velocity.
                    // Threshold suppresses tiny accidental flings.
                    val absX = abs(velX)
                    val absY = abs(velY)
                    val label = when {
                        absX < 200 && absY < 200 -> return false
                        absX > absY -> if (velX > 0) "swipe_right" else "swipe_left"
                        else -> if (velY > 0) "swipe_down" else "swipe_up"
                    }
                    fire(v, e2.x, e2.y, label, null)
                    return false // never consume — host app's
                    // fling-handlers must keep firing.
                }
            },
        )
        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                // One event per pinch END so a single gesture produces
                // one timeline entry (mirrors the iOS choice +
                // matches the reference mobile SDK).
                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    val v = currentView ?: return
                    fire(
                        v,
                        detector.focusX,
                        detector.focusY,
                        "pinch",
                        detector.scaleFactor.toDouble(),
                    )
                }
            },
        )
    }

    private fun fire(view: View, x: Float, y: Float, gesture: String, scale: Double?) {
        try {
            onTap(view, x, y, gesture, scale)
        } catch (t: Throwable) {
            android.util.Log.v(TAG, "$gesture capture failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "ReplaySdk"

        /** Global toggle for advanced gestures. Read on every
         *  onTouch call so toggling from Replay.enableAdvancedGestureRecognizer()
         *  takes effect on the next touch without re-attaching
         *  listeners. */
        @Volatile var advancedGesturesEnabled: Boolean = false

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
