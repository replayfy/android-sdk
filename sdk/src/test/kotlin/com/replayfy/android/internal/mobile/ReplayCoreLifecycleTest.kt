package com.replayfy.android.internal.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.replayfy.android.BuildConfig
import com.replayfy.android.ReplayConfig
import com.replayfy.android.internal.OptOutStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine's app-lifecycle handling, driven through real Activity lifecycles
 * against a local ingest server: what goes out when the app goes to the
 * background and comes back, and what keeps frames held (an excluded screen,
 * a host pause, the schematic opt-out) across those transitions and across a
 * session rotation.
 */
@RunWith(RobolectricTestRunner::class)
class ReplayCoreLifecycleTest {
    private lateinit var server: FakeIngestServer
    private lateinit var app: Application
    private lateinit var core: ReplayCore
    private val sessionsLive = AtomicInteger()

    @Before
    fun setUp() {
        server = FakeIngestServer()
        app = ApplicationProvider.getApplicationContext()
        core = ReplayCore()
        core.addVerificationListener { sessionsLive.incrementAndGet() }
    }

    @After
    fun tearDown() {
        core.cancelSession()
        server.close()
        engine = null
        engineConfig = null
    }

    // ── Background / foreground ──────────────────────────────────────

    @Test
    fun goingToTheBackgroundSendsHostFedFramesAndMessagesBufferedSinceTheLastBatch() {
        val home = launch(config(nativeCapture = false))
        repeat(5) { shots().appendFrame(ByteArray(64)) }
        core.sendEvent("checkout_started", "{}")

        home.pause().stop()

        waitUntil { server.frameCount == 5 && server.messagesContain("checkout_started") }
        assertEquals("the partial batch goes out at once", 5, server.frameCount)
        assertTrue(server.messagesContain("checkout_started"))
        assertEquals(0, shots().pendingCount)
    }

    @Test
    fun goingToTheBackgroundSendsEveryQueuedMessageBatch() {
        val home = launch(config(nativeCapture = false))
        // Offline while 12 × 60 KB events queue up: more than one 500 KB batch.
        server.failMessages = true
        val payload = "x".repeat(60_000)
        repeat(12) { core.sendEvent("bulk_$it", payload) }
        waitUntil { (core.liveCollector?.waitingCount ?: 0) >= 12 }
        server.failMessages = false

        home.pause().stop()

        // Well inside the 5 s flush timer: only the background drain can do it.
        waitUntil(2_000) { (0 until 12).all { server.messagesContain("bulk_$it") } }
        assertTrue("the whole queue goes out, not one batch", (0 until 12).all { server.messagesContain("bulk_$it") })
    }

    @Test
    fun anActivityPauseWithoutBackgroundingLeavesCaptureRunning() {
        val home = launch(config(nativeCapture = true))
        assertTrue(shots().isCapturing)

        home.pause() // a permission dialog in front: paused, never stopped

        assertTrue("only the app going to the background pauses capture", shots().isCapturing)
        home.resume()
        assertTrue(shots().isCapturing)
    }

    @Test
    fun returningToTheForegroundSendsWhatTheBackgroundCouldNotGetOut() {
        val home = launch(config(nativeCapture = false))
        server.failImages = true // the background cuts the upload off
        repeat(5) { shots().appendFrame(ByteArray(64)) }
        home.pause().stop()
        waitUntil { !shots().isDraining && shots().pendingCount == 5 }
        assertEquals("nothing is dropped", 5, shots().pendingCount)
        server.failImages = false

        home.restart().resume()

        waitUntil { server.frameCount == 5 }
        assertEquals("leftovers go out on return, not at the next full batch", 5, server.frameCount)
    }

    @Test
    fun anEngineStartedAfterItsActivityIsOnScreenTellsNavigationFromBackgrounding() {
        // React Native / Flutter: the engine boots after the Activity resumed.
        val first = Robolectric.buildActivity(HomeActivity::class.java).setup()
        core.start(app, config(nativeCapture = true))
        awaitSessions(1)
        assertTrue(shots().isCapturing)

        // In-app navigation: the first Activity pauses, the next one opens,
        // then the first stops.
        first.pause()
        Robolectric.buildActivity(SecondActivity::class.java).setup()
        first.stop()

        assertTrue("navigating isn't going to the background", shots().isCapturing)
    }

    @Test
    fun anEngineStartedFromALaterActivityTellsItsLaunchFromBackgrounding() {
        // Replay.init in MainActivity.onCreate, after a splash screen. The
        // splash started before the engine was listening, and stops once Main
        // is in front.
        val splash = Robolectric.buildActivity(HomeActivity::class.java).setup()
        splash.pause()
        markPausedLikeADevice() // Robolectric leaves a paused Activity's record unpaused
        engine = core
        engineConfig = config(nativeCapture = true)
        Robolectric.buildActivity(InitInCreateActivity::class.java).setup()
        splash.stop()
        awaitSessions(1)

        assertTrue("the app is in front: capture runs", shots().isCapturing)
    }

    @Test
    fun anAppBackgroundedBeforeItsSessionIsLiveStartsCapturingOnlyWhenItReturns() {
        val gate = java.util.concurrent.CountDownLatch(1)
        server.startGate = gate // the first /start is slow
        core.start(app, config(nativeCapture = true))
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        home.pause().stop()

        gate.countDown()
        awaitSessions(1)
        assertFalse("no capture of an invisible window", shots().isCapturing)

        home.restart().resume()
        assertTrue(shots().isCapturing)
    }

    @Test
    fun hostFedCaptureNeverStartsTheNativeTimer() {
        val home = launch(config(nativeCapture = false))
        core.excludeScreen("Secret")

        home.pause().stop()
        home.restart().resume()
        assertFalse("not on a return from the background", shots().isCapturing)
        core.pauseCapture()
        core.resumeCapture()
        assertFalse("not on resumeRecording", shots().isCapturing)
        core.tagScreenManual("Secret")
        core.tagScreenManual("Home")
        assertFalse("not on leaving an excluded screen", shots().isCapturing)
    }

    // ── Holds ────────────────────────────────────────────────────────

    @Test
    fun aHostPauseHoldsAcrossBackgroundAndForeground() {
        val home = launch(config(nativeCapture = true))
        core.pauseCapture()
        assertFalse(shots().isCapturing)

        home.pause().stop()
        home.restart().resume()
        assertFalse("pauseRecording still holds after a return", shots().isCapturing)

        home.pause().stop()
        core.resumeCapture() // the host resumes while the app is in the background
        assertFalse("no capture of an invisible window", shots().isCapturing)
        home.restart().resume()
        assertTrue(shots().isCapturing)
    }

    @Test
    fun leavingAnExcludedScreenDoesNotLiftAHostPause() {
        launch(config(nativeCapture = true))
        core.excludeScreen("Secret")
        core.pauseCapture()

        core.tagScreenManual("Secret")
        core.tagScreenManual("Home")

        assertFalse("pauseRecording still holds", shots().isCapturing)
        core.resumeCapture()
        assertTrue(shots().isCapturing)
    }

    @Test
    fun heldFramesRefuseHostFedFrames() {
        launch(config(nativeCapture = false))
        core.excludeScreen("Secret")

        core.tagScreenManual("Secret")
        core.submitFrame(png(), emptyList())
        core.tagScreenManual("Home")
        core.pauseCapture()
        core.submitFrame(png(), emptyList())
        core.resumeCapture()
        core.submitFrame(png(), emptyList()) // the control: nothing holds this one

        waitUntil { shots().pendingCount == 1 }
        Thread.sleep(200)
        assertEquals("only the frame submitted while nothing held it is kept", 1, shots().pendingCount)
    }

    @Test
    fun hostFedFramesAreRefusedWhileTheAppIsInTheBackground() {
        val home = launch(config(nativeCapture = false))
        home.pause().stop()

        // Flutter's capture timer keeps running in a backgrounded process.
        core.submitFrame(png(), emptyList())
        Thread.sleep(200)
        assertEquals("nothing on screen, nothing to record", 0, shots().pendingCount)

        home.restart().resume()
        core.submitFrame(png(), emptyList())
        waitUntil { shots().pendingCount == 1 }
        assertEquals(1, shots().pendingCount)
    }

    @Test
    fun optingBackIntoSchematicRecordingDoesNotLiftAHostPause() {
        launch(config(nativeCapture = true))
        core.pauseCapture() // pauseRecording on a PIN screen

        core.setSchematicOptOut(true)
        core.setSchematicOptOut(false) // e.g. a consent sync on every resume

        assertFalse("pauseRecording still holds", shots().isCapturing)
        core.resumeCapture()
        assertTrue(shots().isCapturing)
    }

    @Test
    fun theSchematicOptOutHoldsFramesUntilItIsLifted() {
        launch(config(nativeCapture = true))

        core.setSchematicOptOut(true)
        assertFalse(shots().isCapturing)
        assertTrue("persisted", OptOutStore(app).schematicOptOut)
        core.setSchematicOptOut(false)
        assertTrue(shots().isCapturing)
    }

    @Test
    fun aPersistedSchematicOptOutHoldsFramesFromTheStart() {
        OptOutStore(app).schematicOptOut = true
        launch(config(nativeCapture = true))

        assertFalse(shots().isCapturing)
        core.submitFrame(png(), emptyList())
        Thread.sleep(200)
        assertEquals(0, shots().pendingCount)
    }

    @Test
    fun aManuallyTaggedExcludedScreenStaysExcludedAcrossReturns() {
        val home = launch(config(nativeCapture = true))
        core.excludeScreen("Secret")
        core.tagScreenManual("Secret") // in-app navigation inside the one Activity
        assertFalse(shots().isCapturing)

        repeat(2) {
            home.pause().stop()
            home.restart().resume()
            assertFalse("still excluded after return ${it + 1}", shots().isCapturing)
        }
    }

    @Test
    fun aManuallyTaggedExcludedScreenStaysExcludedAcrossReturnsWithFragments() {
        core.start(app, config(nativeCapture = true))
        val host = Robolectric.buildActivity(FragmentHostActivity::class.java).setup()
        awaitSessions(1)
        core.excludeScreen("Secret")
        core.tagScreenManual("Secret")
        assertFalse(shots().isCapturing)

        repeat(2) {
            host.pause().stop()
            host.restart().resume()
            assertFalse("the Fragment's auto-tag didn't replace it on return ${it + 1}", shots().isCapturing)
        }
    }

    @Test
    fun aManuallyTaggedExcludedScreenStaysExcludedAfterVisitingAnotherActivity() {
        val home = launch(config(nativeCapture = true))
        core.excludeScreen("Secret")
        core.tagScreenManual("Secret")

        // An in-process Activity in front (a payment sheet, a picker), then back.
        home.pause()
        val other = Robolectric.buildActivity(SecondActivity::class.java).setup()
        home.stop()
        assertTrue("the other Activity is not excluded", shots().isCapturing)
        other.pause()
        home.restart().resume()
        other.stop().destroy()

        assertFalse("back on the excluded Secret screen", shots().isCapturing)
    }

    @Test
    fun aRotationKeepsAManuallyTaggedExcludedScreenExcluded() {
        val home = launch(config(nativeCapture = true))
        core.excludeScreen("Secret")
        core.tagScreenManual("Secret") // a single-Activity (Compose) app's screen

        home.recreate()

        assertFalse("the recreated Activity shows the same screen", shots().isCapturing)
    }

    @Test
    fun aRotationKeepsAManuallyTaggedExcludedScreenExcludedWithFragments() {
        core.start(app, config(nativeCapture = true))
        val host = Robolectric.buildActivity(RestoringFragmentHostActivity::class.java).setup()
        awaitSessions(1)
        core.excludeScreen("Secret")
        core.tagScreenManual("Secret")

        host.recreate() // its Fragment is restored with it

        assertFalse("the restored Fragment shows the same screen", shots().isCapturing)
    }

    @Test
    fun aFragmentThatTagsItsScreenOnResumeDoesNotHideTheNextScreenAfterAReturn() {
        engine = core
        core.start(app, config(nativeCapture = true, excluded = listOf("Secret")))
        val host = Robolectric.buildActivity(TaggingFragmentHostActivity::class.java).setup()
        awaitSessions(1)
        assertFalse("its own tag excludes it", shots().isCapturing)

        host.pause().stop()
        host.restart().resume() // the Fragment re-tags itself
        assertFalse(shots().isCapturing)
        host.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, HomeFragment()).commitNow()

        assertTrue("the next Fragment is named, so the exclusion lifts", shots().isCapturing)
    }

    // ── Session rotation ─────────────────────────────────────────────

    @Test
    fun aRotationKeepsRuntimeExclusionsAndTheExcludedScreenInFront() {
        launch(config(nativeCapture = true, excluded = listOf("Settings")))
        core.excludeScreen("Secret")
        core.tagScreenManual("Secret")
        assertFalse(shots().isCapturing)

        core.startNewSession()
        awaitSessions(2)

        assertFalse("the new session doesn't capture the excluded screen", shots().isCapturing)
        core.tagScreenManual("Home")
        assertTrue(shots().isCapturing)
        core.tagScreenManual("Secret")
        assertFalse("the runtime exclusion survived the rotation", shots().isCapturing)
    }

    @Test
    fun aReturnAfterTheShortBreakRotatesWithTheExcludedScreenStillHeld() {
        val home = launch(config(nativeCapture = true))
        core.setAllowShortBreak(false, 0)
        core.excludeScreen("Secret")
        core.tagScreenManual("Secret")

        home.pause().stop()
        home.restart().resume()
        awaitSessions(2)

        assertFalse("the new session doesn't capture the excluded screen", shots().isCapturing)
        core.liveCollector?.flush()
        waitUntil { server.messagesContain("Secret", auth = "Bearer tok2") }
        assertTrue("the new session is told which screen it starts on", server.messagesContain("Secret", auth = "Bearer tok2"))
    }

    // ── Session end ──────────────────────────────────────────────────

    @Test
    fun aSessionEndTheServerNeverGotIsResentByTheNextLaunchWithoutEndingItAgain() {
        launch(config(nativeCapture = false))
        core.sendEvent("signed_out", "{}")
        server.failLate = true // offline when the session ends

        core.stop()
        val lateDir = File(app.filesDir, "replayfy-late")
        waitUntil { lateDir.listFiles().orEmpty().any { it.name.endsWith(".dat") } }
        server.failLate = false

        // The next launch (a fresh engine in the same app sandbox).
        val next = ReplayCore()
        next.start(app, config(nativeCapture = false))
        try {
            waitUntil { server.messagesContain("signed_out", auth = "Bearer tok1") }
            assertTrue("resent to the session it belongs to", server.messagesContain("signed_out", auth = "Bearer tok1"))
            // The server has long since ended that session on its idle timer;
            // another end signal would re-finalize it from frames it no longer
            // keeps, replacing its replay with an empty one.
            assertTrue("as a plain batch, not a second session end", server.on("/v1/mobile/late").none { it.auth == "Bearer tok1" })
            waitUntil { lateDir.listFiles().orEmpty().none { it.name.endsWith(".dat") } }
            assertTrue("deleted once delivered", lateDir.listFiles().orEmpty().none { it.name.endsWith(".dat") })
        } finally {
            next.cancelSession()
        }
    }

    @Test
    fun stopAndWaitReturnsOnlyOnceTheSessionsLastDataIsOut() {
        launch(config(nativeCapture = false))
        repeat(5) { shots().appendFrame(ByteArray(64)) }
        core.sendEvent("signed_out", "{}")

        // Replay.stopApplicationAndUploadData, just before System.exit.
        core.stopAndWait(5_000)

        assertEquals("the last frames are out", 5, server.frameCount)
        assertTrue("so is the session end", server.on("/v1/mobile/late").any { it.auth == "Bearer tok1" })
        assertTrue(server.messagesContain("signed_out"))
    }

    @Test
    fun sessionsReportTheRealSdkVersion() {
        launch(config(nativeCapture = false))
        val start = JSONObject(String(server.on("/v1/mobile/start").first().body))
        assertEquals(BuildConfig.SDK_VERSION, start.getString("trackerVersion"))
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun config(nativeCapture: Boolean, excluded: List<String> = emptyList()) = ReplayConfig(
        apiKey = "rpl_pk_test",
        apiHost = server.url,
        captureConsole = false,
        captureErrors = false,
        captureSnapshotPixels = nativeCapture,
        excludedScreens = excluded,
    )

    /** Start the engine the way a native app does (before its first
     *  Activity), open one Activity, and wait for the session to go live.
     *  Tests send it to the background with `pause().stop()` and bring it back
     *  with `restart().resume()` (a stopped Activity restarts; a plain start()
     *  would leave it marked stopped, so its next stop would never dispatch). */
    private fun launch(config: ReplayConfig): ActivityController<HomeActivity> {
        core.start(app, config)
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        awaitSessions(1)
        return home
    }

    private fun awaitSessions(n: Int) {
        waitUntil(10_000) { sessionsLive.get() >= n }
        assertTrue("session $n went live", sessionsLive.get() >= n)
    }

    private fun shots(): MobileScreenshots = core.liveScreenshots ?: error("no live session")

    /** A real 1×1 PNG, as Flutter's Dart-side capture would send. */
    private fun png(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    /** On a device, ActivityThread marks a paused Activity's record paused;
     *  Robolectric leaves it unpaused, which would make it look resumed to the
     *  engine's start-up lookup. */
    private fun markPausedLikeADevice() {
        val atClass = Class.forName("android.app.ActivityThread")
        val thread = atClass.getMethod("currentActivityThread").invoke(null)
        val records = atClass.getDeclaredField("mActivities").apply { isAccessible = true }.get(thread) as Map<*, *>
        for (record in records.values) {
            record!!.javaClass.getDeclaredField("paused").apply { isAccessible = true }.setBoolean(record, true)
        }
    }

    companion object {
        /** For the test Activities and Fragments that call into the engine
         *  from their own lifecycle methods. */
        @JvmStatic var engine: ReplayCore? = null
        @JvmStatic var engineConfig: ReplayConfig? = null
    }

    class HomeActivity : Activity()

    class SecondActivity : Activity()

    /** Calls Replay.init from its onCreate. */
    class InitInCreateActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            engine!!.start(application, engineConfig!!)
        }
    }

    class FragmentHostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            supportFragmentManager.beginTransaction().add(android.R.id.content, HomeFragment()).commitNow()
        }
    }

    /** Adds its Fragment only on a first create; a recreate restores it. */
    class RestoringFragmentHostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction().add(android.R.id.content, HomeFragment()).commitNow()
            }
        }
    }

    class TaggingFragmentHostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction().add(android.R.id.content, SecretFragment()).commitNow()
            }
        }
    }

    class HomeFragment : Fragment()

    /** Names its own screen on every resume, a common screen-view pattern. */
    class SecretFragment : Fragment() {
        override fun onResume() {
            super.onResume()
            engine!!.tagScreenManual("Secret")
        }
    }
}
