package com.replayfy.android.internal.mobile

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.replayfy.android.MaskRect
import com.replayfy.android.ReplayConfig
import com.replayfy.android.ReplayMaskStyle
import com.replayfy.android.internal.privacy.PrivacyRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A window panned for the keyboard (adjustPan) draws its content moved up,
 * which the views' own geometry leaves out. A frame shows the window as
 * drawn, so its masks move with the content: in the live session's privacy
 * lookup and in the draw fallback's pixels. (Robolectric's PixelCopy draws
 * the views without the pan, so the PixelCopy path itself is a device check.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdjustPanMaskingTest {
    private val uploader = FakeUploader()

    @Before
    fun setUp() = resetRegistry()

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

    /** A red secret low on a white screen, and a password field and a
     *  WebView, as on a form the keyboard pans. */
    class FormActivity : Activity() {
        lateinit var secret: View
        lateinit var field: EditText
        lateinit var web: WebView
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
            secret = View(this).apply { setBackgroundColor(Color.RED) }
            root.addView(secret, FrameLayout.LayoutParams(200, 60).also { it.leftMargin = 20; it.topMargin = 300 })
            field = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            root.addView(field, FrameLayout.LayoutParams(200, 40).also { it.leftMargin = 20; it.topMargin = 380 })
            web = LaidOutWebView(this)
            root.addView(web, FrameLayout.LayoutParams(200, 100).also { it.leftMargin = 20; it.topMargin = 100 })
            setContentView(root)
        }
    }

    private fun luma(px: Int) = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3

    private fun isRed(px: Int) = Color.red(px) > 150 && Color.green(px) < 90 && Color.blue(px) < 90

    private fun onlyFrame(shots: MobileScreenshots): Bitmap {
        waitUntil { shots.pendingCount == 1 }
        assertEquals("one frame encoded", 1, shots.pendingCount)
        shots.flush(all = true)
        waitUntil { uploader.imageJpegs.size == 1 }
        val jpeg = uploader.imageJpegs.single()
        return requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "frame decodes" }
    }

    /** Run [block] where PixelCopy's callback runs: off the main thread. */
    private fun offMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        Thread({ block(); done.countDown() }, "pixelcopy-callback").start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun theDrawFallbackDrawsThePannedWindowWithTheMaskOnTheSecret() {
        val activity = Robolectric.buildActivity(FormActivity::class.java).setup().get()
        val decor = activity.window.decorView
        PrivacyRegistry.add(activity.secret)
        panWindow(activity, 150)
        try {
            val rects = PrivacyRegistry.inFrame(
                PrivacyRegistry.sensitiveBounds(decor).map { MaskRect(it, ReplayMaskStyle.OVERLAY) },
                decor,
            )
            val shots = MobileScreenshots(uploader, activityProvider = { null }, privacyRects = { emptyList() })
            assertTrue(shots.captureBegan())

            // A failed copy: the window is drawn on main, as its surface shows it.
            val copy = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            offMain { shots.onPixelCopyResult(false, copy, decor, rects) }
            shadowOf(Looper.getMainLooper()).idle()
            val frame = onlyFrame(shots)

            val drawn = IntArray(2).also { activity.secret.getLocationInWindow(it) }
            val centre = frame.getPixel((drawn[0] + 100) / 2, (drawn[1] + 30) / 2)
            assertTrue("the secret, drawn 150px up, is masked: luma ${luma(centre)}", Math.abs(luma(centre) - 56) <= 8)
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                if (isRed(frame.getPixel(x, y))) fail("the secret shows at ($x, $y)")
            }
        } finally {
            panWindow(activity, 0)
        }
    }

    @Test
    fun aLiveSessionPutsItsMasksWhereThePannedFrameShowsTheFields() {
        val server = FakeIngestServer()
        val core = ReplayCore()
        val live = AtomicInteger()
        core.addVerificationListener { live.incrementAndGet() }
        try {
            val app: Application = ApplicationProvider.getApplicationContext()
            core.start(app, ReplayConfig(
                apiKey = "rpl_pk_test",
                apiHost = server.url,
                captureConsole = false,
                captureErrors = false,
                captureSnapshotPixels = true,
            ))
            val activity = Robolectric.buildActivity(FormActivity::class.java).setup().get()
            waitUntil(10_000) { live.get() >= 1 }
            assertTrue("session went live", live.get() >= 1)
            val atRest = Rect().also { assertTrue(activity.field.getGlobalVisibleRect(it)) }

            panWindow(activity, 150)
            val masks = core.privacyRectsProvider().map { it.rect }
            assertEquals(listOf(Rect(atRest).apply { offset(0, -150) }), masks)
            assertEquals("the walk left the WebView for the scan", 1, core.pendingWebViewCount)
            panWindow(activity, 0)

            // A host's lookup that throws fails the whole lookup (the frame
            // is skipped): no WebView is left waiting for a scan that won't
            // come.
            core.externalPrivacyRectsProvider = { throw IllegalStateException("bridge gone") }
            try {
                core.privacyRectsProvider()
                fail("the lookup should fail")
            } catch (e: IllegalStateException) { /* expected */ }
            assertEquals(0, core.pendingWebViewCount)
        } finally {
            core.externalPrivacyRectsProvider = { emptyList() }
            core.cancelSession()
            server.close()
        }
    }
}
