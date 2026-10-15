package com.replayfy.android.internal.mobile

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import com.replayfy.android.internal.privacy.PrivacyRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Periodic screenshot capture → frames archive upload (mirrors iOS).
 *
 * A timer fires at `1/fps`, captures the current activity's decor view
 * (PixelCopy on API 26+, Canvas draw fallback otherwise), downscales,
 * JPEG-compresses, accumulates, and uploads a gzipped frames archive
 * once `batchSize` frames are collected.
 *
 * [nativeCapture] is false for hosts that push their own frames through
 * [submitFrame] (Flutter's Dart-side capture): the capture timer must then
 * never run, including on resume.
 *
 * A FLAG_SECURE window records as a solid black frame, as the reference
 * tracker records it. It is never copied or drawn: the frame is made without
 * reading a single pixel of the window.
 *
 * A frame shows the window's surface, as PixelCopy copies it: while the
 * window is panned for the keyboard (adjustPan), the content is drawn moved
 * up by the pan. [privacyRects] and the WebView masks are in that frame's
 * coordinates, and the draw fallback draws the window the same way.
 *
 * [webViewRects] starts the frame's WebView scan on the main thread, next to
 * [privacyRects], and hands back an awaiter for its masks (null: no scan).
 * The awaiter is only ever called on the PixelCopy thread, just before the
 * encode: the scan's answers arrive on the main thread, so waiting there
 * would block them.
 *
 * One instance per session, with threads of its own (upload, PixelCopy,
 * host-frame decode). They are shut down once the session is over: after
 * [stop]'s final upload, or [discard], and once no capture still under way
 * can add a frame. Otherwise every session rotation (a long background,
 * startNewSession) leaves them idle for the life of the process.
 */
class MobileScreenshots(
    private val transport: MobileUploading,
    private val activityProvider: () -> Activity?,
    private val privacyRects: () -> List<com.replayfy.android.MaskRect>,
    private val nativeCapture: Boolean = true,
    private val webViewRects: (View) -> ((Long) -> List<com.replayfy.android.MaskRect>)? = { null },
) {
    internal companion object {
        /** Solid colour of OVERLAY masks and of the whole-screen cover. */
        const val MASK_COLOR: Int = 0xFF383838.toInt()

        /** How long a frame waits for its WebView masks, at most. */
        const val WEBVIEW_WAIT_MS = 300L
    }

    private val lock = Any()
    private val pending = ArrayList<Pair<ByteArray, Long>>()
    // Lazy: a host-fed engine (Flutter) never captures natively, so it never
    // needs the main-thread timer or the PixelCopy thread.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val sender = Executors.newSingleThreadExecutor()
    // Kept apart from its Handler so the thread can be quit with the session.
    private val pixelCopyThread = lazy { HandlerThread("replay-pixelcopy").apply { start() } }
    private val pixelCopyHandler by lazy { Handler(pixelCopyThread.value.looper) }
    // Host-fed frames (submitFrame) decode here, one at a time in the order
    // they were reported. Not on `sender`: there a decode waited behind an
    // upload (up to a minute), so a pause decided what to send before frames
    // reported earlier had even been buffered. Created on the first host
    // frame; its thread exits when idle, and the pool shuts down with the
    // session.
    private val decoder = lazy {
        ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()).apply { allowCoreThreadTimeOut(true) }
    }

    // Capture timer state, guarded by `lock`. Each start bumps the generation,
    // so a tick chain from before a pause/resume ends itself instead of running
    // alongside the new one (pause and resume can come from any thread).
    private var capturing = false
    private var timerGeneration = 0

    // Upload drain state, guarded by `lock`. One drain runs at a time (on the
    // single `sender` thread) so archives reach the server in capture order.
    private var draining = false
    /** Frames captured at or before this time go out even as a partial batch
     *  (a pause, resume or stop asked for everything up to that moment);
     *  later frames wait for a full batch. 0 = full batches only. */
    private var flushThroughTs = 0L
    private var roundsLeft = 0
    /** A flush arrived while a round was in flight. If that round fails, the
     *  request still gets its own attempt (a resume right after an upload the
     *  background cut off must not be lost with it). */
    private var requestedDuringRound = false
    private val drainWaiters = ArrayList<() -> Unit>()
    /** stop() or discard() ran: the capture timer must never start again. */
    private var stopped = false
    /** discard() ran: drop everything and send nothing more. */
    private var discarded = false

    // Thread lifetime, guarded by `lock`.
    /** Captures under way: a native screenshot waiting on PixelCopy, a host
     *  frame waiting to decode. Each ends in appendFrame (or is dropped); the
     *  threads stay up until then, so a frame that lands after stop() still
     *  goes out. */
    private var capturesInFlight = 0
    /** stop()'s final upload has been asked for (on the decoder thread when
     *  one exists, behind the host frames reported before it). */
    private var finalFlushRequested = false
    /** The threads are shut down: nothing more is captured or sent. */
    private var released = false

    private var intervalMs = 333L      // 3 fps default
    private var jpegQuality = 50
    private val targetScale = 0.5f     // downscale to keep size down
    private val batchSize = 20
    private val maxBuffered = 500
    /** Archives one drain may send. Bounds a drain whose buffer refills as
     *  fast as it uploads, so it still ends. */
    private val maxDrainRounds = 30

    private inner class Tick(private val generation: Int) : Runnable {
        override fun run() {
            // Counted in the same hold as the check, so a stop() on another
            // thread can't quit the PixelCopy thread under this capture.
            val live = synchronized(lock) {
                (capturing && generation == timerGeneration).also { if (it) capturesInFlight++ }
            }
            if (!live) return
            capture()
            main.postDelayed(this, intervalMs)
        }
    }

    fun setSettings(fps: Int, quality: String) {
        val f = fps.coerceIn(1, 30)
        intervalMs = (1000.0 / f).toLong()
        jpegQuality = when (quality.lowercase()) {
            "low" -> 40; "high" -> 60; else -> 50
        }
    }

    fun start() {
        startTimer()
    }

    /** Feed an externally-captured frame (Flutter's Dart-side RepaintBoundary
     *  capture, since native PixelCopy can't read Flutter's SurfaceView) into
     *  the same encode → accumulate → upload pipeline. Decodes + re-encodes on
     *  the decoder thread so it never touches the main thread. [rects] are the
     *  occlusion regions (developer `ReplayMask` bounds, in the PNG's pixel
     *  coords) the encoder masks — Flutter has no native view tree to mask. */
    fun submitFrame(png: ByteArray, rects: List<com.replayfy.android.MaskRect>) =
        submitFrame(png, rects, cover = null)

    /** [submitFrame], with the whole frame replaced by one solid [cover]
     *  colour when given: a whole-screen occlusion, or a FLAG_SECURE window.
     *  The PNG's pixels are then never decoded, only its size is read. */
    internal fun submitFrame(png: ByteArray, rects: List<com.replayfy.android.MaskRect>, cover: Int?) {
        // Stamped when reported, not when decoded: the frame shows the screen
        // as of now, so a pause or stop that follows counts it among the frames
        // captured before it.
        val ts = System.currentTimeMillis()
        // Once the session is over (its threads gone) the frame is dropped.
        // Counted before it is queued, so the decoder and the upload that
        // sends it stay up until it lands.
        if (!captureBegan()) return
        decoder.value.execute {
            try {
                if (synchronized(lock) { discarded }) return@execute
                if (cover != null) {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    try {
                        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
                    } catch (e: Throwable) { return@execute }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@execute
                    encodeSolid(bounds.outWidth, bounds.outHeight, cover, ts)
                    return@execute
                }
                val bmp = try {
                    android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
                } catch (e: Throwable) { null } ?: return@execute
                encode(bmp, rects, ts)
            } finally {
                captureEnded()
            }
        }
    }

    /** Session end: stop capturing and upload everything buffered.
     *  [completion] runs once that upload settles. The threads are shut down
     *  after it, once no capture still under way can add a frame. */
    fun stop(completion: (() -> Unit)? = null) {
        synchronized(lock) { stopped = true }
        stopTimer()
        flushAll(final = true, completion = completion)
    }

    /** Drop pending frames WITHOUT uploading — the cancelSession counterpart to
     *  [stop] (which flushes). Stops the capture loop. The buffer is cleared at
     *  once, under the lock, so a running drain stops before its next round;
     *  the threads go once it has, and once no capture is under way. */
    fun discard() {
        val release = synchronized(lock) {
            stopped = true
            discarded = true
            pending.clear()
            releaseIfDoneLocked()
        }
        stopTimer()
        if (release) releaseThreads()
    }

    /** Suspend capture (backgrounding, an excluded screen, a host pause) and
     *  upload everything buffered — shooting an invisible window just inflates
     *  the archive. Always flushes, even when the timer never ran: a host-fed
     *  engine (Flutter) has frames buffered with no timer at all. Never
     *  discards: frames that fail to send stay queued and go out on [resume].
     *  [completion] runs once the uploads settle. */
    fun pause(completion: (() -> Unit)? = null) {
        stopTimer()
        flush(all = true, completion = completion)
    }

    /** Resume after [pause]. First push whatever the pause could not get out;
     *  then restart the capture timer, but only when this engine screenshots
     *  natively. Host-fed capture (Flutter) never starts the timer — doing so
     *  began native screenshots after the first background, without the
     *  host's occlusion rects. */
    fun resume() {
        flush(all = true)
        startTimer()
    }

    private fun startTimer() {
        if (!nativeCapture) return
        val generation = synchronized(lock) {
            // A stop() before this resume must win.
            if (stopped || capturing) return
            capturing = true
            ++timerGeneration
        }
        main.post(Tick(generation))
    }

    private fun stopTimer() {
        synchronized(lock) { capturing = false }
    }

    /** One screenshot, counted in by [Tick] (tests count it in with
     *  [captureBegan]). Ends with [captureEnded] exactly once: here, or in the
     *  PixelCopy callback when the copy was handed off. */
    internal fun capture() {
        var handedOff = false
        try {
            val activity = activityProvider() ?: return
            val view = activity.window?.decorView ?: return
            val w = view.width; val h = view.height
            if (w <= 0 || h <= 0) return

            // FLAG_SECURE: a black frame, before any copy, draw or privacy
            // lookup. The window's pixels never enter the process.
            if (PrivacyRegistry.isSecureWindow(activity.window)) {
                handedOff = true
                postSolidFrame(w, h, Color.BLACK) { captureEnded() }
                return
            }

            // Resolve sensitive rects HERE, on the main thread — `capture` always
            // runs on the main looper, whereas `encode` runs on the PixelCopy
            // background thread where walking the View tree is unsafe. Computing
            // now also keeps the boxes aligned with the pixels PixelCopy is about
            // to grab. If the privacy lookup throws, skip the whole frame rather
            // than ship an unmasked one.
            val rects = try { privacyRects() } catch (e: Throwable) { return }
            // The WebView scan starts now, with the copy; its masks are
            // waited for on the PixelCopy thread. A failure to start it skips
            // the frame like the lookup above.
            val web = try { webViewRects(view) } catch (e: Throwable) { return }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(activity.window, bmp, { result ->
                        onPixelCopyResult(result == PixelCopy.SUCCESS, bmp, view, rects, web)
                    }, pixelCopyHandler)
                    handedOff = true
                } catch (e: Throwable) {
                    bmp.recycle()
                    handedOff = true
                    drawFallback(view, w, h, rects, web) { captureEnded() }
                }
            } else {
                handedOff = true
                drawFallback(view, w, h, rects, web) { captureEnded() }
            }
        } finally {
            if (!handedOff) captureEnded()
        }
    }

    /** PixelCopy's callback, on the PixelCopy thread. Ends the capture once.
     *  [web] is the frame's WebView-mask awaiter, if a scan started. */
    internal fun onPixelCopyResult(
        copied: Boolean, bmp: Bitmap, view: View, rects: List<com.replayfy.android.MaskRect>,
        web: ((Long) -> List<com.replayfy.android.MaskRect>)? = null,
    ) {
        val w = bmp.width; val h = bmp.height
        if (copied) {
            // The window turned secure while the copy was in flight: the
            // frame is black, like every other frame of a secure window.
            if (PrivacyRegistry.isSecureWindow(view)) {
                bmp.recycle()
                try { encodeSolid(w, h, Color.BLACK) } finally { captureEnded() }
                return
            }
            try { encodeWithWebViews(bmp, rects, web) } finally { captureEnded() }
            return
        }
        bmp.recycle()
        // The fallback draws the View tree, which only the main thread may do
        // (see drawFallback); this callback runs on the PixelCopy thread.
        val posted = try {
            main.post { drawFallback(view, w, h, rects, web) { captureEnded() } }
        } catch (e: Throwable) { false }
        if (!posted) captureEnded()
    }

    /** Software-draw the window when PixelCopy can't. MAIN THREAD ONLY: a View
     *  may only be drawn on the thread that owns it. Drawing it on the PixelCopy
     *  thread made Jetpack Compose throw "Detected multithreaded access to
     *  SnapshotStateObserver" on the app's next frame, crashing the host app,
     *  and is unsafe for any View. Only the draw happens here; the JPEG encode
     *  goes to the PixelCopy thread like a PixelCopy frame. [done] runs once,
     *  after the frame is encoded or skipped. A window that turned secure
     *  since the tick is never drawn (a software draw ignores FLAG_SECURE):
     *  its frame is black. The WebView masks [web] are waited for with the
     *  encode, off the main thread. */
    private fun drawFallback(
        view: View, w: Int, h: Int, rects: List<com.replayfy.android.MaskRect>,
        web: ((Long) -> List<com.replayfy.android.MaskRect>)?, done: () -> Unit,
    ) {
        if (PrivacyRegistry.isSecureWindow(view)) {
            postSolidFrame(w, h, Color.BLACK, done)
            return
        }
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                // Drawn as the window's surface shows it, which is what a
                // PixelCopy frame and the masks are placed for: moved up by
                // the window's adjustPan scroll while the keyboard is up.
                val canvas = Canvas(it)
                val o = PrivacyRegistry.frameOffset(view)
                canvas.translate(o[0].toFloat(), o[1].toFloat())
                view.draw(canvas)
            }
        } catch (e: Throwable) {
            done(); return // skip frame
        }
        val posted = try {
            pixelCopyHandler.post {
                try { encodeWithWebViews(bmp, rects, web) } catch (e: Throwable) { /* skip frame */ } finally { done() }
            }
        } catch (e: Throwable) { false }
        if (!posted) { bmp.recycle(); done() }
    }

    /** [encode] with the frame's WebView masks added to [rects]. Waits up to
     *  [WEBVIEW_WAIT_MS] for them: PIXELCOPY THREAD ONLY, never main (the
     *  scan answers on main). If the wait throws, the frame is skipped
     *  rather than shipped without them, like a failed privacy lookup. The
     *  frame is stamped before the wait: it shows the screen as copied. */
    private fun encodeWithWebViews(
        bmp: Bitmap, rects: List<com.replayfy.android.MaskRect>,
        web: ((Long) -> List<com.replayfy.android.MaskRect>)?,
    ) {
        val ts = System.currentTimeMillis()
        val all = if (web == null) rects else {
            val masks = try { web(WEBVIEW_WAIT_MS) } catch (e: Throwable) { bmp.recycle(); return }
            if (masks.isEmpty()) rects else rects + masks
        }
        encode(bmp, all, ts)
    }

    /** Encode a solid [color] frame on the PixelCopy thread, the size a
     *  [w]×[h] capture encodes to. No window is copied or drawn. [done] runs
     *  once, after the frame is encoded or skipped. */
    private fun postSolidFrame(w: Int, h: Int, color: Int, done: () -> Unit) {
        val posted = try {
            pixelCopyHandler.post { try { encodeSolid(w, h, color) } finally { done() } }
        } catch (e: Throwable) { false }
        if (!posted) done()
    }

    /** One frame of a single [color], at the size [encode] scales a [w]×[h]
     *  capture to. Made from no source pixels at all. */
    private fun encodeSolid(w: Int, h: Int, color: Int, ts: Long = System.currentTimeMillis()) {
        try {
            val bmp = Bitmap.createBitmap(scaledPx(w), scaledPx(h), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(color)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            bmp.recycle()
            appendFrame(out.toByteArray(), ts)
        } catch (e: Throwable) { /* skip frame */ }
    }

    /** A capture dimension at the encoded scale. */
    private fun scaledPx(px: Int): Int = (px * targetScale).toInt().coerceAtLeast(1)

    /** Obscure a region of [src] onto [canvas] via downscale → upscale.
     *  The downscale averages pixels into representative block colours; the
     *  upscale's [smooth] flag picks the look:
     *   • `smooth = true`  → bilinear upscale = soft **blur**.
     *   • `smooth = false` → nearest-neighbour upscale = hard **pixelate**
     *     mosaic blocks.
     *  Dependency-free + works on every API level. Falls back to a solid box
     *  if anything throws — never leave pixels exposed. */
    private fun mosaicRegion(
        src: Bitmap, canvas: Canvas, dst: Rect, downscale: Int,
        smooth: Boolean, fallback: Paint,
    ) {
        val left = dst.left.coerceIn(0, src.width)
        val top = dst.top.coerceIn(0, src.height)
        val right = dst.right.coerceIn(0, src.width)
        val bottom = dst.bottom.coerceIn(0, src.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return
        try {
            val region = Bitmap.createBitmap(src, left, top, w, h)
            val sw = (w / downscale).coerceAtLeast(1)
            val sh = (h / downscale).coerceAtLeast(1)
            // Average down (filter=true) for representative block colours.
            val small = Bitmap.createScaledBitmap(region, sw, sh, true)
            // Up: smooth=blur, hard=mosaic.
            val big = Bitmap.createScaledBitmap(small, w, h, smooth)
            canvas.drawBitmap(big, left.toFloat(), top.toFloat(), null)
            region.recycle()
            if (small != region) small.recycle()
            if (big != small) big.recycle()
        } catch (e: Throwable) {
            canvas.drawRect(dst, fallback)
        }
    }

    private fun encode(bmp: Bitmap, rects: List<com.replayfy.android.MaskRect>, ts: Long = System.currentTimeMillis()) {
        try {
            val sw = scaledPx(bmp.width)
            val sh = scaledPx(bmp.height)
            val scaled = if (sw != bmp.width) Bitmap.createScaledBitmap(bmp, sw, sh, true) else bmp

            // Mask sensitive rects per their style — OVERLAY paints a solid box,
            // BLUR blurs the region (rects were resolved on the main thread in
            // `capture`, in unscaled frame coords; scale them to `scaled`).
            // Scaled outward (floor the left/top, ceil the right/bottom): the
            // bilinear downscale blends a fractional edge pixel, which must
            // not survive outside the mask.
            if (rects.isNotEmpty()) {
                val c = Canvas(scaled)
                val p = Paint().apply { color = MASK_COLOR }
                val downscale = PrivacyRegistry.blurDownscale.coerceAtLeast(2)
                for (m in rects) {
                    val r = m.rect
                    val dst = Rect(
                        floor(r.left * targetScale).toInt(), floor(r.top * targetScale).toInt(),
                        ceil(r.right * targetScale).toInt(), ceil(r.bottom * targetScale).toInt())
                    if (dst.width() <= 0 || dst.height() <= 0) continue
                    when (m.style) {
                        com.replayfy.android.ReplayMaskStyle.OVERLAY -> c.drawRect(dst, p)
                        com.replayfy.android.ReplayMaskStyle.BLUR ->
                            mosaicRegion(scaled, c, dst, downscale, smooth = true, fallback = p)
                        com.replayfy.android.ReplayMaskStyle.PIXELATE ->
                            mosaicRegion(scaled, c, dst, downscale, smooth = false, fallback = p)
                    }
                }
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            if (scaled != bmp) scaled.recycle()
            bmp.recycle()

            appendFrame(out.toByteArray(), ts)
        } catch (e: Throwable) { /* skip frame */ }
    }

    /** Buffer one encoded frame; a full batch starts an upload. A frame that
     *  lands after stop() (its capture was already in flight) goes straight
     *  out: nothing will flush this session again. */
    internal fun appendFrame(jpeg: ByteArray, ts: Long = System.currentTimeMillis()) {
        val ready: Boolean
        val ended: Boolean
        synchronized(lock) {
            if (discarded || released) return
            pending.add(jpeg to ts)
            if (pending.size > maxBuffered) pending.subList(0, pending.size - maxBuffered).clear()
            ended = stopped
            ready = ended || pending.size >= batchSize
        }
        // Already buffered, so no need to wait for frames still decoding.
        if (ready) requestDrain(throughTs = if (ended) System.currentTimeMillis() else 0L, final = false, completion = null)
    }

    /**
     * Upload buffered frames oldest first, one archive of at most `batchSize`
     * frames per request. `all = false` (a batch just filled) sends full
     * batches only. `all = true` (pause, resume, stop) also sends a partial
     * batch of the frames captured up to now; frames captured afterwards keep
     * filling a batch. A failed upload puts its frames back at the front and
     * ends the drain, so nothing is dropped and a retry can't spin.
     * [completion] runs once the drain settles.
     */
    fun flush(all: Boolean = false, completion: (() -> Unit)? = null) {
        if (all) flushAll(final = false, completion = completion)
        else requestDrain(throughTs = 0L, final = false, completion = completion)
    }

    /** [flush] with `all = true`; [final] for stop()'s, the session's last. */
    private fun flushAll(final: Boolean, completion: (() -> Unit)?) {
        val through = System.currentTimeMillis()
        // Host frames reported before this call may still be decoding. Queue
        // the request behind them so it covers them, and its completion (a
        // stop the host waits on before exiting) waits for them too. Rejected
        // only once the session's threads are gone, when there is nothing
        // left to wait for.
        val queued = decoder.isInitialized() && try {
            decoder.value.execute { requestDrain(through, final, completion) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
        if (!queued) requestDrain(through, final, completion)
    }

    /** Start a drain, or widen the running one. [throughTs] > 0 also sends
     *  the frames captured up to that time as a partial batch. */
    private fun requestDrain(throughTs: Long, final: Boolean, completion: (() -> Unit)?) {
        val all = throughTs > 0L
        val startDrain = synchronized(lock) {
            if (discarded || released) {
                // Nothing will be sent; a caller waiting still hears back.
                false
            } else {
                // Recorded with the drain it starts or joins, so the threads
                // can't go before that drain has ended.
                if (final) finalFlushRequested = true
                if (all) {
                    flushThroughTs = maxOf(flushThroughTs, throughTs)
                    roundsLeft = maxDrainRounds
                }
                completion?.let { drainWaiters.add(it) }
                if (draining) {
                    // The running drain picks up the new frames, the wider scope
                    // and the waiter. A pause/resume/stop request also earns its
                    // own attempt if the in-flight round fails.
                    if (all) requestedDuringRound = true
                    return
                }
                draining = true
                if (!all) roundsLeft = maxDrainRounds
                true
            }
        }
        if (startDrain) sender.execute { drainLoop() } else completion?.let { runWaiters(listOf(it)) }
    }

    /** The drain, one archive per round on the `sender` thread. The queued
     *  task holds `this`, so the drain finishes sending the session's last
     *  frames after ReplayCore releases this object on stop(). */
    private fun drainLoop() {
        while (true) {
            var frames: List<Pair<ByteArray, Long>> = emptyList()
            var done: List<() -> Unit>? = null
            synchronized(lock) {
                val forced = pending.firstOrNull()?.let { it.second <= flushThroughTs } ?: false
                if (discarded || roundsLeft <= 0 || pending.isEmpty() || !(forced || pending.size >= batchSize)) {
                    done = endDrainLocked()
                } else {
                    roundsLeft--
                    requestedDuringRound = false
                    val head = pending.subList(0, minOf(batchSize, pending.size))
                    frames = ArrayList(head)
                    head.clear()
                }
            }
            done?.let { return runWaiters(it) }

            // Compression failed: skip this batch rather than retry it forever.
            val archive = MobileFrames.archive(frames) ?: continue
            val ok = try { transport.sendImages(archive) } catch (_: Throwable) { false }
            if (ok) continue

            var retry = false
            synchronized(lock) {
                if (discarded) {
                    done = endDrainLocked()
                } else {
                    pending.addAll(0, frames)
                    if (pending.size > maxBuffered) pending.subList(0, pending.size - maxBuffered).clear()
                    // Someone asked for a flush while this upload was in flight:
                    // give that request its own attempt.
                    if (requestedDuringRound) retry = true else done = endDrainLocked()
                }
            }
            if (retry) continue
            done?.let { return runWaiters(it) }
        }
    }

    /** Ends the drain and hands back its waiters, led by the threads' shutdown
     *  when this was the session's last drain. Call with `lock` held, in the
     *  same hold as the check that ended it, so a flush that arrives in
     *  between can't be dropped; run the waiters after releasing. */
    private fun endDrainLocked(): List<() -> Unit> {
        val waiters = ArrayList<() -> Unit>()
        draining = false
        // Shutdown lets this task (the drain itself) finish on the sender.
        if (releaseIfDoneLocked()) waiters.add(::releaseThreads)
        waiters.addAll(drainWaiters)
        drainWaiters.clear()
        flushThroughTs = 0L
        roundsLeft = 0
        requestedDuringRound = false
        return waiters
    }

    private fun runWaiters(waiters: List<() -> Unit>) {
        waiters.forEach { runCatching { it() } }
    }

    // ── Thread lifetime ──────────────────────────────────────────────

    /** Count a capture in; false (drop the frame) once the session is over. */
    internal fun captureBegan(): Boolean = synchronized(lock) {
        if (released || discarded) false else { capturesInFlight++; true }
    }

    /** The capture [captureBegan] counted has landed in appendFrame, or been
     *  dropped. The last one out after the session's end shuts the threads. */
    internal fun captureEnded() {
        val release = synchronized(lock) {
            capturesInFlight--
            releaseIfDoneLocked()
        }
        if (release) releaseThreads()
    }

    /** True, once, when the threads can go: stop()'s final upload has been
     *  asked for (or discard() ran), no drain is running, and no capture is
     *  under way that could still add a frame. Call with `lock` held, then
     *  [releaseThreads] after releasing it. */
    private fun releaseIfDoneLocked(): Boolean {
        if (released || draining || capturesInFlight > 0 || !(finalFlushRequested || discarded)) return false
        released = true
        return true
    }

    /** Shut the session's threads down. shutdown(), not shutdownNow(): the
     *  caller may be the drain's own last task, and a flush already queued on
     *  the decoder (a pause just before the end) still runs, so whoever waits
     *  on it hears back. */
    private fun releaseThreads() {
        sender.shutdown()
        if (decoder.isInitialized()) decoder.value.shutdown()
        if (pixelCopyThread.isInitialized()) pixelCopyThread.value.quitSafely()
    }

    /** Frames waiting to upload. For tests. */
    internal val pendingCount: Int
        get() = synchronized(lock) { pending.size }

    /** Whether the native capture timer is running. For tests. */
    internal val isCapturing: Boolean
        get() = synchronized(lock) { capturing }

    /** Whether an upload drain is running. For tests. */
    internal val isDraining: Boolean
        get() = synchronized(lock) { draining }
}
