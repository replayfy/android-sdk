package com.replayfy.android.internal.mobile

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import com.replayfy.android.ReplayMaskStyle
import com.replayfy.android.internal.privacy.PrivacyRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A field whose pixels are masked in the frames never ships its typed text:
 * an observed field reports "***" whenever any masking rule covers it.
 */
@RunWith(RobolectricTestRunner::class)
class MobileInputCaptureTest {
    private class Typed(val value: String, val masked: Boolean, val label: String)

    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private val reported = ArrayList<Typed>()
    /** The masks a host SDK added to the last frame, in frame coordinates. */
    private var hostMasks: List<Rect> = emptyList()
    private val capture = MobileInputCapture(
        onInput = { value, masked, label -> reported.add(Typed(value, masked, label)) },
        hostMasks = { hostMasks },
    )

    /** An occluded class around the field, not the field's own class. */
    private class CardRow(context: android.content.Context) : FrameLayout(context)

    @Before
    fun setUp() {
        resetRegistry()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = FrameLayout(activity)
        activity.setContentView(root)
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
        PrivacyRegistry.removeOcclusion(EditText::class.java)
        PrivacyRegistry.removeOcclusion(CardRow::class.java)
    }

    private fun field(parent: android.view.ViewGroup = root, setup: EditText.() -> Unit = {}): EditText =
        EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Field"
            setup()
            setText("4111 1111 1111 1111")
            parent.addView(this)
            capture.observe(this)
        }

    /** Editing ends: the field loses focus. */
    private fun leave(field: EditText): Typed {
        reported.clear()
        field.onFocusChangeListener.onFocusChange(field, false)
        return reported.single()
    }

    private fun assertMasked(why: String, field: EditText) {
        val typed = leave(field)
        assertEquals(why, "***", typed.value)
        assertTrue(why, typed.masked)
        assertEquals("the hint still labels it", "Field", typed.label)
    }

    @Test
    fun everyFieldMaskedInTheFramesSendsNoText() {
        assertMasked("password with no suggestions", field { inputType = 0x80081 })
        assertMasked("visible password", field {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        })
        assertMasked("password transformation only", field {
            transformationMethod = PasswordTransformationMethod.getInstance()
        })
        assertMasked("card hint", field { setAutofillHints("creditCardNumber") })
    }

    @Test
    fun theAppsSwitchTheDashboardsAndMarkedParentsMaskTheValue() {
        val plain = field()
        PrivacyRegistry.occludeAllTextFields = true
        assertMasked("occludeAllTextFields", plain)
        PrivacyRegistry.occludeAllTextFields = false

        PrivacyRegistry.remoteOccludeAllTextFields = true
        assertMasked("dashboard force", plain)
        PrivacyRegistry.remoteOccludeAllTextFields = false

        PrivacyRegistry.add(root)
        assertMasked("inside a privacy view", plain)
        PrivacyRegistry.remove(root)

        PrivacyRegistry.applyOcclusion(EditText::class.java)
        assertMasked("occluded class", plain)
        PrivacyRegistry.removeOcclusion(EditText::class.java)

        val row = CardRow(activity).also { root.addView(it) }
        val inRow = field(row)
        PrivacyRegistry.applyOcclusion(CardRow::class.java)
        assertMasked("inside an occluded class, whose whole subtree the frames mask", inRow)
    }

    @Test
    fun aFieldOnASecureWindowSendsNoText() {
        val plain = field()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        assertMasked("FLAG_SECURE window, recorded black", plain)
    }

    @Test
    fun aFieldUnderAComposeMaskSendsNoText() {
        // A classic field hosted in Compose (AndroidView) inside a
        // replayOcclude region: the frames mask the region, so the value too.
        val plain = field()
        shadowOf(Looper.getMainLooper()).idle()
        val at = PrivacyRegistry.decorRectOf(plain)
        val token = Any()
        try {
            PrivacyRegistry.addComposeBounds(token, Rect(at.left, at.top, at.left + 10, at.top + 10))
            assertMasked("under a Compose replayOcclude region", plain)
            PrivacyRegistry.addComposeBounds(token, Rect(at.right + 50, at.bottom + 50, at.right + 60, at.bottom + 60))
            assertEquals("a region elsewhere leaves it", false, leave(plain).masked)
        } finally {
            PrivacyRegistry.removeComposeBounds(token)
        }
    }

    @Test
    fun aFieldUnderAHostMaskSendsNoText() {
        val plain = field()
        shadowOf(Looper.getMainLooper()).idle()
        val at = PrivacyRegistry.decorRectOf(plain)
        hostMasks = listOf(Rect(at.left + 5, at.top + 5, at.left + 20, at.top + 20))
        assertMasked("under a React Native / Flutter mask", plain)
        hostMasks = listOf(Rect(at.right + 50, at.bottom + 50, at.right + 60, at.bottom + 60))
        assertEquals("a mask elsewhere leaves it", false, leave(plain).masked)
    }

    @Test
    fun hostMasksAreMatchedWhereTheFrameShowsTheField() {
        // Host masks are in frame coordinates: with the window panned for
        // the keyboard, the frame shows the field higher up.
        val plain = field()
        shadowOf(Looper.getMainLooper()).idle()
        val at = PrivacyRegistry.decorRectOf(plain)
        panWindow(activity, 30)
        try {
            hostMasks = listOf(Rect(at.left, at.top - 30, at.left + 10, at.top - 25))
            assertMasked("the mask over the field in the panned frame", plain)
        } finally {
            panWindow(activity, 0)
        }
    }

    @Test
    fun aPlainFieldSendsItsText() {
        val typed = leave(field())
        assertEquals("4111 1111 1111 1111", typed.value)
        assertEquals(false, typed.masked)
    }

    @Test
    fun theHostsFocusListenerStillRuns() {
        var hostCalls = 0
        val v = EditText(activity).apply {
            onFocusChangeListener = View.OnFocusChangeListener { _, _ -> hostCalls++ }
            root.addView(this)
        }
        capture.observe(v)
        v.onFocusChangeListener.onFocusChange(v, false)
        assertEquals(1, hostCalls)
        assertEquals(1, reported.size)
    }
}
