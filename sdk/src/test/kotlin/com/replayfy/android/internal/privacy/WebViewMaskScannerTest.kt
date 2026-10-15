package com.replayfy.android.internal.privacy

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import com.replayfy.android.MaskRect
import com.replayfy.android.ReplayMaskStyle
import com.replayfy.android.internal.mobile.LaidOutWebView
import com.replayfy.android.internal.mobile.panWindow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The WebView password scan: how a script answer becomes masks, the two
 * samples per WebView and their cache, the frame-level reuse of the last
 * masks (and when it fails open), and that nothing ever waits on the main
 * thread. Robolectric's WebView runs no script, so a fake evaluator answers
 * for the page.
 */
@RunWith(RobolectricTestRunner::class)
class WebViewMaskScannerTest {
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private val decor: View get() = activity.window.decorView

    private var clock = 0L
    /** Every script run, in order, with the WebView it ran in. */
    private val runs = ArrayList<Pair<WebView, String>>()
    /** How the page answers a script (default: an empty page). */
    private var page: (WebView, String, (String?) -> Unit) -> Unit = { _, _, cb -> cb(answer()) }

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = FrameLayout(activity)
        activity.setContentView(root)
    }

    private fun scanner(awaitBudgetMs: Long = 300) = WebViewMaskScanner(
        evaluate = { w, js, cb -> runs.add(w to js); page(w, js, cb) },
        now = { clock },
        awaitBudgetMs = awaitBudgetMs,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Past every sample's timeout. */
    private fun timeOut() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(401))

    private fun webView(left: Int, top: Int, w: Int = 300, h: Int = 150): WebView {
        val wv = LaidOutWebView(activity)
        root.addView(wv, FrameLayout.LayoutParams(w, h).also { it.leftMargin = left; it.topMargin = top })
        return wv
    }

    /** A box at ([l], [t], [r], [b]) in [wv]'s own pixels, on screen. */
    private fun onScreen(wv: WebView, l: Int, t: Int, r: Int, b: Int): Rect {
        val loc = IntArray(2).also { wv.getLocationOnScreen(it) }
        return Rect(loc[0] + l, loc[1] + t, loc[0] + r, loc[1] + b)
    }

    /** A screen rect in a frame's coordinates: less the window's top-left
     *  on screen (the decor's place, before any adjustPan scroll). */
    private fun inFrame(r: Rect): Rect {
        val onScreen = IntArray(2).also { decor.getLocationOnScreen(it) }
        val inWindow = IntArray(2).also { decor.getLocationInWindow(it) }
        return Rect(r).apply { offset(-(onScreen[0] - inWindow[0]), -(onScreen[1] - inWindow[1])) }
    }

    /** What evaluateJavascript hands back for a script answering [boxes]. */
    private fun answer(vararg boxes: Rect): String =
        JSONObject.quote(JSONArray(boxes.map { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) }).toString())

    private fun answering(vararg boxes: Rect) {
        page = { _, _, cb -> cb(answer(*boxes)) }
    }

    private fun silent() {
        page = { _, _, _ -> }
    }

    private fun solid(vararg rects: Rect) = rects.map { MaskRect(it, ReplayMaskStyle.OVERLAY) }

    private fun id(wv: WebView) = System.identityHashCode(wv)

    // ── Script answers ───────────────────────────────────────────────

    @Test
    fun anAnswerParsesToItsBoxes() {
        assertEquals(listOf(Rect(10, 20, 110, 60)), WebViewMaskScanner.parse("\"[[10,20,110,60]]\""))
        assertEquals("an unquoted array too", listOf(Rect(1, 2, 3, 4)), WebViewMaskScanner.parse("[[1,2,3,4]]"))
    }

    @Test
    fun swappedCornersAreNormalisedAndFractionsRoundedOutward() {
        assertEquals(listOf(Rect(10, 20, 110, 60)), WebViewMaskScanner.parse("\"[[110,60,10,20]]\""))
        assertEquals(listOf(Rect(10, 20, 111, 61)), WebViewMaskScanner.parse("\"[[10.5,20.5,110.2,60.2]]\""))
    }

    @Test
    fun noAnswerParsesToNothing() {
        for (none in listOf(null, "", "\"", "null", "\"null\"", "x", "\"[[1,2\"", "42")) {
            assertTrue("<$none>", WebViewMaskScanner.parse(none).isEmpty())
        }
    }

    @Test
    fun aMalformedEntryIsSkippedAndTheOthersKept() {
        val raw = JSONObject.quote("""[[10,20,110,60],[1,"a"],[0,0,5,5]]""")
        assertEquals(listOf(Rect(10, 20, 110, 60), Rect(0, 0, 5, 5)), WebViewMaskScanner.parse(raw))
    }

    // ── Layout shifts ────────────────────────────────────────────────

    @Test
    fun samplesDifferWhenTheirCountsDiffer() {
        assertTrue(WebViewMaskScanner.detectLayoutShift(listOf(Rect(0, 0, 10, 10)), emptyList(), 2.75f))
        assertFalse(WebViewMaskScanner.detectLayoutShift(emptyList(), emptyList(), 2.75f))
    }

    @Test
    fun samplesDifferWhenTheirBoxesOverlapLessThanNinetyPercent() {
        val a = listOf(Rect(0, 0, 100, 100))
        assertFalse("IoU 0.95", WebViewMaskScanner.detectLayoutShift(a, listOf(Rect(0, 0, 95, 100)), 2.75f))
        assertTrue("IoU 0.85", WebViewMaskScanner.detectLayoutShift(a, listOf(Rect(0, 0, 85, 100)), 2.75f))
    }

    @Test
    fun samplesDifferWhenTheirTopsMoveSixDp() {
        // 6dp at density 2.75 is 16.5px. Tall boxes keep the IoU above 0.9.
        val a = listOf(Rect(0, 0, 100, 1000))
        assertFalse("16px", WebViewMaskScanner.detectLayoutShift(a, listOf(Rect(0, 16, 100, 1016)), 2.75f))
        assertTrue("17px", WebViewMaskScanner.detectLayoutShift(a, listOf(Rect(0, 17, 100, 1017)), 2.75f))
    }

    @Test
    fun theBoundingBoxSpansNegativeCoordinates() {
        val box = WebViewMaskScanner.bounds(listOf(Rect(-50, -40, -10, -5), Rect(-30, -60, -20, -50)))
        assertEquals(-50f, box.left); assertEquals(-60f, box.top)
        assertEquals(-10f, box.right); assertEquals(-5f, box.bottom)
    }

    // ── Per-WebView cache ────────────────────────────────────────────

    @Test
    fun cachedRectsLastOneSecond() {
        val s = scanner()
        s.cachePut(1, listOf(Rect(0, 0, 10, 10)))
        clock = 999
        assertEquals(listOf(Rect(0, 0, 10, 10)), s.cached(1))
        clock = 1001
        assertNull(s.cached(1))
    }

    @Test
    fun aSixthWebViewEvictsTheOldest() {
        val s = scanner()
        for (i in 1..6) s.cachePut(i, listOf(Rect(i, i, i + 1, i + 1)))
        assertNull(s.cached(1))
        for (i in 2..6) assertEquals(listOf(Rect(i, i, i + 1, i + 1)), s.cached(i))
    }

    // ── One frame's scan ─────────────────────────────────────────────

    @Test
    fun noWebViewsRunNoScript() {
        assertTrue(scanner().begin(decor, emptyList(), false)(300).isEmpty())
        assertTrue(runs.isEmpty())
    }

    @Test
    fun twoAgreeingSamplesGiveTheBoxesInDecorCoordinatesAlwaysSolid() {
        val wv = webView(10, 20)
        idle()
        val box = onScreen(wv, 5, 5, 105, 45)
        answering(box)

        val masks = scanner().begin(decor, listOf(wv), false)(300)

        assertEquals(solid(inFrame(box)), masks)
        assertEquals("two samples", 2, runs.size)
    }

    @Test
    fun theScriptCarriesTheWebViewsScreenOriginTheClassAndTheSwitch() {
        val wv = webView(10, 20)
        idle()
        val loc = IntArray(2).also { wv.getLocationOnScreen(it) }

        scanner().begin(decor, listOf(wv), true)(300)

        val js = runs.first().second
        assertTrue(js.contains("visualViewport"))
        assertTrue(js.trimEnd().endsWith("(${loc[0]}, ${loc[1]}, true, \"rr-block\")"))
        assertEquals("rr-block", WebViewMaskScanner.MANUAL_CLASS)
        assertTrue(WebViewMaskScanner.script(12, 34, false, "rr-block").trimEnd().endsWith("(12, 34, false, \"rr-block\")"))
    }

    @Test
    fun masksAreClippedToThePartOfTheWebViewThatShows() {
        val wv = webView(0, 0, 300, 150)
        idle()
        // Reaches 250px below a 150px-tall WebView.
        val box = onScreen(wv, 10, 100, 110, 400)
        answering(box)

        val masks = scanner().begin(decor, listOf(wv), false)(300)

        val shown = onScreen(wv, 0, 0, 300, 150)
        assertEquals(solid(inFrame(Rect(box).apply { assertTrue(intersect(shown)) })), masks)
    }

    @Test
    fun aWebViewThatShowsNothingRunsNoScript() {
        val wv = webView(0, 0).apply { visibility = View.INVISIBLE }
        idle()
        answering(onScreen(wv, 0, 0, 10, 10))

        assertTrue(scanner().begin(decor, listOf(wv), false)(300).isEmpty())
        assertTrue(runs.isEmpty())
    }

    @Test
    fun anUnansweredWebViewUsesItsFreshCacheElseNothing() {
        val a = webView(0, 0)
        val b = webView(0, 200)
        idle()
        val boxA = onScreen(a, 1, 1, 50, 50)
        val boxB = onScreen(b, 1, 1, 50, 50)
        page = { w, _, cb -> cb(answer(if (w === a) boxA else boxB)) }
        val s = scanner()
        s.begin(decor, listOf(a, b), false)(300)

        // b stops answering; a still does.
        page = { w, _, cb -> if (w === a) cb(answer(boxA)) }
        clock = 500
        val fresh = s.begin(decor, listOf(a, b), false)
        timeOut()
        assertEquals(setOf(inFrame(boxA), inFrame(boxB)), fresh(0).map { it.rect }.toSet())

        clock = 2_000
        val stale = s.begin(decor, listOf(a, b), false)
        timeOut()
        assertEquals("b's cache is stale: nothing for b", solid(inFrame(boxA)), stale(0))
    }

    @Test
    fun aLayoutShiftBetweenSamplesMasksBothSamplesAndTheFreshCache() {
        val wv = webView(0, 0)
        idle()
        val before = onScreen(wv, 0, 0, 100, 40)
        val first = onScreen(wv, 0, 60, 100, 100)
        val second = listOf(onScreen(wv, 0, 0, 10, 10), onScreen(wv, 0, 50, 10, 60))
        val moving = ArrayDeque(listOf(answer(first), answer(*second.toTypedArray())))
        val s = scanner()
        page = { _, _, cb -> cb(moving.removeFirst()) }

        // No cache yet: the page moved between the samples, and the frame may
        // show either position, so both are masked. Sample 1 is cached.
        assertEquals(solid(*(listOf(first) + second).map(::inFrame).toTypedArray()), s.begin(decor, listOf(wv), false)(300))
        assertEquals(listOf(first), s.cached(id(wv)))

        // A fresh cache adds the last settled position.
        s.cachePut(id(wv), listOf(before))
        moving.addAll(listOf(answer(first), answer(*second.toTypedArray())))
        assertEquals(
            solid(*(listOf(before, first) + second).map(::inFrame).toTypedArray()),
            s.begin(decor, listOf(wv), false)(300),
        )
        assertEquals("a fresh cache is kept", listOf(before), s.cached(id(wv)))
    }

    @Test
    fun aBoxThatAppearsInTheSecondSampleIsMasked() {
        // Sample 1 finds nothing, sample 2 a box: with no cache, the empty
        // first sample used to win.
        val wv = webView(0, 0)
        idle()
        val box = onScreen(wv, 10, 10, 60, 30)
        val answers = ArrayDeque(listOf(answer(), answer(box)))
        page = { _, _, cb -> cb(answers.removeFirst()) }

        assertEquals(solid(inFrame(box)), scanner().begin(decor, listOf(wv), false)(300))
    }

    @Test
    fun aPageThatAnswersNoResultIsAFailedSampleNotAnEmptyPage() {
        // JavaScript off in that WebView: the script never runs, so there is
        // no result at all. That says nothing about the page: the fresh
        // cache is used and kept, and the last frame's masks are reused.
        val wv = webView(0, 0)
        idle()
        val box = onScreen(wv, 0, 0, 50, 50)
        answering(box)
        val s = scanner()
        s.begin(decor, listOf(wv), false)(300)

        for (none in listOf(null, "null")) {
            page = { _, _, cb -> cb(none) }
            clock += 100
            assertEquals("<$none>", solid(inFrame(box)), s.begin(decor, listOf(wv), false)(300))
            assertEquals("<$none> kept the cache", listOf(box), s.cached(id(wv)))
        }
        clock = 600_000
        assertEquals("the last frame's masks outlast the cache", solid(inFrame(box)), s.begin(decor, listOf(wv), false)(300))
    }

    @Test
    fun anEvaluatorThatThrowsFallsBackWithoutCrashing() {
        val wv = webView(0, 0)
        idle()
        page = { _, _, _ -> throw IllegalStateException("WebView methods on the wrong thread") }

        assertTrue(scanner().begin(decor, listOf(wv), false)(300).isEmpty())

        val cachedBox = onScreen(wv, 0, 0, 20, 20)
        val s = scanner()
        s.cachePut(id(wv), listOf(cachedBox))
        assertEquals(solid(inFrame(cachedBox)), s.begin(decor, listOf(wv), false)(300))
    }

    @Test
    fun anAnswerAfterTheTimeoutIsIgnored() {
        val wv = webView(0, 0)
        idle()
        val held = ArrayList<(String?) -> Unit>()
        page = { _, _, cb -> held.add(cb) }
        val s = scanner()

        val await = s.begin(decor, listOf(wv), false)
        timeOut()
        assertTrue(await(0).isEmpty())

        held.single()(answer(onScreen(wv, 0, 0, 20, 20)))
        assertEquals("no second sample", 1, runs.size)
        assertNull("nothing cached", s.cached(id(wv)))
    }

    // ── Across frames ────────────────────────────────────────────────

    @Test
    fun aFrameWhoseScanDoesNotAnswerReusesTheLastMasksHoweverOld() {
        val wv = webView(0, 0)
        idle()
        val box = onScreen(wv, 0, 0, 50, 50)
        answering(box)
        val s = scanner()
        s.begin(decor, listOf(wv), false)(300)

        clock = 600_000 // far past the per-WebView cache
        silent()
        assertEquals(solid(inFrame(box)), s.begin(decor, listOf(wv), false)(20))
    }

    @Test
    fun aScanThatFindsNothingReusesTheLastMasksForTheSameWebViews() {
        val wv = webView(0, 0)
        idle()
        val box = onScreen(wv, 0, 0, 50, 50)
        answering(box)
        val s = scanner()
        s.begin(decor, listOf(wv), false)(300)

        answering()
        assertEquals(solid(inFrame(box)), s.begin(decor, listOf(wv), false)(300))
    }

    @Test
    fun reusedMasksAreKeptOnlyWhereTheirWebViewsStillShow() {
        // A WebView in a native scroll view: its password box was found, then
        // the page stops answering while the user scrolls the WebView away.
        val scroll = android.widget.ScrollView(activity)
        root.addView(scroll, FrameLayout.LayoutParams(300, 200))
        val column = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        scroll.addView(column, android.view.ViewGroup.LayoutParams(300, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        val wv = LaidOutWebView(activity)
        column.addView(wv, android.widget.LinearLayout.LayoutParams(300, 150))
        column.addView(View(activity), android.widget.LinearLayout.LayoutParams(300, 600))
        idle()
        val box = onScreen(wv, 0, 0, 100, 140)
        answering(box)
        val s = scanner()
        assertEquals(solid(inFrame(box)), s.begin(decor, listOf(wv), false)(300))

        silent()
        clock = 600_000
        scroll.scrollTo(0, 60)
        idle()
        // 90px of the WebView still show, at the top of the scroll view: the
        // old box (where the frame it was found in showed it) is kept only
        // where it overlaps them.
        val shown = Rect().also { assertTrue(wv.getGlobalVisibleRect(it)) }
        val onScreenOrigin = IntArray(2).also { decor.getLocationOnScreen(it) }
        shown.offset(onScreenOrigin[0], onScreenOrigin[1])
        assertEquals(solid(inFrame(Rect(box).apply { assertTrue(intersect(shown)) })), s.begin(decor, listOf(wv), false)(20))

        // Scrolled fully away: nothing of the WebView shows, so nothing is
        // painted over the native views now in its place.
        timeOut()
        scroll.scrollTo(0, 400)
        idle()
        assertTrue(s.begin(decor, listOf(wv), false)(20).isEmpty())
    }

    @Test
    fun aFrameThatCantStartItsOwnScanGetsWhatTheOneOutHasFound() {
        val a = webView(0, 0)
        val b = webView(0, 200)
        idle()
        val boxA = onScreen(a, 1, 1, 50, 50)
        // a answers, b never does: the scan stays out.
        page = { w, _, cb -> if (w === a) cb(answer(boxA)) }
        val s = scanner()
        s.begin(decor, listOf(a, b), false)
        val runs0 = runs.size

        assertEquals(solid(inFrame(boxA)), s.begin(decor, listOf(a, b), false)(0))
        assertEquals("no new script", runs0, runs.size)
        assertTrue("a different set of WebViews gets nothing from it", s.begin(decor, listOf(a), false)(0).isEmpty())
    }

    @Test
    fun withTheWindowPannedMasksLandWhereTheFrameShowsTheField() {
        // The keyboard pans the window: the page reports screen pixels as
        // drawn, moved up by the pan; the frame (the window's surface) shows
        // the field there too.
        val wv = webView(0, 150)
        idle()
        panWindow(activity, 100)
        try {
            val box = onScreen(wv, 10, 20, 110, 60)
            answering(box)

            val masks = scanner().begin(decor, listOf(wv), false)(300)

            val shownAt = IntArray(2).also { wv.getLocationInWindow(it) }
            assertEquals("not clipped away", solid(Rect(shownAt[0] + 10, shownAt[1] + 20, shownAt[0] + 110, shownAt[1] + 60)), masks)
            assertEquals(50 + 20, masks.single().rect.top - PrivacyRegistry.locationInDecor(root)[1])
        } finally {
            panWindow(activity, 0)
        }
    }

    @Test
    fun aChangedSetOfWebViewsFailsOpenAndDropsTheLastMasks() {
        val wv = webView(0, 0)
        idle()
        val box = onScreen(wv, 0, 0, 50, 50)
        answering(box)
        val s = scanner()
        s.begin(decor, listOf(wv), false)(300)

        clock = 600_000
        silent()
        val other = webView(0, 200)
        idle()
        assertTrue("a different page set: nothing to reuse", s.begin(decor, listOf(wv, other), false)(20).isEmpty())

        // Back to the first set: the dropped masks don't come back.
        timeOut()
        assertTrue(s.begin(decor, listOf(wv), false)(20).isEmpty())
    }

    @Test
    fun noNewScriptRunsWhileTheLastScanIsOut() {
        val wv = webView(0, 0)
        idle()
        silent()
        val s = scanner()

        s.begin(decor, listOf(wv), false)
        assertEquals(1, runs.size)
        assertTrue(s.begin(decor, listOf(wv), false)(0).isEmpty())
        assertEquals("no new script while one is out", 1, runs.size)

        timeOut()
        s.begin(decor, listOf(wv), false)
        assertEquals("the next frame scans again", 2, runs.size)
    }

    @Test
    fun theWaitNeverOutlastsItsBudgetOffTheMainThreadWithMainStalled() {
        val wv = webView(0, 0)
        idle()
        silent()

        for ((budget, cap) in listOf(300L to 300L, 10_000L to 100L)) {
            runs.clear()
            val await = scanner(awaitBudgetMs = cap).begin(decor, listOf(wv), false)
            var took = -1L
            var out: List<MaskRect>? = null
            val t = Thread({
                val t0 = System.nanoTime()
                out = await(budget)
                took = (System.nanoTime() - t0) / 1_000_000
            }, "replay-pixelcopy")
            t.start()
            t.join(5_000) // the main looper runs nothing meanwhile
            // The upper bound is loose: a busy machine can wake the thread late.
            assertTrue("budget ${minOf(budget, cap)}ms took ${took}ms", took >= minOf(budget, cap) - 5 && took < minOf(budget, cap) + 250)
            assertTrue(out!!.isEmpty())
        }
    }

    @Test
    fun clearForgetsTheCacheAndTheLastMasks() {
        val wv = webView(0, 0)
        idle()
        answering(onScreen(wv, 0, 0, 50, 50))
        val s = scanner()
        s.begin(decor, listOf(wv), false)(300)

        s.clear()

        assertNull(s.cached(id(wv)))
        silent()
        assertTrue(s.begin(decor, listOf(wv), false)(20).isEmpty())
    }
}
