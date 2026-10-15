package com.replayfy.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.replayfy.android.internal.mobile.MobileScreenshots
import com.replayfy.android.internal.privacy.PrivacyRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof that the mobile frames screenshotter masks sensitive
 * regions. Guards the bug where `ReplayCore.privacyRectsProvider` was
 * never wired, so masking never reached the captured JPEGs.
 *
 * Builds a screen with a public control, a registry-marked label, and a
 * password [EditText]; asks the real privacy collectors (the same set
 * `ReplayCore.privacyRectsProvider` composes) for rects; renders the
 * decor view to a bitmap and paints the masks exactly like
 * `MobileScreenshots.encode`; then samples pixels. Writes a PNG for
 * inspection.
 */
@RunWith(AndroidJUnit4::class)
class MobilePrivacyMaskingTest {

    @Test
    fun masksMarkedViewAndPasswordField() {
        // Clean registry state so order/other tests don't bleed in.
        PrivacyRegistry.clear()
        PrivacyRegistry.occludeAllScreen = false
        PrivacyRegistry.occludeAllTextFields = false
        PrivacyRegistry.occludeAllTextViews = false

        val scenario = ActivityScenario.launch(MaskTestActivity::class.java)

        var rects: List<Rect> = emptyList()
        var bmp: Bitmap? = null
        var markedC = intArrayOf(0, 0)
        var passwordC = intArrayOf(0, 0)
        var controlC = intArrayOf(0, 0)

        scenario.onActivity { activity ->
            val root: View = activity.window.decorView

            // Mark the label through the public registry path.
            PrivacyRegistry.add(activity.marked)

            // Exactly the composition ReplayCore.privacyRectsProvider uses.
            rects = PrivacyRegistry.sensitiveBounds(root) +
                PrivacyRegistry.bulkBounds(root) +
                PrivacyRegistry.composeBoundsRelativeTo(root)

            // Render decor view → bitmap and paint masks, mirroring
            // MobileScreenshots.encode (sans the 0.5 downscale).
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(b))
            val canvas = Canvas(b)
            val paint = Paint().apply { color = Color.DKGRAY }
            for (r in rects) canvas.drawRect(r, paint)
            bmp = b

            markedC = centerInRoot(activity.marked, root)
            passwordC = centerInRoot(activity.password, root)
            controlC = centerInRoot(activity.control, root)
        }

        val b = requireNotNull(bmp) { "bitmap was not captured" }

        assertTrue("expected ≥2 rects (marked + password), got ${rects.size}", rects.size >= 2)
        assertTrue("marked label center not masked", isDkGray(b, markedC))
        assertTrue("password field center not masked", isDkGray(b, passwordC))
        assertFalse("public control center wrongly masked", isDkGray(b, controlC))

        // Persist the proof to the test app's external files dir so it can
        // be pulled off the device with `adb pull`.
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val out = File(ctx.getExternalFilesDir(null), "replay_android_mask.png")
        out.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("REPLAY_PROOF_PNG=${out.absolutePath}")

        scenario.close()
    }

    /**
     * The field matrix through the REAL frames path: capture → PixelCopy →
     * encode → upload, with the privacy rects composed as the live session
     * composes them. Every automatically masked field is a solid box, edge to
     * edge, even with the global style BLUR; a privacy view scaled 1.5x is
     * covered at the size it is drawn; the plain control is left alone.
     */
    @Test
    fun realFramesMaskEverySensitiveFieldSolid() {
        PrivacyRegistry.clear()
        PrivacyRegistry.occludeAllScreen = false
        PrivacyRegistry.occludeAllTextFields = false
        PrivacyRegistry.remoteOccludeAllTextFields = false
        PrivacyRegistry.occludeAllTextViews = false
        try {
            val scenario = ActivityScenario.launch(FieldMatrixActivity::class.java)
            var activity: FieldMatrixActivity? = null
            scenario.onActivity { activity = it; PrivacyRegistry.add(it.scaled) }

            // Auto-masked fields stay solid whatever the global style.
            val blurred = frameOf(activity!!, ReplayMaskStyle.BLUR)
            for ((name, r) in blurred.auto) assertSolid(blurred.frame, name, r)
            assertFalse("the control is left alone", isMaskGrey(blurred.frame, blurred.control.centerX() / 2, blurred.control.centerY() / 2))

            // The default style: the scaled privacy view is covered where it
            // is drawn, 1.5x its layout size.
            val solid = frameOf(activity!!, ReplayMaskStyle.OVERLAY)
            assertTrue("drawn at 1.5x: ${solid.scaled}", solid.scaled.width() >= (activity!!.scaled.width * 1.5f).toInt() - 1)
            assertSolid(solid.frame, "scaled privacy view", solid.scaled)
            for ((name, r) in solid.auto) assertSolid(solid.frame, name, r)
            scenario.close()
        } finally {
            PrivacyRegistry.clear()
            PrivacyRegistry.maskStyle = ReplayMaskStyle.OVERLAY
        }
    }

    /** One real frame of [activity] with the global [style], and where its
     *  views show in that frame (full-size frame px). */
    private class Shot(val frame: Bitmap, val auto: Map<String, Rect>, val control: Rect, val scaled: Rect)

    private fun frameOf(activity: FieldMatrixActivity, style: ReplayMaskStyle): Shot {
        PrivacyRegistry.maskStyle = style
        val sink = FakeFrameSink()
        val decor = activity.window.decorView
        val shots = MobileScreenshots(
            sink,
            activityProvider = { activity },
            // As ReplayCore's privacy lookup composes them.
            privacyRects = {
                val s = PrivacyRegistry.maskStyle
                PrivacyRegistry.inFrame(
                    PrivacyRegistry.sensitiveBounds(decor).map { MaskRect(it, s) } +
                        PrivacyRegistry.bulkMaskRects(decor, s) +
                        PrivacyRegistry.composeBoundsRelativeTo(decor).map { MaskRect(it, s) },
                    decor,
                )
            },
        )
        var auto: Map<String, Rect> = emptyMap()
        var control = Rect()
        var scaled = Rect()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            auto = activity.auto.mapValues { (_, v) -> inFrame(v, decor) }
            control = inFrame(activity.control, decor)
            scaled = inFrame(activity.scaled, decor)
            assertTrue(shots.captureBegan())
            shots.capture()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (shots.pendingCount < 1 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        shots.flush(all = true)
        while (sink.frames.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val jpeg = sink.frames.single()
        val frame = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "frame decodes" }
        val ctx = InstrumentationRegistry.getInstrumentation().context
        File(ctx.getExternalFilesDir(null), "replay_android_fields_${style.name.lowercase()}.jpg").writeBytes(jpeg)
        return Shot(frame, auto, control, scaled)
    }

    /** Where [v] shows in a recorded frame: its visible rect, moved as the
     *  window's surface shows it. */
    private fun inFrame(v: View, decor: View): Rect {
        val r = Rect().also { assertTrue("${v.javaClass.simpleName} shows", v.getGlobalVisibleRect(it)) }
        val o = IntArray(2).also { decor.getLocationInWindow(it) }
        return r.apply { offset(o[0], o[1]) }
    }

    /** The centre of [r] (full-size frame px) and each corner, 2px in, are
     *  the solid mask grey in the half-size [frame]. */
    private fun assertSolid(frame: Bitmap, name: String, r: Rect) {
        val l = r.left / 2 + 2
        val t = r.top / 2 + 2
        val rr = (r.right + 1) / 2 - 3
        val b = (r.bottom + 1) / 2 - 3
        for ((x, y) in listOf(r.centerX() / 2 to r.centerY() / 2, l to t, rr to t, l to b, rr to b)) {
            assertTrue("$name at ($x, $y) of $r is solid", isMaskGrey(frame, x, y))
        }
    }

    /** Whether the pixel is the solid OVERLAY grey, rgb(56, 56, 56) ±12. */
    private fun isMaskGrey(b: Bitmap, x: Int, y: Int): Boolean {
        val px = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
        return Math.abs(Color.red(px) - 56) <= 12 &&
            Math.abs(Color.green(px) - 56) <= 12 &&
            Math.abs(Color.blue(px) - 56) <= 12
    }

    /** Center of [v] in [root]-relative (decor-view) coords — the same
     *  space the privacy collectors and the rendered bitmap use. */
    private fun centerInRoot(v: View, root: View): IntArray {
        val vLoc = IntArray(2).also { v.getLocationOnScreen(it) }
        val rLoc = IntArray(2).also { root.getLocationOnScreen(it) }
        return intArrayOf(vLoc[0] - rLoc[0] + v.width / 2, vLoc[1] - rLoc[1] + v.height / 2)
    }

    /** Whether the pixel at [c] is ~Color.DKGRAY (0xFF444444 ≈ rgb(68,68,68)). */
    private fun isDkGray(b: Bitmap, c: IntArray): Boolean {
        val x = c[0].coerceIn(0, b.width - 1)
        val y = c[1].coerceIn(0, b.height - 1)
        val px = b.getPixel(x, y)
        return Math.abs(Color.red(px) - 68) < 40 &&
            Math.abs(Color.green(px) - 68) < 40 &&
            Math.abs(Color.blue(px) - 68) < 40
    }
}

/**
 * Bare host activity for the masking test. Three children: a public
 * control, a label marked via `addPrivacyView`, and a password field
 * (masked unconditionally by the always-on password default).
 */
class MaskTestActivity : Activity() {
    lateinit var control: TextView
    lateinit var marked: TextView
    lateinit var password: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force the activity to show + resume even over a locked / asleep
        // emulator screen — otherwise it never reaches RESUMED and
        // ActivityScenario.launch blocks forever (the test hang).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        control = TextView(this).apply {
            text = "PUBLIC"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(600, 120).also {
                it.leftMargin = 60; it.topMargin = 80
            }
        }
        marked = TextView(this).apply {
            text = "SECRET-LABEL"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(600, 120).also {
                it.leftMargin = 60; it.topMargin = 420
            }
        }
        password = EditText(this).apply {
            setText("p@ssw0rd")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(600, 120).also {
                it.leftMargin = 60; it.topMargin = 760
            }
        }

        root.addView(control)
        root.addView(marked)
        root.addView(password)
        setContentView(root)
    }
}

/**
 * The field matrix for [MobilePrivacyMaskingTest.realFramesMaskEverySensitiveFieldSolid]:
 * every kind of field masked with no setup, a plain control, and a label
 * scaled 1.5x (registered with addPrivacyView by the test). Each field is
 * filled yellow, so an unmasked one can't pass for the mask grey.
 */
class FieldMatrixActivity : Activity() {
    /** The automatically masked fields, by name. */
    val auto = LinkedHashMap<String, EditText>()
    lateinit var control: EditText
    lateinit var scaled: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        var top = 60
        fun place(v: View, h: Int = 110, w: Int = 600, left: Int = 60) {
            root.addView(v, FrameLayout.LayoutParams(w, h).also { it.leftMargin = left; it.topMargin = top })
            top += h + 40
        }
        fun field(name: String?, setup: EditText.() -> Unit): EditText = EditText(this).apply {
            setText("4111 1111 1111 1111")
            setBackgroundColor(Color.YELLOW)
            setTextColor(Color.BLACK)
            setup()
            place(this)
            if (name != null) auto[name] = this
        }
        field("textPassword|textNoSuggestions") { inputType = 0x80081 }
        field("numberPassword") {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setText("123456")
        }
        field("transformation only") {
            inputType = InputType.TYPE_CLASS_TEXT
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        // Autofill hints exist from Android 8.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            field("creditCardNumber hint") { inputType = InputType.TYPE_CLASS_NUMBER; setAutofillHints("creditCardNumber") }
            field("smsOTPCode hint") { inputType = InputType.TYPE_CLASS_NUMBER; setAutofillHints("smsOTPCode"); setText("482913") }
        }
        control = field(null) { inputType = InputType.TYPE_CLASS_TEXT }
        scaled = TextView(this).apply {
            text = "SCALED 4111"
            setBackgroundColor(Color.YELLOW)
            setTextColor(Color.BLACK)
            scaleX = 1.5f
            scaleY = 1.5f
        }
        // Scaled about its centre, it is drawn 450x120 and stays on screen.
        top += 40
        place(scaled, h = 80, w = 300, left = 200)
        setContentView(root)
    }
}
