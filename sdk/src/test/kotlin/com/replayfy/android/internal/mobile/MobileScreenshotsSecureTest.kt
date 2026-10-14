package com.replayfy.android.internal.mobile

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.replayfy.android.MaskRect
import com.replayfy.android.ReplayMaskStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A FLAG_SECURE window records as a black frame that is made without copying
 * or drawing the window, the mask edges are scaled outward, and a host frame
 * can be replaced by a solid cover without its pixels being decoded. Real
 * pixels: the frames are decoded back from the uploaded archive.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileScreenshotsSecureTest {

    class RecordingView(context: Context) : View(context) {
        @Volatile var draws = 0
        override fun draw(canvas: Canvas) {
            draws++
            super.draw(canvas)
        }
    }

    /** An Activity that marks its window FLAG_SECURE, as banking apps do. */
    class SecureActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setContentView(RecordingView(this).apply { setBackgroundColor(Color.WHITE) })
        }
        val content: RecordingView get() = (window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as RecordingView
    }

    private val uploader = FakeUploader()

    private fun shots(activity: Activity? = null, privacyRects: () -> List<MaskRect> = { emptyList() }) =
        MobileScreenshots(uploader, activityProvider = { activity }, privacyRects = privacyRects, nativeCapture = true)

    /** The one frame [shots] has encoded, once it is uploaded. */
    private fun onlyFrame(shots: MobileScreenshots): Bitmap {
        waitUntil { shots.pendingCount == 1 }
        assertEquals("one frame encoded", 1, shots.pendingCount)
        shots.flush(all = true)
        waitUntil { uploader.imageJpegs.size == 1 }
        val jpeg = uploader.imageJpegs.single()
        return requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "frame decodes" }
    }

    private fun luma(px: Int) = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3

    private fun meanLuma(b: Bitmap): Int {
        var sum = 0L; var n = 0
        for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) { sum += luma(b.getPixel(x, y)); n++ }
        return (sum / n).toInt()
    }

    private fun white(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun view(secure: Boolean) = RecordingView(androidx.test.core.app.ApplicationProvider.getApplicationContext()).apply {
        layout(0, 0, 200, 200)
        // An unattached view is its own root: these are its window's attributes.
        layoutParams = WindowManager.LayoutParams().also {
            if (secure) it.flags = it.flags or WindowManager.LayoutParams.FLAG_SECURE
        }
    }

    /** Run [block] where PixelCopy's callback runs: off the main thread. */
    private fun onPixelCopyThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        Thread({ block(); done.countDown() }, "pixelcopy-callback").start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aSecureWindowIsRecordedBlackWithoutBeingCopiedOrDrawn() {
        val activity = Robolectric.buildActivity(SecureActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        var lookups = 0
        val shots = shots(activity) { lookups++; emptyList() }
        activity.content.draws = 0

        assertTrue(shots.captureBegan())
        shots.capture()

        val frame = onlyFrame(shots)
        assertTrue("black, mean luma ${meanLuma(frame)}", meanLuma(frame) < 8)
        assertEquals("the privacy rects are never computed", 0, lookups)
        assertEquals("the window is never drawn", 0, activity.content.draws)
        val decor = activity.window.decorView
        assertEquals("the size a capture encodes to", decor.width / 2, frame.width)
        assertEquals(decor.height / 2, frame.height)
    }

    @Test
    fun aFailedCopyOfASecureWindowFallsBackToBlackNotADraw() {
        val v = view(secure = true)
        val shots = shots()
        assertTrue(shots.captureBegan())

        onPixelCopyThread { shots.onPixelCopyResult(false, white(200, 200), v, emptyList()) }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(meanLuma(onlyFrame(shots)) < 8)
        assertEquals("a software draw would ignore FLAG_SECURE", 0, v.draws)
    }

    @Test
    fun aCopyOfAWindowThatTurnedSecureMeanwhileIsReplacedWithBlack() {
        val shots = shots()
        assertTrue(shots.captureBegan())

        onPixelCopyThread { shots.onPixelCopyResult(true, white(200, 200), view(secure = true), emptyList()) }

        assertTrue(meanLuma(onlyFrame(shots)) < 8)
    }

    @Test
    fun anOrdinaryCopyKeepsItsPixels() {
        val shots = shots()
        assertTrue(shots.captureBegan())

        onPixelCopyThread { shots.onPixelCopyResult(true, white(200, 200), view(secure = false), emptyList()) }

        assertTrue(meanLuma(onlyFrame(shots)) > 240)
    }

    @Test
    fun maskEdgesAreScaledOutwardSoNoBlendedEdgePixelSurvives() {
        val shots = shots()
        assertTrue(shots.captureBegan())
        // right = 101 at scale 0.5 is 50.5: the mask must cover column 50.
        val rects = listOf(MaskRect(Rect(0, 0, 101, 101), ReplayMaskStyle.OVERLAY))

        onPixelCopyThread { shots.onPixelCopyResult(true, white(200, 200), view(secure = false), rects) }

        val frame = onlyFrame(shots)
        assertEquals(100, frame.width)
        assertTrue("column 50 masked, luma ${luma(frame.getPixel(50, 20))}", luma(frame.getPixel(50, 20)) < 128)
        assertTrue("row 50 masked, luma ${luma(frame.getPixel(20, 50))}", luma(frame.getPixel(20, 50)) < 128)
        assertTrue("outside stays clear", luma(frame.getPixel(80, 80)) > 200)
    }

    @Test
    fun aCoveredHostFrameIsOneSolidColourAtTheFramesSize() {
        val png = ByteArrayOutputStream().also { white(200, 400).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val shots = MobileScreenshots(uploader, activityProvider = { null }, privacyRects = { emptyList() }, nativeCapture = false)

        // The host's own rects don't matter under a cover.
        shots.submitFrame(png, listOf(MaskRect(Rect(0, 0, 10, 10), ReplayMaskStyle.BLUR)), MobileScreenshots.MASK_COLOR)

        val frame = onlyFrame(shots)
        assertEquals(100, frame.width)
        assertEquals(200, frame.height)
        for (y in 0 until frame.height step 10) for (x in 0 until frame.width step 10) {
            val px = frame.getPixel(x, y)
            assertTrue("($x,$y) is the overlay grey: $px", Math.abs(luma(px) - 56) <= 6)
        }
    }

    @Test
    fun aHostFrameWithoutACoverKeepsItsPixels() {
        val png = ByteArrayOutputStream().also { white(200, 400).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val shots = MobileScreenshots(uploader, activityProvider = { null }, privacyRects = { emptyList() }, nativeCapture = false)

        shots.submitFrame(png, emptyList())

        assertTrue(meanLuma(onlyFrame(shots)) > 240)
    }
}
