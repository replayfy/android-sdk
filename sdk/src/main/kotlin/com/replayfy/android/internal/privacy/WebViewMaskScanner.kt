package com.replayfy.android.internal.privacy

import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import com.replayfy.android.MaskRect
import com.replayfy.android.ReplayMaskStyle
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Masks the password inputs of the pages shown in WebViews, which the View
 * walk can't see into. On every captured frame a script runs in each WebView
 * the walk found and reports where the page's sensitive elements are, as the
 * reference tracker does by default:
 *
 *  - every `input` whose type is password;
 *  - every element with the class [MANUAL_CLASS] ("rr-block", the web SDK's
 *    block class, so one class hides an element on the web and in an app);
 *  - with occludeAllTextFields (the app's or the dashboard's): every textual
 *    input, textarea, contenteditable and `.input-field` element.
 *
 * Top document only: iframes and shadow DOM are not reached, as in the
 * reference. The script only reads the page: it writes nothing to the DOM
 * and leaves no globals behind, so the page and everything running in it
 * behave exactly as without the SDK.
 *
 * Each WebView is sampled twice in a row. Sample 1 failing (no answer in
 * [sampleTimeoutMs], an error, or no result at all: the script didn't run,
 * as when the WebView's JavaScript is off) uses that WebView's cached rects
 * while they are fresh ([cacheTtlMs]), else nothing. The two samples
 * disagreeing (a layout shift: page scrolling, content moving) masks both
 * samples' rects and the fresh cached ones: the frame may show any of those
 * positions. Every rect is clipped to the part of the WebView that shows.
 *
 * Per frame: when the scan hasn't answered within the frame's budget, or
 * found nothing, the last frame's WebView masks are reused while the set of
 * WebViews on screen is unchanged, with no age limit, wherever one of those
 * WebViews still shows. Otherwise the frame has no WebView masks: like the
 * reference, a scan that can't answer fails open rather than blanking the
 * page.
 *
 * Coordinates: the script reports screen pixels as drawn, which count the
 * window's adjustPan scroll (the keyboard pushing the content up). A frame
 * is the window's surface, so its masks are those rects less the window's
 * top-left on screen.
 *
 * Threads: [begin] runs on the main thread, where the script runs and
 * answers. The awaiter it returns runs on the PixelCopy thread, NEVER on
 * main: waiting there would block the very answers it waits for.
 *
 * One instance per session; [clear] drops what it remembers.
 */
internal class WebViewMaskScanner(
    private val evaluate: (WebView, String, (String?) -> Unit) -> Unit =
        { w, js, cb -> w.evaluateJavascript(js) { cb(it) } },
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val sampleTimeoutMs: Long = 400,
    private val cacheTtlMs: Long = 1000,
    private val cacheMax: Int = 5,
    private val awaitBudgetMs: Long = 300,
) {
    internal companion object {
        /** Elements with this class are always masked: rrweb's (and so the
         *  web SDK's) block class, carrying no product name. */
        const val MANUAL_CLASS = "rr-block"

        /** Two samples whose bounding boxes overlap less than this differ. */
        private const val SHIFT_MIN_IOU = 0.9f

        /** Two samples whose tops are this far apart (dp) differ. */
        private const val SHIFT_TOP_DP = 6f

        private val NONE: (Long) -> List<MaskRect> = { emptyList() }

        /** One warning per process for a WebView whose script can't run. */
        private val scriptOffWarned = AtomicBoolean(false)

        /**
         * The read-only page script, for a WebView whose top-left is at
         * ([ox], [oy]) on screen. It answers a JSON string of
         * `[left, top, right, bottom]` boxes in screen pixels: an element's
         * CSS box is taken relative to the visual viewport (pinch zoom), then
         * scaled by the zoom and the device pixel ratio. Edges are rounded
         * outward and empty boxes are left out.
         */
        fun script(ox: Int, oy: Int, all: Boolean, cls: String): String = """
            (function (ox, oy, all, cls) {
              var view = window.visualViewport, ratio = window.devicePixelRatio || 1;
              var shiftX = 0, shiftY = 0, zoom = 1;
              if (view) { shiftX = view.offsetLeft || 0; shiftY = view.offsetTop || 0; zoom = view.scale || 1; }
              var k = zoom * ratio, boxes = [];
              var textual = /^(|text|email|number|search|tel|url|password|datetime|datetime-local|month|week|time|color)$/;
              function box(el) {
                var b = el.getBoundingClientRect();
                if (!(b.width > 0 && b.height > 0)) return;
                boxes.push([
                  Math.floor((b.left - shiftX) * k + ox), Math.floor((b.top - shiftY) * k + oy),
                  Math.ceil((b.right - shiftX) * k + ox), Math.ceil((b.bottom - shiftY) * k + oy)
                ]);
              }
              function scan(found, keep) {
                for (var i = 0; i < found.length; i++) {
                  try { if (!keep || keep(found[i])) box(found[i]); } catch (e) {}
                }
              }
              try { scan(document.getElementsByClassName(cls)); } catch (e) {}
              try {
                scan(document.getElementsByTagName('input'), function (el) {
                  var type = String(el.type || '').toLowerCase();
                  return type === 'password' || (all && textual.test(type));
                });
              } catch (e) {}
              if (all) { try { scan(document.querySelectorAll('textarea, [contenteditable], .input-field')); } catch (e) {} }
              return JSON.stringify(boxes);
            })($ox, $oy, $all, ${JSONObject.quote(cls)})
        """.trimIndent()

        /**
         * The rects in a script answer (evaluateJavascript hands back the
         * script's JSON string, JSON-quoted). Nothing for no answer. Corners
         * are normalised and edges rounded outward; a malformed entry is
         * skipped, keeping the others.
         */
        fun parse(result: String?): List<Rect> {
            if (result == null || result.length <= 1 || result == "null" || result == "\"null\"") return emptyList()
            val entries = try {
                when (val v = JSONTokener(result).nextValue()) {
                    is String -> JSONArray(v)
                    is JSONArray -> v
                    else -> return emptyList()
                }
            } catch (e: Throwable) {
                return emptyList()
            }
            val out = ArrayList<Rect>(entries.length())
            for (i in 0 until entries.length()) {
                try {
                    val e = entries.getJSONArray(i)
                    val l = e.getDouble(0); val t = e.getDouble(1)
                    val r = e.getDouble(2); val b = e.getDouble(3)
                    out.add(Rect(
                        floor(min(l, r)).toInt(), floor(min(t, b)).toInt(),
                        ceil(max(l, r)).toInt(), ceil(max(t, b)).toInt(),
                    ))
                } catch (e: Throwable) { /* skip this entry */ }
            }
            return out
        }

        /** Whether two samples of one page disagree: a different number of
         *  boxes, bounding boxes overlapping less than [SHIFT_MIN_IOU], or
         *  tops [SHIFT_TOP_DP] or more apart. */
        fun detectLayoutShift(a: List<Rect>, b: List<Rect>, density: Float): Boolean {
            if (a.size != b.size) return true
            if (a.isEmpty()) return false
            val ba = bounds(a)
            val bb = bounds(b)
            if (iou(ba, bb) < SHIFT_MIN_IOU) return true
            return abs(ba.top - bb.top) >= SHIFT_TOP_DP * density
        }

        /** The box around [rects]. Seeded with ±MAX_VALUE (MIN_VALUE is the
         *  smallest positive float, not the most negative). */
        fun bounds(rects: List<Rect>): RectF {
            val out = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (r in rects) {
                out.left = min(out.left, r.left.toFloat())
                out.top = min(out.top, r.top.toFloat())
                out.right = max(out.right, r.right.toFloat())
                out.bottom = max(out.bottom, r.bottom.toFloat())
            }
            return out
        }

        /** Intersection over union of two boxes. */
        fun iou(a: RectF, b: RectF): Float {
            val w = min(a.right, b.right) - max(a.left, b.left)
            val h = min(a.bottom, b.bottom) - max(a.top, b.top)
            val inter = if (w > 0f && h > 0f) w * h else 0f
            val union = a.width() * a.height() + b.width() * b.height() - inter
            if (union <= 0f) return if (a == b) 1f else 0f
            return inter / union
        }
    }

    /** One script answer: its rects (screen px) and when it came. */
    private class Sample(val rects: List<Rect>, val at: Long)

    /** A WebView's last good rects (screen px), for [cacheTtlMs]. */
    private class Cached(val rects: List<Rect>, val at: Long)

    private val lock = Any()

    // Guarded by `lock`. Tier 1: per WebView (identity hash), oldest first.
    private val cache = LinkedHashMap<Int, Cached>()
    // Tier 2: the last scan that found something, and the WebViews it saw.
    private var lastFrameRects: List<Rect>? = null
    private var lastIds: Set<Int>? = null
    // The scan still waiting on answers. Scans never overlap.
    private var inFlight: Scan? = null

    /** One frame's scan of every WebView in it. [areas]: where those
     *  WebViews show (screen px), or null when that isn't known. */
    private inner class Scan(val ids: Set<Int>, count: Int, private val areas: List<Rect>?) {
        private val latch = CountDownLatch(count)
        private val results = AtomicReferenceArray<List<Rect>?>(count)

        /** WebView [i] is done with [rects] (screen px, clipped). Only its
         *  first answer counts. */
        fun resolve(i: Int, rects: List<Rect>) {
            if (!results.compareAndSet(i, null, rects)) return
            latch.countDown()
            if (latch.count == 0L) finish()
        }

        /** The rects of the WebViews done so far. */
        fun found(): List<Rect> {
            val out = ArrayList<Rect>()
            for (i in 0 until results.length()) results.get(i)?.let { out.addAll(it) }
            return out
        }

        /** Every WebView is done: remember what it found for the frames
         *  after, and let the next scan start. */
        private fun finish() {
            val found = found()
            synchronized(lock) {
                if (found.isNotEmpty()) {
                    lastFrameRects = found
                    lastIds = ids
                }
                if (inFlight === this) inFlight = null
            }
        }

        /** Wait up to [budgetMs] for the answers. Off the main thread.
         *  [origin]: the window's top-left on screen. */
        fun await(budgetMs: Long, origin: IntArray): List<MaskRect> {
            val done = try {
                latch.await(min(budgetMs, awaitBudgetMs).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            val found = found()
            val rects = if (done && found.isNotEmpty()) found else withLastFrame(ids, found, areas)
            return toFrame(rects, origin)
        }
    }

    /**
     * Start this frame's scan of [webViews] (the ones the privacy walk just
     * met in [decor]'s window) and return the awaiter that hands back its
     * masks in the frame's coordinates (the window's surface). MAIN THREAD
     * ONLY. No WebViews costs nothing: no script runs.
     */
    fun begin(decor: View, webViews: List<WebView>, occludeAll: Boolean): (Long) -> List<MaskRect> {
        if (webViews.isEmpty()) return NONE
        // The decor as drawn on screen (after any adjustPan scroll), and the
        // window's own top-left on screen: a frame's origin.
        val onScreen = IntArray(2).also { decor.getLocationOnScreen(it) }
        val inWindow = IntArray(2).also { decor.getLocationInWindow(it) }
        val origin = intArrayOf(onScreen[0] - inWindow[0], onScreen[1] - inWindow[1])
        val ids = webViews.mapTo(HashSet()) { System.identityHashCode(it) }

        // Where each WebView shows, on screen as drawn: a visible rect is in
        // the decor's coordinates, which the decor's place on screen maps
        // there. Null: it shows nothing. A lookup that throws leaves the
        // areas unknown, so older masks are then reused unclipped.
        val clips = arrayOfNulls<Rect>(webViews.size)
        val unknown = BooleanArray(webViews.size)
        webViews.forEachIndexed { i, wv ->
            try {
                val r = Rect()
                if (wv.isShown && wv.getGlobalVisibleRect(r)) clips[i] = r.apply { offset(onScreen[0], onScreen[1]) }
            } catch (e: Throwable) {
                unknown[i] = true
            }
        }
        val areas = if (unknown.any { it }) null else clips.filterNotNull()

        val scan = synchronized(lock) {
            if (inFlight != null) null else Scan(ids, webViews.size, areas).also { inFlight = it }
        }
        if (scan == null) {
            // The last scan is still out: no new script. This frame gets what
            // that scan has found so far, when it covers the same WebViews,
            // and the last frame's masks, if they still apply.
            val out = synchronized(lock) { inFlight }?.takeIf { it.ids == ids }
            return { _ -> toFrame(withLastFrame(ids, out?.found().orEmpty(), areas), origin) }
        }

        webViews.forEachIndexed { i, wv ->
            val id = System.identityHashCode(wv)
            try {
                val clip = clips[i]
                when {
                    unknown[i] -> scan.resolve(i, cached(id).orEmpty())
                    // A WebView that shows nothing needs no mask, nor a script.
                    clip == null -> scan.resolve(i, emptyList())
                    else -> {
                        val density = wv.resources.displayMetrics.density
                        scanOne(WeakReference(wv), id, density, occludeAll) { rects -> scan.resolve(i, clipTo(rects, clip)) }
                    }
                }
            } catch (e: Throwable) {
                scan.resolve(i, cached(id).orEmpty())
            }
        }
        return { budgetMs -> scan.await(budgetMs, origin) }
    }

    /** Forget everything: the session is over. */
    fun clear() {
        synchronized(lock) {
            cache.clear()
            lastFrameRects = null
            lastIds = null
            inFlight = null
        }
    }

    /**
     * Two samples of one WebView, then [done] once with its rects. Holds the
     * WebView only weakly: a WebView gone meanwhile just fails its sample.
     */
    private fun scanOne(
        ref: WeakReference<WebView>, id: Int, density: Float, occludeAll: Boolean, done: (List<Rect>) -> Unit,
    ) {
        val settled = AtomicBoolean(false)
        val finish: (List<Rect>) -> Unit = { if (settled.compareAndSet(false, true)) done(it) }
        // Anything unexpected: the cached rects, else nothing.
        val fail = { finish(cached(id).orEmpty()) }
        try {
            sample(ref, occludeAll) { first ->
                try {
                    if (first == null) {
                        fail()
                    } else {
                        val midpoint = now()
                        sample(ref, occludeAll) { second ->
                            try {
                                if (second != null && detectLayoutShift(first.rects, second.rects, density)) {
                                    // The page moved between the samples: the
                                    // frame may show either position, or the
                                    // last cached one. Mask them all. As in
                                    // the reference, sample 1 is cached only
                                    // when nothing fresh is.
                                    val before = cached(id)
                                    if (before == null) cachePut(id, first.rects)
                                    finish((before.orEmpty() + first.rects + second.rects).distinct())
                                } else {
                                    // The sample taken closest to the midpoint.
                                    val pick = if (second == null || abs(first.at - midpoint) <= abs(second.at - midpoint)) first else second
                                    cachePut(id, pick.rects)
                                    finish(pick.rects)
                                }
                            } catch (e: Throwable) { fail() }
                        }
                    }
                } catch (e: Throwable) { fail() }
            }
        } catch (e: Throwable) { fail() }
    }

    /** Run the script in the WebView once; [then] gets its answer, or null
     *  when it fails, doesn't answer within [sampleTimeoutMs], or answers no
     *  result at all. An answer that comes after the timeout is ignored. */
    private fun sample(ref: WeakReference<WebView>, occludeAll: Boolean, then: (Sample?) -> Unit) {
        val wv = ref.get() ?: return then(null)
        val settled = AtomicBoolean(false)
        val timeout = Runnable { if (settled.compareAndSet(false, true)) then(null) }
        try {
            val loc = IntArray(2).also { wv.getLocationOnScreen(it) }
            main.postDelayed(timeout, sampleTimeoutMs)
            evaluate(wv, script(loc[0], loc[1], occludeAll, MANUAL_CLASS)) { result ->
                if (settled.compareAndSet(false, true)) {
                    main.removeCallbacks(timeout)
                    if (result == null || result == "null") {
                        // The script always answers a string, so no result
                        // means it didn't run (JavaScript off in that
                        // WebView): a failed sample, not an empty page.
                        warnIfScriptOff(ref)
                        then(null)
                    } else {
                        then(Sample(parse(result), now()))
                    }
                }
            }
        } catch (e: Throwable) {
            if (settled.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                then(null)
            }
        }
    }

    /** [found] plus the last frame's rects when the same WebViews are on
     *  screen, kept where one of them shows ([areas]; all of them when
     *  that isn't known): a mask outside every WebView can't hide a page,
     *  only the native views there. Otherwise the last frame's rects no
     *  longer apply and are dropped. */
    private fun withLastFrame(ids: Set<Int>, found: List<Rect>, areas: List<Rect>?): List<Rect> = synchronized(lock) {
        val last = lastFrameRects
        if (lastIds == ids && last != null) {
            (found + if (areas == null) last else clipTo(last, areas)).distinct()
        } else {
            lastFrameRects = null
            lastIds = null
            found
        }
    }

    /** Screen rects to frame masks (less the window's top-left on screen,
     *  [origin]), always solid. */
    private fun toFrame(rects: List<Rect>, origin: IntArray): List<MaskRect> =
        rects.map { r ->
            MaskRect(Rect(r).apply { offset(-origin[0], -origin[1]) }, ReplayMaskStyle.OVERLAY)
        }

    /** Warn once that a WebView answered no result while its JavaScript is
     *  off: its page can't be scanned, so its password fields go unmasked.
     *  On the main thread, where WebView settings are read. */
    private fun warnIfScriptOff(ref: WeakReference<WebView>) {
        if (scriptOffWarned.get()) return
        val off = try { ref.get()?.settings?.javaScriptEnabled == false } catch (e: Throwable) { false }
        if (off && scriptOffWarned.compareAndSet(false, true)) {
            android.util.Log.w(
                "ReplaySdk",
                "A WebView has JavaScript turned off, so its page can't be scanned for password fields " +
                    "to mask. Mask that WebView with Replay.addPrivacyView(webView).",
            )
        }
    }

    /** The parts of [rects] inside [clip]. */
    private fun clipTo(rects: List<Rect>, clip: Rect): List<Rect> =
        rects.mapNotNull { r -> Rect(r).takeIf { it.intersect(clip) } }

    /** The parts of [rects] inside any of [clips]. */
    private fun clipTo(rects: List<Rect>, clips: List<Rect>): List<Rect> =
        rects.flatMap { r -> clipTo(clips, r) }

    /** Remember [rects] as WebView [id]'s, evicting the oldest past [cacheMax]. */
    internal fun cachePut(id: Int, rects: List<Rect>) {
        synchronized(lock) {
            cache.remove(id)
            cache[id] = Cached(rects.map(::Rect), now())
            while (cache.size > cacheMax) cache.remove(cache.keys.first())
        }
    }

    /** A copy of WebView [id]'s cached rects while fresh, else null. */
    internal fun cached(id: Int): List<Rect>? = synchronized(lock) {
        val c = cache[id] ?: return null
        if (now() - c.at > cacheTtlMs) {
            cache.remove(id)
            return null
        }
        c.rects.map(::Rect)
    }
}
