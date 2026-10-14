package com.replayfy.android.internal.mobile

import android.app.Activity
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
    private val capture = MobileInputCapture { value, masked, label -> reported.add(Typed(value, masked, label)) }

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
