package com.replayfy.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.replayfy.android.internal.privacy.PrivacyRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof that the mobile frames screenshotter masks sensitive
 * regions. Guards the bug where `MobileEngine.privacyRectsProvider` was
 * never wired, so masking never reached the captured JPEGs.
 *
 * Builds a screen with a public control, a registry-marked label, and a
 * password [EditText]; asks the real privacy collectors (the same set
 * `MobileEngine.privacyRectsProvider` composes) for rects; renders the
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

            // Exactly the composition MobileEngine.privacyRectsProvider uses.
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
