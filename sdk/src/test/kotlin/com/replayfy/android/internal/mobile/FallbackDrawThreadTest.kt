package com.replayfy.android.internal.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A failed PixelCopy falls back to drawing the View tree. That draw must run on
 * the main thread: drawing on the PixelCopy thread made Jetpack Compose throw
 * "Detected multithreaded access to SnapshotStateObserver" on the app's next
 * frame and crash the host app.
 */
@RunWith(RobolectricTestRunner::class)
class FallbackDrawThreadTest {

    private class RecordingView(context: Context) : View(context) {
        @Volatile var drawThread: Thread? = null
        override fun draw(canvas: Canvas) {
            drawThread = Thread.currentThread()
            super.draw(canvas)
        }
    }

    @Test
    fun aFailedPixelCopyDrawsTheViewOnTheMainThread() {
        val view = RecordingView(ApplicationProvider.getApplicationContext())
        view.layout(0, 0, 40, 80)
        val shots = MobileScreenshots(FakeUploader(), activityProvider = { null }, privacyRects = { emptyList() }, nativeCapture = true)
        assertTrue(shots.captureBegan())

        // The PixelCopy callback lands on a background thread, with a failure.
        val callbackDone = CountDownLatch(1)
        Thread({
            shots.onPixelCopyResult(false, Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888), view, emptyList())
            callbackDone.countDown()
        }, "pixelcopy-callback").start()
        assertTrue(callbackDone.await(5, TimeUnit.SECONDS))
        assertEquals("nothing drawn off the main thread", null, view.drawThread)

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Looper.getMainLooper().thread, view.drawThread)
    }
}
