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
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Periodic screenshot capture → frames archive upload (mirrors iOS).
 *
 * A timer fires at `1/fps`, captures the current activity's decor view
 * (PixelCopy on API 26+, Canvas draw fallback otherwise), downscales,
 * JPEG-compresses, accumulates, and uploads a gzipped frames archive
 * once `batchSize` frames are collected.
 */
class MobileScreenshots(
    private val transport: MobileTransport,
    private val activityProvider: () -> Activity?,
    private val privacyRects: () -> List<com.replayfy.android.MaskRect>,
) {
    private val lock = Any()
    private val pending = ArrayList<Pair<ByteArray, Long>>()
    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val pixelCopyThread = HandlerThread("replay-pixelcopy").apply { start() }
    private val pixelCopyHandler = Handler(pixelCopyThread.looper)

    private var running = false
    private var intervalMs = 333L      // 3 fps default
    private var jpegQuality = 50
    private val targetScale = 0.5f     // downscale to keep size down
    private val batchSize = 20
    private val maxBuffered = 500

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
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
        running = true
        main.post(tick)
    }

    /** Feed an externally-captured frame (Flutter's Dart-side RepaintBoundary
     *  capture, since native PixelCopy can't read Flutter's SurfaceView) into
     *  the same encode → accumulate → upload pipeline. Decodes + re-encodes on
     *  the sender thread so it never touches the main thread. [rects] are the
     *  occlusion regions (developer `ReplayMask` bounds, in the PNG's pixel
     *  coords) the encoder masks — Flutter has no native view tree to mask. */
    fun submitFrame(png: ByteArray, rects: List<com.replayfy.android.MaskRect>) {
        sender.execute {
            val bmp = try {
                android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
            } catch (e: Throwable) { null } ?: return@execute
            encode(bmp, rects)
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        flush()
    }

    /** Suspend capture while the app is backgrounded (no point shooting an
     *  invisible window — it just inflates the archive). Flushes what's
     *  pending so nothing is lost. */
    fun pause() {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        flush()
    }

    /** Resume capture when the app returns to the foreground. */
    fun resume() {
        if (running) return
        running = true
        main.post(tick)
    }

    private fun capture() {
        val activity = activityProvider() ?: return
        val view = activity.window?.decorView ?: return
        val w = view.width; val h = view.height
        if (w <= 0 || h <= 0) return

        // Resolve sensitive rects HERE, on the main thread — `capture` always
        // runs on the main looper, whereas `encode` runs on the PixelCopy
        // background thread where walking the View tree is unsafe. Computing
        // now also keeps the boxes aligned with the pixels PixelCopy is about
        // to grab. If the privacy lookup throws, skip the whole frame rather
        // than ship an unmasked one.
        val rects = try { privacyRects() } catch (e: Throwable) { return }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(activity.window, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) encode(bmp, rects) else { bmp.recycle(); drawFallback(view, w, h, rects) }
                }, pixelCopyHandler)
            } catch (e: Throwable) {
                bmp.recycle(); drawFallback(view, w, h, rects)
            }
        } else {
            drawFallback(view, w, h, rects)
        }
    }

    private fun drawFallback(view: View, w: Int, h: Int, rects: List<com.replayfy.android.MaskRect>) {
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            encode(bmp, rects)
        } catch (e: Throwable) { /* skip frame */ }
    }

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

    private fun encode(bmp: Bitmap, rects: List<com.replayfy.android.MaskRect>) {
        try {
            val sw = (bmp.width * targetScale).toInt().coerceAtLeast(1)
            val sh = (bmp.height * targetScale).toInt().coerceAtLeast(1)
            val scaled = if (sw != bmp.width) Bitmap.createScaledBitmap(bmp, sw, sh, true) else bmp

            // Mask sensitive rects per their style — OVERLAY paints a solid box,
            // BLUR blurs the region (rects were resolved on the main thread in
            // `capture`, in unscaled decor-view coords; scale them to `scaled`).
            if (rects.isNotEmpty()) {
                val c = Canvas(scaled)
                val p = Paint().apply { color = Color.rgb(56, 56, 56) }
                val downscale = com.replayfy.android.internal.privacy.PrivacyRegistry
                    .blurDownscale.coerceAtLeast(2)
                for (m in rects) {
                    val r = m.rect
                    val dst = Rect(
                        (r.left * targetScale).toInt(), (r.top * targetScale).toInt(),
                        (r.right * targetScale).toInt(), (r.bottom * targetScale).toInt())
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

            val jpeg = out.toByteArray()
            val ready: Boolean
            synchronized(lock) {
                pending.add(jpeg to System.currentTimeMillis())
                if (pending.size > maxBuffered) pending.subList(0, pending.size - maxBuffered).clear()
                ready = pending.size >= batchSize
            }
            if (ready) flush()
        } catch (e: Throwable) { /* skip frame */ }
    }

    fun flush() {
        val frames: List<Pair<ByteArray, Long>>
        synchronized(lock) {
            if (pending.isEmpty()) return
            frames = ArrayList(pending); pending.clear()
        }
        sender.execute {
            val archive = MobileFrames.archive(frames) ?: return@execute
            val ok = transport.sendImages(archive)
            if (!ok) synchronized(lock) { pending.addAll(0, frames) }
        }
    }
}
