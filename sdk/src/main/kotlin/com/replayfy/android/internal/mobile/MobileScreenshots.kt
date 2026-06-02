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
    private val privacyRects: () -> List<Rect>,
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(activity.window, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) encode(bmp) else { bmp.recycle(); drawFallback(view, w, h) }
                }, pixelCopyHandler)
            } catch (e: Throwable) {
                bmp.recycle(); drawFallback(view, w, h)
            }
        } else {
            drawFallback(view, w, h)
        }
    }

    private fun drawFallback(view: View, w: Int, h: Int) {
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            encode(bmp)
        } catch (e: Throwable) { /* skip frame */ }
    }

    private fun encode(bmp: Bitmap) {
        try {
            val sw = (bmp.width * targetScale).toInt().coerceAtLeast(1)
            val sh = (bmp.height * targetScale).toInt().coerceAtLeast(1)
            val scaled = if (sw != bmp.width) Bitmap.createScaledBitmap(bmp, sw, sh, true) else bmp

            // Mask sensitive rects with a solid box.
            val rects = privacyRects()
            if (rects.isNotEmpty()) {
                val c = Canvas(scaled)
                val p = Paint().apply { color = Color.DKGRAY }
                for (r in rects) c.drawRect(Rect((r.left * targetScale).toInt(), (r.top * targetScale).toInt(), (r.right * targetScale).toInt(), (r.bottom * targetScale).toInt()), p)
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
