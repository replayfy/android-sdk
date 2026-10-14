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
 * Guards the background/resume upload path of the mobile engine (the Android
 * port of the iOS MobileBackgroundDrainTests). The bugs these pin down:
 *   • a pause only flushed when the native timer was running, so host-fed
 *     (Flutter) frames buffered since the last full batch were never sent at
 *     the first background;
 *   • a flush sent the whole buffer as one unbounded archive and dropped
 *     nothing on failure but never retried it on return;
 *   • the message queue sent one batch per flush, stranding the rest, and a
 *     message larger than the batch cap wedged it;
 *   • resuming restarted native screenshots for host-fed (Flutter) capture.
 */
@RunWith(RobolectricTestRunner::class)
class MobileBackgroundDrainTest {

    // ── Frames ───────────────────────────────────────────────────────

    @Test
    fun fullBatchFlushLeavesPartialRemainderBuffered() {
        val uploader = FakeUploader()
        val shots = hostFed(uploader)

        repeat(45) { shots.appendFrame(frame()) }

        waitUntil { uploader.imageFrameCounts == listOf(20, 20) }
        assertEquals("only full batches go out while recording", listOf(20, 20), uploader.imageFrameCounts)
        assertEquals(5, shots.pendingCount)
    }

    @Test
    fun pauseUploadsEverythingBufferedThenCompletes() {
        val uploader = FakeUploader()
        // Host-fed: the native timer never ran, which is exactly when the old
        // pause() returned early without flushing.
        val shots = hostFed(uploader)
        repeat(5) { shots.appendFrame(frame()) }
        assertEquals("a partial batch is not sent while recording", emptyList<Int>(), uploader.imageFrameCounts)

        val done = CountDownLatch(1)
        shots.pause { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals("completion runs only after the upload settles", listOf(5), uploader.imageFrameCounts)
        assertEquals(0, shots.pendingCount)
    }

    @Test
    fun pauseSendsBoundedArchivesInCaptureOrder() {
        val uploader = FakeUploader()
        uploader.queueImageResults(false) // the first full-batch flush fails while recording
        val shots = hostFed(uploader)
        var ts = 1_000L
        repeat(47) { shots.appendFrame(frame(), ts++) }
        waitUntil { !shots.isDraining }

        val done = CountDownLatch(1)
        shots.pause { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals("every frame is uploaded exactly once", 47, uploader.imageFrameCounts.sum())
        assertTrue("archives stay bounded", uploader.imageFrameCounts.all { it <= 20 })
        val sent = uploader.imageTimestamps
        assertEquals("archives reach the server in capture order", sent.sorted(), sent)
        assertEquals(0, shots.pendingCount)
    }

    @Test
    fun aDrainWhoseBufferKeepsRefillingStillEnds() {
        val uploader = FakeUploader()
        val shots = hostFed(uploader)
        val feeding = java.util.concurrent.atomic.AtomicBoolean(true)
        val feeder = Thread { while (feeding.get()) shots.appendFrame(frame()) }.apply { start() }
        try {
            val done = CountDownLatch(1)
            shots.pause { done.countDown() }
            // Frames arrive faster than they upload; without a round limit the
            // drain (and a caller waiting on it) would run as long as they do.
            assertTrue("the drain settles while frames keep coming", done.await(2, TimeUnit.SECONDS))
        } finally {
            feeding.set(false)
            feeder.join()
        }
    }

    @Test
    fun framesFailingDuringPauseAreKeptAndSentOnResume() {
        val uploader = FakeUploader()
        uploader.queueImageResults(false) // the background cuts the in-flight upload off
        val shots = hostFed(uploader)
        repeat(5) { shots.appendFrame(frame()) }

        val done = CountDownLatch(1)
        shots.pause { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("the failed upload delivered nothing", emptyList<Int>(), uploader.imageFrameCounts)
        assertEquals("pause never discards frames", 5, shots.pendingCount)

        shots.resume()
        waitUntil { uploader.imageFrameCounts == listOf(5) }
        assertEquals("resume resends the leftovers immediately", listOf(5), uploader.imageFrameCounts)
        assertEquals(0, shots.pendingCount)
    }

    @Test
    fun stopFinishesUploadingAfterTheEngineReleasesIt() {
        val uploader = FakeUploader()
        var shots: MobileScreenshots? = hostFed(uploader)
        // Offline while 45 frames buffer up, so stop() has three archives to send.
        uploader.failImages = true
        repeat(45) { shots?.appendFrame(frame()) }
        waitUntil { shots?.isDraining == false && shots?.pendingCount == 45 }
        assertEquals(45, shots?.pendingCount)
        uploader.failImages = false

        shots?.stop()
        @Suppress("UNUSED_VALUE")
        shots = null // ReplayCore.stop() drops its reference straight away

        waitUntil { uploader.imageFrameCounts.sum() == 45 }
        assertEquals("the session's last frames all go out", listOf(20, 20, 5), uploader.imageFrameCounts)
    }

    @Test
    fun resumeJoiningAFailingUploadStillGetsItsAttempt() {
        val uploader = FakeUploader()
        uploader.imageDelayMs = 300
        uploader.queueImageResults(false) // the upload cut off by the background dies...
        val shots = hostFed(uploader)
        repeat(5) { shots.appendFrame(frame()) }

        shots.pause()
        waitUntil { shots.isDraining }
        shots.resume() // ...while the app returns and asks for the leftovers

        waitUntil { uploader.imageFrameCounts == listOf(5) }
        assertEquals("the resume request is retried, not lost with the failed round", listOf(5), uploader.imageFrameCounts)
        assertEquals(0, shots.pendingCount)
    }

    @Test
    fun resumeOnlyForcesOutFramesCapturedBeforeIt() {
        val uploader = FakeUploader()
        uploader.imageDelayMs = 300
        val shots = hostFed(uploader)
        repeat(3) { shots.appendFrame(frame()) }
        shots.resume()
        // Its upload has taken the three (a round takes up to a batch, so
        // frames landing before it starts may ride along).
        waitUntil { shots.isDraining && shots.pendingCount == 0 }

        // Live capture keeps going while the resume's upload is in flight.
        val later = System.currentTimeMillis() + 60_000
        repeat(5) { shots.appendFrame(frame(), later + it) }
        waitUntil { !shots.isDraining }

        assertEquals("frames after the resume wait for a full batch", listOf(3), uploader.imageFrameCounts)
        assertEquals(5, shots.pendingCount)
    }

    @Test
    fun hostFramesReportedBeforeAPauseGoOutWithIt() {
        val uploader = FakeUploader()
        uploader.imageDelayMs = 500
        val shots = hostFed(uploader)
        repeat(20) { shots.appendFrame(frame()) } // a full batch: its upload is in flight
        waitUntil { shots.isDraining }
        // Flutter reports three more frames, still decoding when the app goes
        // to the background.
        repeat(3) { shots.submitFrame(png(), emptyList()) }

        val done = CountDownLatch(1)
        shots.pause { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals("every frame reported before the pause is out when it completes", 23, uploader.imageFrameCounts.sum())
        assertEquals(0, shots.pendingCount)
    }

    @Test
    fun hostFramesStillDecodingWhenThePauseComesGoOutWithIt() {
        val uploader = FakeUploader()
        val shots = hostFed(uploader)
        // Nothing uploading: the pause's drain would start at once, before
        // most of these have been decoded and buffered.
        repeat(15) { shots.submitFrame(png(), emptyList()) }

        val done = CountDownLatch(1)
        shots.pause { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals("the pause covers frames reported before it, decoded or not", 15, uploader.imageFrameCounts.sum())
        assertEquals(0, shots.pendingCount)
    }

    @Test
    fun aFrameLandingAfterStopStillGoesOut() {
        val uploader = FakeUploader()
        val shots = hostFed(uploader)
        assertTrue(shots.captureBegan()) // a screenshot waiting on PixelCopy...
        shots.stop()
        waitUntil { !shots.isDraining }

        shots.appendFrame(frame()) // ...lands after the stop's upload
        shots.captureEnded()

        waitUntil { uploader.imageFrameCounts == listOf(1) }
        assertEquals("nothing flushes this session again, so it goes now", listOf(1), uploader.imageFrameCounts)
    }

    @Test
    fun stopRacingAResumeNeverRestartsCapture() {
        val shots = native(FakeUploader())
        shots.resume()
        shots.stop()
        assertFalse(shots.isCapturing)
        // A resume that lands after the session ended (a foreground return
        // racing stop) must not restart it either.
        shots.resume()
        assertFalse(shots.isCapturing)
    }

    @Test
    fun discardHaltsARunningDrain() {
        val uploader = FakeUploader()
        val shots = hostFed(uploader)
        uploader.failImages = true
        repeat(60) { shots.appendFrame(frame()) }
        waitUntil { !shots.isDraining && shots.pendingCount == 60 }
        uploader.failImages = false
        uploader.imageDelayMs = 200

        shots.pause()
        waitUntil { shots.isDraining }
        shots.discard() // cancelSession: nothing more may leave the device

        waitUntil { !shots.isDraining }
        Thread.sleep(300)
        assertTrue("only the archive already in flight goes out", uploader.imageFrameCounts.size <= 1)
        assertEquals(0, shots.pendingCount)
    }

    @Test
    fun resumeDoesNotStartNativeCaptureForHostFedFrames() {
        val shots = hostFed(FakeUploader())
        shots.pause()
        shots.resume()
        assertFalse("host-fed capture must never start the native timer", shots.isCapturing)
    }

    @Test
    fun resumeRestartsNativeCapture() {
        val shots = native(FakeUploader())
        shots.pause()
        shots.resume()
        assertTrue(shots.isCapturing)
        shots.discard()
    }

    // ── Messages ─────────────────────────────────────────────────────

    @Test
    fun collectorDrainSendsEveryQueuedBatch() {
        val uploader = FakeUploader()
        val collector = MobileCollector(uploader)
        // Offline while 12 × 60 KB queue up (720 KB, more than one 500 KB
        // batch): the size-triggered flushes fail and re-queue.
        uploader.failMessages = true
        repeat(12) { collector.add(ByteArray(60_000) { 7 }) }
        waitUntil { collector.waitingCount == 12 }
        assertEquals(12, collector.waitingCount)
        uploader.failMessages = false

        val done = CountDownLatch(1)
        collector.drain { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals("drain empties the queue, not just one batch", 0, collector.waitingCount)
        assertEquals(2, uploader.messageBatchCount)
    }

    @Test
    fun collectorStopDrainsEverythingAndEndsWithLate() {
        val uploader = FakeUploader()
        var collector: MobileCollector? = MobileCollector(uploader)
        uploader.failMessages = true
        repeat(12) { collector?.add(ByteArray(60_000) { 7 }) }
        waitUntil { collector?.waitingCount == 12 }
        uploader.failMessages = false

        collector?.stop()
        @Suppress("UNUSED_VALUE")
        collector = null // ReplayCore.stop() drops its reference straight away

        waitUntil { uploader.lateBatches.size == 1 }
        assertEquals("every batch but the last goes to /i", 1, uploader.messageBatchCount)
        assertEquals("the batch that empties the queue goes to /late", 1, uploader.lateBatches.size)
    }

    @Test
    fun aSessionEndWithMoreBatchesThanOneDrainSendsStillEndsWithLate() {
        val uploader = FakeUploader()
        uploader.messageDelayMs = 1
        var collector: MobileCollector? = MobileCollector(uploader)
        // Offline for long enough that 22 batches (8 × 60 KB each) queue up,
        // more than one drain's 20 rounds.
        uploader.failMessages = true
        repeat(8 * 22) { collector?.add(ByteArray(60_000) { 7 }) }
        waitUntil { collector?.waitingCount == 8 * 22 }
        uploader.failMessages = false

        collector?.stop()
        @Suppress("UNUSED_VALUE")
        collector = null // ReplayCore.stop() drops its reference straight away

        waitUntil(10_000) { uploader.lateBatches.isNotEmpty() }
        assertEquals("every batch but the last goes to /i", 21, uploader.messageBatchCount)
        assertEquals("the session end still goes out", 1, uploader.lateBatches.size)
    }

    @Test
    fun aCollectorDrainWhoseQueueKeepsRefillingStillEnds() {
        val uploader = FakeUploader()
        val collector = MobileCollector(uploader)
        val feeding = java.util.concurrent.atomic.AtomicBoolean(true)
        // Keeps a backlog of ~200 (two dozen batches) however fast the drain
        // sends; an unbounded feeder piled up hundreds of MB and the test
        // measured the garbage collector instead.
        val feeder = Thread {
            while (feeding.get()) {
                if (collector.waitingCount < 200) collector.add(ByteArray(60_000) { 7 }) else Thread.yield()
            }
        }.apply { start() }
        try {
            waitUntil { collector.waitingCount > 20 }
            val done = CountDownLatch(1)
            collector.drain { done.countDown() }
            assertTrue("the drain settles while messages keep coming", done.await(2, TimeUnit.SECONDS))
        } finally {
            feeding.set(false)
            feeder.join()
        }
    }

    @Test
    fun collectorDrainStopsOnFailureAndKeepsTheBatch() {
        val uploader = FakeUploader()
        uploader.queueMessageResults(false)
        val collector = MobileCollector(uploader)
        repeat(3) { collector.add(byteArrayOf(it.toByte())) }

        val done = CountDownLatch(1)
        collector.drain { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(0, uploader.messageBatchCount)
        assertEquals("a failed batch goes back on the queue", 3, collector.waitingCount)
    }

    @Test
    fun messageLargerThanTheBatchCapIsSentAloneInsteadOfWedgingTheQueue() {
        val uploader = FakeUploader()
        val collector = MobileCollector(uploader)
        collector.add(ByteArray(600_000) { 1 }) // over the 500 KB cap on its own
        collector.add(byteArrayOf(2))

        val done = CountDownLatch(1)
        collector.drain { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals("the queue empties", 0, collector.waitingCount)
        assertEquals("the oversized message goes in a batch of its own", 2, uploader.messageBatchCount)
    }

    @Test
    fun collectorDiscardHaltsARunningDrainAndSendsNothingMore() {
        val uploader = FakeUploader()
        val collector = MobileCollector(uploader)
        // Offline while 24 × 60 KB queue up (four batches).
        uploader.failMessages = true
        repeat(24) { collector.add(ByteArray(60_000) { 7 }) }
        waitUntil { collector.waitingCount == 24 }
        uploader.failMessages = false
        uploader.messageDelayMs = 200

        collector.drain()
        waitUntil { uploader.messageBatchCount == 1 }
        collector.discard() // cancelSession: nothing more may leave the device
        collector.stop()    // nor does a session end after it
        Thread.sleep(500)

        assertEquals(0, collector.waitingCount)
        assertEquals("only the batch already in flight went out", 1, uploader.messageBatchCount)
        assertEquals(0, uploader.lateBatches.size)
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun hostFed(uploader: FakeUploader) =
        MobileScreenshots(uploader, activityProvider = { null }, privacyRects = { emptyList() }, nativeCapture = false)

    private fun native(uploader: FakeUploader) =
        MobileScreenshots(uploader, activityProvider = { null }, privacyRects = { emptyList() }, nativeCapture = true)

    /** Stand-in for one encoded JPEG; the archive only frames the bytes. */
    private fun frame() = ByteArray(64) { 9 }

    /** A real 1×1 PNG, as Flutter's Dart-side capture would send. */
    private fun png(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )
}
