package com.replayfy.android.internal.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reference tracker's late-messages file: a session's final (/late) batch
 * is kept on disk until the server accepts it, and a later launch resends what
 * an earlier one could not deliver, under that session's own token.
 */
@RunWith(RobolectricTestRunner::class)
class MobileLateMessagesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun stopKeepsTheFinalBatchOnDiskOnlyUntilTheServerAcceptsIt() {
        val store = MobileLateMessages(tmp.newFolder("late"))
        val uploader = FakeUploader(token = "tok-A")
        val collector = MobileCollector(uploader, store)
        repeat(3) { collector.add(byteArrayOf(it.toByte())) }

        collector.stop()

        waitUntil { uploader.lateBatches.size == 1 }
        assertEquals(1, uploader.lateBatches.size)
        waitUntil { store.pending().isEmpty() }
        assertEquals("delivered, so nothing is left to resend", 0, store.pending().size)
    }

    @Test
    fun aFinalBatchTheServerNeverGotIsResentNextLaunchUnderItsOwnToken() {
        val store = MobileLateMessages(tmp.newFolder("late"))
        val uploader = FakeUploader(token = "tok-A")
        uploader.failLate = true // offline when the session ends
        val collector = MobileCollector(uploader, store)
        repeat(3) { collector.add(byteArrayOf(it.toByte())) }

        collector.stop()
        waitUntil { store.pending().size == 1 }
        assertEquals("the undelivered batch stays on disk", 1, store.pending().size)

        // Next launch (a new session with its own token is irrelevant here).
        val sent = ArrayList<Pair<String, ByteArray>>()
        store.resend(store.pending()) { batch, token -> sent.add(token to batch); true }

        assertEquals(1, sent.size)
        assertEquals("sent for the session it belongs to", "tok-A", sent[0].first)
        val batch = sent[0].second
        // batchMeta, then the three one-byte messages in order.
        assertArrayEquals(byteArrayOf(0, 1, 2), batch.copyOfRange(batch.size - 3, batch.size))
        assertEquals("accepted, so deleted", 0, store.pending().size)
    }

    @Test
    fun aSessionEndThatCannotReachTheServerKeepsTheWholeQueueOnDisk() {
        val store = MobileLateMessages(tmp.newFolder("late"))
        val uploader = FakeUploader(token = "tok-A")
        val collector = MobileCollector(uploader, store)
        // Offline at session end with more than one batch queued: the first
        // /i send fails, and the engine has already let go of this collector.
        uploader.failMessages = true
        repeat(12) { collector.add(ByteArray(60_000) { 7 }) }
        waitUntil { collector.waitingCount == 12 }

        collector.stop()

        waitUntil { store.pending().size == 2 }
        assertEquals("both batches are kept for the next launch", 2, store.pending().size)
        assertEquals("nothing reached /late", 0, uploader.lateBatches.size)
        val kept = ArrayList<ByteArray>()
        store.resend(store.pending()) { batch, _ -> kept.add(batch); true }
        val payload = kept.sumOf { it.size }
        assertTrue("all 12 messages are in them", payload >= 12 * 60_000)
    }

    @Test
    fun resendKeepsWhatFailsAndDropsUnreadableFiles() {
        val dir = tmp.newFolder("late")
        val store = MobileLateMessages(dir)
        store.save("tok-A", byteArrayOf(1))
        store.save("tok-B", byteArrayOf(2))
        java.io.File(dir, "late-0-0.dat").writeBytes(byteArrayOf()) // torn / empty

        val tried = ArrayList<String>()
        store.resend(store.pending()) { _, token -> tried.add(token); token == "tok-B" }

        assertEquals("one failure doesn't hold back the rest", listOf("tok-A", "tok-B"), tried)
        assertEquals("only the failed batch is left", 1, store.pending().size)
    }

    @Test
    fun theStoreIsBounded() {
        val store = MobileLateMessages(tmp.newFolder("late"))
        repeat(15) { store.save("tok-$it", byteArrayOf(it.toByte())) }

        assertEquals(10, store.pending().size)
        val tokens = ArrayList<String>()
        store.resend(store.pending()) { _, token -> tokens.add(token); true }
        assertEquals("the newest batches are kept", (5 until 15).map { "tok-$it" }, tokens)
    }
}
