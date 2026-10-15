package com.replayfy.android.internal.mobile

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.replayfy.android.MaskRect
import com.replayfy.android.ReplayConfig
import com.replayfy.android.ReplayMaskStyle
import com.replayfy.android.internal.privacy.PrivacyRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView masks in recorded frames: the screenshotter waits for them on the
 * PixelCopy thread (never on main, where the page answers), paints them
 * solid, and skips the frame if the wait fails; and a live session wires the
 * walk's WebViews into the scan, so a password box a page reports is masked
 * in the uploaded JPEG.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebViewMaskingTest {
    private val uploader = FakeUploader()
    private val mainThread: Thread = Looper.getMainLooper().thread

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

    private fun luma(px: Int) = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3

    private fun white(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun whiteView() = View(ApplicationProvider.getApplicationContext()).apply {
        setBackgroundColor(Color.WHITE)
        layout(0, 0, 200, 200)
    }

    private fun shots() = MobileScreenshots(uploader, activityProvider = { null }, privacyRects = { emptyList() }, nativeCapture = true)

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

    /** A WebView-mask awaiter over the top-left 100×100 that records how
     *  it was waited for. */
    private class Awaiter : (Long) -> List<MaskRect> {
        @Volatile var thread: Thread? = null
        @Volatile var budget = -1L
        override fun invoke(budgetMs: Long): List<MaskRect> {
            thread = Thread.currentThread()
            budget = budgetMs
            return listOf(MaskRect(Rect(0, 0, 100, 100), ReplayMaskStyle.OVERLAY))
        }
    }

    @Test
    fun webViewMasksArePaintedSolidAndWaitedForOffTheMainThread() {
        val shots = shots()
        val web = Awaiter()
        assertTrue(shots.captureBegan())

        offMain { shots.onPixelCopyResult(true, white(200, 200), whiteView(), emptyList(), web) }

        val frame = onlyFrame(shots)
        assertTrue("masked, luma ${luma(frame.getPixel(20, 20))}", Math.abs(luma(frame.getPixel(20, 20)) - 56) <= 8)
        assertTrue("outside stays clear", luma(frame.getPixel(80, 80)) > 240)
        assertEquals(MobileScreenshots.WEBVIEW_WAIT_MS, web.budget)
        assertNotEquals("never waited for on main", mainThread, web.thread)
    }

    @Test
    fun theDrawFallbackAlsoWaitsOffTheMainThread() {
        val shots = shots()
        val web = Awaiter()
        assertTrue(shots.captureBegan())

        // A failed copy: the draw is posted to main, the encode (and the wait)
        // goes back to the PixelCopy thread.
        offMain { shots.onPixelCopyResult(false, white(200, 200), whiteView(), emptyList(), web) }
        shadowOf(Looper.getMainLooper()).idle()

        val frame = onlyFrame(shots)
        assertTrue(Math.abs(luma(frame.getPixel(20, 20)) - 56) <= 8)
        assertEquals("replay-pixelcopy", web.thread?.name)
    }

    @Test
    fun aFailedWaitSkipsTheFrameRatherThanShipItUnmasked() {
        val shots = shots()
        assertTrue(shots.captureBegan())
        offMain {
            shots.onPixelCopyResult(true, white(200, 200), whiteView(), emptyList()) { throw IllegalStateException("boom") }
        }
        // A frame that follows still goes out: the failed one was skipped.
        assertTrue(shots.captureBegan())
        offMain { shots.onPixelCopyResult(true, white(200, 200), whiteView(), emptyList()) }

        assertTrue(luma(onlyFrame(shots).getPixel(20, 20)) > 240)
    }

    // ── A live session ───────────────────────────────────────────────

    /** A page in a WebView placed below a native header. */
    class WebViewActivity : Activity() {
        lateinit var web: WebView
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
            web = LaidOutWebView(this).apply { setBackgroundColor(Color.WHITE) }
            root.addView(web, FrameLayout.LayoutParams(300, 300).also { it.topMargin = 100 })
            setContentView(root)
        }
    }

    @Test
    fun aPasswordBoxThePageReportsIsMaskedInTheUploadedFrame() {
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
            val activity = Robolectric.buildActivity(WebViewActivity::class.java).setup().get()
            waitUntil(10_000) { live.get() >= 1 }
            assertTrue("session went live", live.get() >= 1)

            // Run the capture timer in small steps until a tick's walk finds
            // the shown WebView and its first sample runs; then stop the
            // clock, so the sample can't time out and no further tick runs.
            val page = shadowOf(activity.web)
            var steps = 0
            while (page.lastEvaluatedJavascript == null && steps++ < 100) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            }
            assertTrue("the scan ran in the page", page.lastEvaluatedJavascript?.contains("visualViewport") == true)

            // The page answers both samples with a password box.
            val loc = IntArray(2).also { activity.web.getLocationOnScreen(it) }
            val box = Rect(loc[0] + 20, loc[1] + 40, loc[0] + 220, loc[1] + 100)
            val answer = JSONObject.quote(JSONArray(listOf(JSONArray(listOf(box.left, box.top, box.right, box.bottom)))).toString())
            page.lastEvaluatedJavascriptCallback.onReceiveValue(answer)
            page.lastEvaluatedJavascriptCallback.onReceiveValue(answer)
            shadowOf(Looper.getMainLooper()).idle() // a draw fallback, if the copy failed

            // That tick's frame is the last one captured: let it encode.
            val shots = core.liveScreenshots ?: error("no live session")
            waitUntil { shots.pendingCount >= 1 }
            Thread.sleep(MobileScreenshots.WEBVIEW_WAIT_MS + 200)
            shots.flush(all = true)
            waitUntil { server.frameCount >= 1 && shots.pendingCount == 0 }
            val jpeg = server.on("/v1/mobile/images").flatMap { frameJpegs(it.body) }.last()
            val frame = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))

            val decor = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
            fun at(x: Int, y: Int) = luma(frame.getPixel((x - decor[0]) / 2, (y - decor[1]) / 2))
            assertTrue("the box is masked, luma ${at(box.centerX(), box.centerY())}", Math.abs(at(box.centerX(), box.centerY()) - 56) <= 8)
            assertTrue("the rest of the page is not, luma ${at(loc[0] + 250, loc[1] + 250)}", at(loc[0] + 250, loc[1] + 250) > 200)
        } finally {
            core.cancelSession()
            server.close()
        }
    }
}
