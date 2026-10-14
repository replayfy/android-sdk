package com.replayfy.android.internal.privacy

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.replayfy.android.MaskRect
import com.replayfy.android.ReplayMaskStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the frames screenshotter masks without any setup, and where: the
 * clipped, transformed, on-screen rect of each sensitive view, in decor
 * coordinates. Also the dashboard's "always mask input values", which only
 * ever adds to the app's own switch.
 */
@RunWith(RobolectricTestRunner::class)
class PrivacyRegistryWalkTest {
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private val decor: View get() = activity.window.decorView

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
        PrivacyRegistry.consumePendingFrameRects()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <V : View> V.at(left: Int, top: Int, w: Int, h: Int, parent: ViewGroup = root): V {
        parent.addView(this, FrameLayout.LayoutParams(w, h).also { it.leftMargin = left; it.topMargin = top })
        return this
    }

    private fun password() = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun plain() = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT }

    private fun globalRect(v: View) = Rect().also { assertTrue(v.getGlobalVisibleRect(it)) }

    private fun masks(style: ReplayMaskStyle = PrivacyRegistry.maskStyle) = PrivacyRegistry.bulkMaskRects(decor, style)

    @Test
    fun aPasswordFieldIsMaskedSolidWhateverTheGlobalStyle() {
        val field = password().at(20, 30, 200, 40)
        idle()

        val out = masks(ReplayMaskStyle.BLUR)

        assertEquals(listOf(MaskRect(globalRect(field), ReplayMaskStyle.OVERLAY)), out)
    }

    @Test
    fun aCardFieldIsMaskedAndAPlainOneIsNot() {
        val card = plain().apply { setAutofillHints("creditCardNumber") }.at(20, 30, 200, 40)
        plain().at(20, 100, 200, 40)
        idle()

        assertEquals(listOf(globalRect(card)), masks().map { it.rect })
    }

    @Test
    fun hiddenFieldsAreNotMasked() {
        password().apply { visibility = View.INVISIBLE }.at(0, 0, 100, 40)
        password().apply { visibility = View.GONE }.at(0, 50, 100, 40)
        val hiddenParent = FrameLayout(activity).apply { visibility = View.INVISIBLE }.at(0, 100, 200, 100)
        password().at(0, 0, 100, 40, hiddenParent)
        idle()

        assertTrue(masks().isEmpty())
    }

    @Test
    fun aDeeplyNestedFieldIsFound() {
        var parent: ViewGroup = root
        repeat(4) { parent = FrameLayout(activity).at(5, 5, 300, 300, parent) }
        val field = password().at(10, 10, 100, 40, parent)
        idle()

        assertEquals(listOf(globalRect(field)), masks().map { it.rect })
    }

    @Test
    fun aFieldHalfScrolledOutIsMaskedOnlyWhereItShows() {
        val scroll = ScrollView(activity).at(0, 0, 300, 100)
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column, ViewGroup.LayoutParams(300, ViewGroup.LayoutParams.WRAP_CONTENT))
        val field = password()
        column.addView(field, LinearLayout.LayoutParams(300, 80))
        column.addView(View(activity), LinearLayout.LayoutParams(300, 400))
        idle()
        scroll.scrollTo(0, 40)
        idle()

        val rect = masks().single().rect
        assertEquals("only the 40px still on screen", 40, rect.height())
        assertEquals(globalRect(field), rect)
    }

    @Test
    fun aScaledUpFieldIsMaskedAtTheSizeItIsDrawn() {
        val field = password().at(50, 50, 100, 40)
        field.scaleX = 1.5f
        field.scaleY = 1.5f
        idle()

        val rect = masks().single().rect
        assertTrue("width ${rect.width()} covers the drawn 150", rect.width() >= 150)
        assertTrue("height ${rect.height()} covers the drawn 60", rect.height() >= 60)
    }

    @Test
    fun everyFieldOnScreenIsMaskedWithNoCap() {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }.at(0, 0, 300, 300)
        repeat(150) { column.addView(password(), LinearLayout.LayoutParams(300, 2)) }
        idle()

        assertEquals(150, masks().size)
    }

    @Test
    fun registeredViewsUseTheSameClippedGeometry() {
        val scroll = ScrollView(activity).at(0, 0, 300, 100)
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column, ViewGroup.LayoutParams(300, ViewGroup.LayoutParams.WRAP_CONTENT))
        val label = TextView(activity).apply { text = "4111 1111 1111 1111" }
        column.addView(label, LinearLayout.LayoutParams(300, 80))
        column.addView(View(activity), LinearLayout.LayoutParams(300, 400))
        PrivacyRegistry.add(label)
        idle()
        scroll.scrollTo(0, 40)
        idle()

        assertEquals(listOf(globalRect(label)), PrivacyRegistry.sensitiveBounds(decor))
        assertEquals(40, PrivacyRegistry.sensitiveBounds(decor).single().height())
    }

    @Test
    fun theWalkCollectsWebViews() {
        val web = WebView(activity).at(0, 200, 300, 200)
        password().at(0, 0, 100, 40)
        idle()

        val found = ArrayList<WebView>()
        val out = PrivacyRegistry.bulkMaskRects(decor, ReplayMaskStyle.OVERLAY, found)

        assertEquals(listOf(web), found)
        assertEquals(1, out.size)
    }

    @Test
    fun composeBoundsStayInWindowCoordinatesForTheDecor() {
        val token = Any()
        PrivacyRegistry.addComposeBounds(token, Rect(10, 20, 110, 60))
        try {
            assertEquals(listOf(Rect(10, 20, 110, 60)), PrivacyRegistry.composeBoundsRelativeTo(decor))
        } finally {
            PrivacyRegistry.removeComposeBounds(token)
        }
    }

    // ── The dashboard's "always mask input values" ───────────────────

    @Test
    fun aPlainFieldIsMaskedByTheAppOrTheDashboardAndNeitherClearsTheOther() {
        val field = plain().at(20, 30, 200, 40)
        idle()
        assertTrue("not masked by default", masks().isEmpty())
        assertFalse(PrivacyRegistry.isSensitive(field))

        PrivacyRegistry.occludeAllTextFields = true
        assertEquals(listOf(globalRect(field)), masks().map { it.rect })

        PrivacyRegistry.occludeAllTextFields = false
        PrivacyRegistry.remoteOccludeAllTextFields = true
        assertEquals("the dashboard alone masks it", listOf(globalRect(field)), masks().map { it.rect })
        assertTrue(PrivacyRegistry.isSensitive(field))

        // The app switching its own flag off doesn't undo the dashboard's.
        com.replayfy.android.Replay.occludeAllTextFields(false)
        assertEquals(1, masks().size)
        assertTrue(PrivacyRegistry.isSensitive(field))
        // A field the dashboard forces takes the global style: it isn't one
        // of the always-solid auto-masked fields.
        assertEquals(ReplayMaskStyle.BLUR, masks(ReplayMaskStyle.BLUR).single().style)
    }

    @Test
    fun alwaysSensitiveFieldsAreMaskedWithNoSwitchOn() {
        val fields = listOf(129, 145, 225, 18).mapIndexed { i, type ->
            plain().apply { inputType = type }.at(0, i * 45, 200, 40)
        } + listOf(
            plain().apply { transformationMethod = PasswordTransformationMethod.getInstance() }.at(0, 180, 200, 40),
            plain().apply { setAutofillHints("creditCardNumber") }.at(0, 225, 200, 40),
            plain().apply { setAutofillHints("smsOTPCode") }.at(0, 270, 200, 40),
        )
        idle()

        val out = masks()
        assertEquals(fields.map { globalRect(it) }, out.map { it.rect })
        assertTrue(out.all { it.style == ReplayMaskStyle.OVERLAY })
        assertTrue(fields.all { PrivacyRegistry.isSensitive(it) })
    }
}
