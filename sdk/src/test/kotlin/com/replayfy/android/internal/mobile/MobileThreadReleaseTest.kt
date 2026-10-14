package com.replayfy.android.internal.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Each session gets its own frame buffer and message queue, and each of those
 * runs its uploads (and host-frame decoding) on threads of its own. They used
 * to outlive the session: every rotation (a long background, startNewSession)
 * left about three idle threads behind for the life of the process. These pin
 * down that the threads end with the session, and only once its last archive
 * and its /late batch are out.
 */
@RunWith(RobolectricTestRunner::class)
class MobileThreadReleaseTest {

    @Test
    fun aStoppedSessionLeavesNoThreadsBehind() {
        val before = liveThreads()
        repeat(3) {
            val uploader = FakeUploader()
            val shots = hostFed(uploader)
            val collector = MobileCollector(uploader).apply { start() }
            repeat(25) { shots.appendFrame(frame()) } // a full batch uploads while recording
            shots.submitFrame(png(), emptyList())      // a host frame: the decoder runs
            collector.add(byteArrayOf(1))
            collector.flush()                          // a batch goes out while recording
            waitUntil { collector.waitingCount == 0 && !shots.isDraining }
            collector.add(byteArrayOf(2))              // and the session end carries this one

            val done = CountDownLatch(2)
            shots.stop { done.countDown() }
            collector.stop { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("the last archive went out first", 26, uploader.imageFrameCounts.sum())
            assertEquals("and so did the session end", 1, uploader.lateBatches.size)
        }

        waitUntil { threadsStartedSince(before).isEmpty() }
        assertEquals("no upload, decode or flush-timer thread outlives its session", emptyList<String>(), threadsStartedSince(before))
    }

    @Test
    fun aDiscardedSessionLeavesNoThreadsBehind() {
        val before = liveThreads()
        repeat(3) {
            val uploader = FakeUploader()
            val shots = hostFed(uploader)
            val collector = MobileCollector(uploader).apply { start() }
            repeat(25) { shots.appendFrame(frame()) }
            shots.submitFrame(png(), emptyList())
            collector.add(byteArrayOf(1))
            collector.flush()
            waitUntil { !shots.isDraining && collector.waitingCount == 0 }

            shots.discard() // cancelSession
            collector.discard()
        }

        waitUntil { threadsStartedSince(before).isEmpty() }
        assertEquals(emptyList<String>(), threadsStartedSince(before))
    }

    @Test
    fun theThreadsWaitForAnUploadStillRunningWhenTheSessionEnds() {
        // Taken first: the failed uploads below already start both senders.
        val before = liveThreads()
        val uploader = FakeUploader()
        uploader.messageDelayMs = 300
        uploader.imageDelayMs = 300
        val shots = hostFed(uploader)
        val collector = MobileCollector(uploader)
        // Offline while two batches of each queue up, so the session end has
        // more than one request to make on each thread.
        uploader.failImages = true
        uploader.failMessages = true
        repeat(30) { shots.appendFrame(frame()) }
        repeat(12) { collector.add(ByteArray(60_000) { 7 }) }
        waitUntil { !shots.isDraining && shots.pendingCount == 30 && collector.waitingCount == 12 }
        uploader.failImages = false
        uploader.failMessages = false

        val done = CountDownLatch(2)
        shots.stop { done.countDown() }
        collector.stop { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals("every archive went out", listOf(20, 10), uploader.imageFrameCounts)
        assertEquals("every batch but the last went to /i", 1, uploader.messageBatchCount)
        assertEquals("the last one ended the session", 1, uploader.lateBatches.size)
        waitUntil { threadsStartedSince(before).isEmpty() }
        assertEquals(emptyList<String>(), threadsStartedSince(before))
    }

    @Test
    fun theThreadsWaitForAScreenshotStillBeingTakenWhenTheSessionEnds() {
        val uploader = FakeUploader()
        val shots = hostFed(uploader)
        assertTrue(shots.captureBegan()) // PixelCopy has the frame
        val before = liveThreads()
        val stopped = CountDownLatch(1)
        shots.stop { stopped.countDown() }
        assertTrue(stopped.await(5, TimeUnit.SECONDS))

        Thread.sleep(200)
        // The PixelCopy callback lands now, on a thread that must still be up.
        shots.appendFrame(frame())
        shots.captureEnded()

        waitUntil { uploader.imageFrameCounts == listOf(1) }
        assertEquals("the late frame still went out", listOf(1), uploader.imageFrameCounts)
        waitUntil { threadsStartedSince(before).isEmpty() }
        assertEquals("and then the threads went", emptyList<String>(), threadsStartedSince(before))
        assertFalse("nothing is captured after that", shots.captureBegan())
    }

    @Test
    fun hostFramesStillDecodingWhenTheSessionEndsGoOutBeforeTheThreadsGo() {
        val uploader = FakeUploader()
        val shots = hostFed(uploader)
        repeat(15) { shots.submitFrame(png(), emptyList()) }

        val done = CountDownLatch(1)
        shots.stop { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals("the stop covers frames reported before it, decoded or not", 15, uploader.imageFrameCounts.sum())
    }

    @Test
    fun callsAfterTheSessionsThreadsAreGoneAreHarmless() {
        val uploader = FakeUploader()
        val shots = hostFed(uploader)
        val collector = MobileCollector(uploader)
        shots.submitFrame(png(), emptyList())
        val done = CountDownLatch(2)
        shots.stop { done.countDown() }
        collector.stop { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val sentFrames = uploader.imageFrameCounts.sum()

        // A host frame or a message racing the session end, and a stop that
        // comes twice, must not throw into the host (a rejected task would).
        shots.submitFrame(png(), emptyList())
        shots.pause()
        shots.resume()
        val settled = CountDownLatch(2)
        shots.flush(all = true) { settled.countDown() }
        collector.add(byteArrayOf(1))
        collector.flush()
        collector.drain()
        collector.flushBlocking()
        collector.stop { settled.countDown() }
        assertTrue("a caller waiting on either still hears back", settled.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals("nothing more leaves once the session is over", sentFrames, uploader.imageFrameCounts.sum())
        assertEquals(0, uploader.messageBatchCount)
        assertEquals(0, uploader.lateBatches.size)
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun hostFed(uploader: FakeUploader) =
        MobileScreenshots(uploader, activityProvider = { null }, privacyRects = { emptyList() }, nativeCapture = false)

    private fun frame() = ByteArray(64) { 9 }

    /** A real 1×1 PNG, as Flutter's Dart-side capture would send. */
    private fun png(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )
}
