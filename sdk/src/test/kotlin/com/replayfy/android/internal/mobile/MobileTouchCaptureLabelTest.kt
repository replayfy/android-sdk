package com.replayfy.android.internal.mobile

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.replayfy.android.ReplayMaskStyle
import com.replayfy.android.internal.privacy.PrivacyRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A tap on a view a privacy rule masks in the frames is never named with
 * what that view shows: no text, no content description. Without a mask the
 * visible text names it, as before, and so it does on a FLAG_SECURE window,
 * as in the reference tracker.
 */
@RunWith(RobolectricTestRunner::class)
class MobileTouchCaptureLabelTest {
    private lateinit var activity: Activity
    private lateinit var card: TextView
    private lateinit var row: FrameLayout
    private val labels = ArrayList<String>()
    private val touch = MobileTouchCapture { label, _, _, kind, _ -> if (kind == "tap") labels.add(label) }

    @Before
    fun setUp() {
        resetRegistry()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        row = FrameLayout(activity)
        card = TextView(activity).apply { text = "4111 1111 1111 1111"; contentDescription = "card 4111" }
        row.addView(card, FrameLayout.LayoutParams(300, 60).also { it.leftMargin = 10; it.topMargin = 20 })
        activity.setContentView(row)
        shadowOf(Looper.getMainLooper()).idle()
        touch.attach(activity)
    }

    @After
    fun tearDown() = resetRegistry()

    private fun resetRegistry() {
        PrivacyRegistry.clear()
        PrivacyRegistry.occludeAllTextFields = false
        PrivacyRegistry.remoteOccludeAllTextFields = false
        PrivacyRegistry.occludeAllTextViews = false
        PrivacyRegistry.occludeAllScreen = false
        PrivacyRegistry.maskStyle = ReplayMaskStyle.OVERLAY
    }

    /** The card's centre, in the decor's coordinates (the events a
     *  Window.Callback sees: window coordinates with any adjustPan scroll
     *  added back). */
    private fun centre(): Pair<Float, Float> {
        val loc = PrivacyRegistry.locationInDecor(card)
        return (loc[0] + card.width / 2f) to (loc[1] + card.height / 2f)
    }

    /** A tap on the card's centre as drawn, delivered as the window
     *  delivers it: ViewRootImpl adds the window's [pan] back to x/y, the
     *  raw (screen) position stays where the finger is. */
    private fun tapCard(pan: Int = 0): String {
        labels.clear()
        val loc = IntArray(2).also { card.getLocationInWindow(it) }
        val x = loc[0] + card.width / 2f
        val y = loc[1] + card.height / 2f
        val down = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50)) {
            touch.handle(MotionEvent.obtain(down, at, action, x, y, 0).apply { offsetLocation(0f, pan.toFloat()) })
        }
        return labels.single()
    }

    @Test
    fun anUnmaskedViewIsNamedByItsText() {
        assertEquals("4111 1111 1111 1111", tapCard())
    }

    @Test
    fun aViewInsideAPrivacyViewIsNamedByItsClassOnly() {
        PrivacyRegistry.add(row)
        assertEquals("TextView", tapCard())
    }

    @Test
    fun occludeAllTextViewsKeepsTheTextOutOfTheLabel() {
        PrivacyRegistry.occludeAllTextViews = true
        assertEquals("TextView", tapCard())
    }

    @Test
    fun aComposeMaskUnderTheTouchKeepsTheTextOutOfTheLabel() {
        val (x, y) = centre()
        val token = Any()
        PrivacyRegistry.addComposeBounds(token, Rect(x.toInt() - 5, y.toInt() - 5, x.toInt() + 5, y.toInt() + 5))
        try {
            assertEquals("TextView", tapCard())
        } finally {
            PrivacyRegistry.removeComposeBounds(token)
        }
    }

    @Test
    fun aComposeMaskRegisteredWhilePannedStillCoversTheTouch() {
        // The keyboard pans the window: the composable's window bounds move
        // up by the pan, while the touch arrives with the pan added back.
        val (x, y) = centre()
        val token = Any()
        panWindow(activity, 40)
        try {
            assertEquals("found under the pan", "4111 1111 1111 1111", tapCard(pan = 40))
            com.replayfy.android.internal.bridge.ComposeBridge.registerOccludedBounds(
                token, Rect(x.toInt() - 5, y.toInt() - 45, x.toInt() + 5, y.toInt() - 35), card,
            )
            assertEquals("TextView", tapCard(pan = 40))
        } finally {
            panWindow(activity, 0)
            PrivacyRegistry.removeComposeBounds(token)
        }
    }

    @Test
    fun aSecureWindowKeepsItsTapLabelsAsTheReferenceDoes() {
        // Its frames are black; its taps are still named, as the reference
        // tracker names them. A privacy rule on the view still wins.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals("4111 1111 1111 1111", tapCard())
        PrivacyRegistry.add(row)
        assertEquals("TextView", tapCard())
    }

    @Test
    fun aMaskedViewWithAResourceIdIsNamedByIt() {
        card.id = android.R.id.text1
        PrivacyRegistry.add(row)
        assertEquals("text1", tapCard())
    }
}
