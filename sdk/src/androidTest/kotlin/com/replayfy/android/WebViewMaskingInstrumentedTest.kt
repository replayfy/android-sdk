package com.replayfy.android

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replayfy.android.internal.mobile.MobileScreenshots
import com.replayfy.android.internal.privacy.PrivacyRegistry
import com.replayfy.android.internal.privacy.WebViewMaskScanner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * On-device proof that password inputs inside a WebView are masked where the
 * page draws them: a real page, a real script run, real PixelCopy frames.
 *
 * The WebView sits below a 200px native header, so it is NOT at the window
 * origin: a scan that counted the WebView's offset twice (or not at all)
 * lands its masks off the field. Also checks scrolling and pinch zoom, the
 * dashboard's "mask all inputs", and that an iframe's password field is NOT
 * reached (the top document only, as in the reference tracker).
 */
@RunWith(AndroidJUnit4::class)
class WebViewMaskingInstrumentedTest {

    @Before
    fun setUp() = reset()

    @After
    fun tearDown() = reset()

    private fun reset() {
        PrivacyRegistry.clear()
        PrivacyRegistry.occludeAllScreen = false
        PrivacyRegistry.occludeAllTextFields = false
        PrivacyRegistry.remoteOccludeAllTextFields = false
        PrivacyRegistry.occludeAllTextViews = false
        PrivacyRegistry.maskStyle = ReplayMaskStyle.OVERLAY
    }

    private fun launch(): ActivityScenario<WebViewMaskTestActivity> {
        val scenario = ActivityScenario.launch(WebViewMaskTestActivity::class.java)
        var loaded: CountDownLatch? = null
        scenario.onActivity { loaded = it.loaded }
        assertTrue("page loaded", loaded!!.await(15, TimeUnit.SECONDS))
        Thread.sleep(300)
        return scenario
    }

    /** One scan, started on main and waited for here, off main. */
    private fun scan(scenario: ActivityScenario<WebViewMaskTestActivity>, occludeAll: Boolean): Page {
        val scanner = WebViewMaskScanner(sampleTimeoutMs = 2_000, awaitBudgetMs = 3_000)
        var await: ((Long) -> List<MaskRect>)? = null
        var page: Page? = null
        scenario.onActivity { a ->
            val decor = a.window.decorView
            await = scanner.begin(decor, listOf(a.web), occludeAll)
            val d = IntArray(2).also { decor.getLocationOnScreen(it) }
            val w = IntArray(2).also { a.web.getLocationOnScreen(it) }
            page = Page(w[0] - d[0], w[1] - d[1], a.resources.displayMetrics.density, emptyList())
        }
        val masks = await!!(3_000)
        masks.forEach { assertEquals("WebView masks are solid", ReplayMaskStyle.OVERLAY, it.style) }
        return page!!.copy(masks = masks.map { it.rect })
    }

    /** Where the WebView is in the decor, its density, and the masks found. */
    data class Page(val x: Int, val y: Int, val density: Float, val masks: List<Rect>) {
        /** A CSS-pixel box of the page (at zoom 1, unscrolled), in decor px. */
        fun css(l: Int, t: Int, r: Int, b: Int, scrollY: Int = 0) = Rect(
            (l * density).roundToInt() + x, (t * density).roundToInt() + y - scrollY,
            (r * density).roundToInt() + x, (b * density).roundToInt() + y - scrollY,
        )

        /** The mask within [tol] px of [expected] on every edge, if any. */
        fun near(expected: Rect, tol: Int = 3): Rect? = masks.firstOrNull {
            abs(it.left - expected.left) <= tol && abs(it.top - expected.top) <= tol &&
                abs(it.right - expected.right) <= tol && abs(it.bottom - expected.bottom) <= tol
        }

        /** The mask whose top-left is closest to [expected]'s. */
        fun closest(expected: Rect): Rect? =
            masks.minByOrNull { abs(it.left - expected.left) + abs(it.top - expected.top) }
    }

    @Test
    fun thePasswordAndTheManualClassAreMaskedWhereThePageDrawsThem() {
        val scenario = launch()
        val page = scan(scenario, occludeAll = false)

        assertNotNull("password at ${page.css(20, 100, 220, 140)}, masks ${page.masks}", page.near(page.css(20, 100, 220, 140)))
        assertNotNull("rr-block div, masks ${page.masks}", page.near(page.css(20, 300, 120, 340)))
        assertNull("the text input is not masked", page.near(page.css(20, 200, 220, 240)))
        assertEquals("nothing in the iframe is reached: ${page.masks}", 2, page.masks.size)
        scenario.close()
    }

    @Test
    fun masksFollowTheScroll() {
        val scenario = launch()
        scenario.onActivity { it.web.scrollBy(0, 50) }
        Thread.sleep(300)
        val page = scan(scenario, occludeAll = false)

        assertNotNull("scrolled up 50px, masks ${page.masks}", page.near(page.css(20, 100, 220, 140, scrollY = 50)))
        scenario.close()
    }

    @Test
    fun masksFollowPinchZoom() {
        val scenario = launch()
        val before = scan(scenario, occludeAll = false).let { it.closest(it.css(20, 100, 220, 140)) }
        assertNotNull(before)
        scenario.onActivity { it.web.zoomBy(2f) }
        Thread.sleep(600)
        val after = scan(scenario, occludeAll = false)

        // The field is drawn twice as tall at zoom 2 (its width may run past
        // the screen edge and be clipped).
        val tallest = after.masks.maxByOrNull { it.height() }
        assertNotNull(tallest)
        assertTrue("zoomed height ${tallest!!.height()} vs ${before!!.height()}", abs(tallest.height() - 2 * before.height()) <= 4)
        scenario.close()
    }

    @Test
    fun maskAllInputsAlsoMasksTheTextInputButStillNotTheIframe() {
        val scenario = launch()
        val page = scan(scenario, occludeAll = true)

        assertNotNull("the text input, masks ${page.masks}", page.near(page.css(20, 200, 220, 240)))
        assertNotNull(page.near(page.css(20, 100, 220, 140)))
        assertEquals("password, text, rr-block: ${page.masks}", 3, page.masks.size)
        scenario.close()
    }

    @Test
    fun theRecordedFrameIsMaskedThroughTheRealCapturePath() {
        val scenario = launch()
        val sink = FakeFrameSink()
        val scanner = WebViewMaskScanner()
        var activity: WebViewMaskTestActivity? = null
        scenario.onActivity { activity = it }
        val shots = MobileScreenshots(
            sink,
            activityProvider = { activity },
            privacyRects = { emptyList() },
            nativeCapture = true,
            webViewRects = { decor -> scanner.begin(decor, listOf(activity!!.web), false) },
        )
        var page: Page? = null
        scenario.onActivity { a ->
            val d = IntArray(2).also { a.window.decorView.getLocationOnScreen(it) }
            val w = IntArray(2).also { a.web.getLocationOnScreen(it) }
            page = Page(w[0] - d[0], w[1] - d[1], a.resources.displayMetrics.density, emptyList())
            assertTrue(shots.captureBegan())
            shots.capture()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (shots.pendingCount < 1 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        shots.flush(all = true)
        while (sink.frames.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val jpeg = sink.frames.single()
        val frame = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))

        fun grey(r: Rect): Boolean {
            val px = frame.getPixel((r.centerX() / 2).coerceIn(0, frame.width - 1), (r.centerY() / 2).coerceIn(0, frame.height - 1))
            return abs(Color.red(px) - 56) <= 12 && abs(Color.green(px) - 56) <= 12 && abs(Color.blue(px) - 56) <= 12
        }
        val at = page!!
        assertTrue("the password field is covered", grey(at.css(20, 100, 220, 140)))
        assertTrue("the text input is not", !grey(at.css(20, 200, 220, 240)))
        scenario.close()
    }
}

/**
 * A native header 200px tall, then a WebView with a page holding a password
 * input, a text input, an rr-block element and an iframe with a password
 * input of its own. Lives only in the test APK.
 */
class WebViewMaskTestActivity : Activity() {
    lateinit var web: WebView
    val loaded = CountDownLatch(1)

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
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        column.addView(TextView(this).apply {
            text = "HEADER"
            setBackgroundColor(Color.LTGRAY)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 200))
        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = loaded.countDown()
            }
        }
        column.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        web.loadDataWithBaseURL("https://replayfy.test/", PAGE, "text/html", "utf-8", null)
    }

    private companion object {
        const val PAGE = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes">
<style>body{margin:0;height:3000px;background:#fff} input{position:absolute;border:0;padding:0;background:#fff;font:16px sans-serif}</style>
</head><body>
<input type="password" value="SECRET" style="left:20px;top:100px;width:200px;height:40px">
<input type="text" value="visible" style="left:20px;top:200px;width:200px;height:40px">
<div class="rr-block" style="position:absolute;left:20px;top:300px;width:100px;height:40px;background:#fff">manual</div>
<iframe srcdoc="&lt;input type=password value=INNER style='width:150px;height:30px'&gt;" style="position:absolute;left:20px;top:400px;width:200px;height:60px;border:0"></iframe>
</body></html>"""
    }
}
