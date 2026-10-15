package com.replayfy.android.internal.mobile

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.replayfy.android.ReplayConfig
import com.replayfy.android.ReplayMaskStyle
import com.replayfy.android.internal.privacy.PrivacyRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * What /v1/mobile/start's privacy block does to the engine: the dashboard's
 * "always mask input values" is applied before the first frame, cleared by a
 * start without it, and never touches the app's own switch. Also the
 * engine's guards on host-fed (Flutter) frames: the whole-screen occlusion
 * and FLAG_SECURE cover them whatever rects the host sent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteMaskingStartTest {
    private lateinit var server: FakeIngestServer
    private lateinit var app: Application
    private lateinit var core: ReplayCore
    private val sessionsLive = AtomicInteger()

    class HomeActivity : Activity()

    class SecureActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Before
    fun setUp() {
        resetRegistry()
        server = FakeIngestServer()
        app = ApplicationProvider.getApplicationContext()
        core = ReplayCore()
        core.addVerificationListener { sessionsLive.incrementAndGet() }
    }

    @After
    fun tearDown() {
        core.cancelSession()
        server.close()
        resetRegistry()
    }

    private fun resetRegistry() {
        PrivacyRegistry.clear()
        PrivacyRegistry.occludeAllTextFields = false
        PrivacyRegistry.remoteOccludeAllTextFields = false
        PrivacyRegistry.occludeAllTextViews = false
        PrivacyRegistry.occludeAllScreen = false
        PrivacyRegistry.maskStyle = ReplayMaskStyle.OVERLAY
    }

    private fun config(nativeCapture: Boolean) = ReplayConfig(
        apiKey = "rpl_pk_test",
        apiHost = server.url,
        captureConsole = false,
        captureErrors = false,
        captureSnapshotPixels = nativeCapture,
    )

    private fun launch(config: ReplayConfig, activity: Class<out Activity> = HomeActivity::class.java) {
        core.start(app, config)
        Robolectric.buildActivity(activity).setup()
        awaitSessions(1)
    }

    private fun awaitSessions(n: Int) {
        waitUntil(10_000) { sessionsLive.get() >= n }
        assertTrue("session $n went live", sessionsLive.get() >= n)
    }

    private fun startResponse(privacy: String?): MobileStartResponse? {
        server.startPrivacy = privacy
        return MobileTransport(server.url, "rpl_pk_test").start(JSONObject())
    }

    // ── Parsing ──────────────────────────────────────────────────────

    @Test
    fun theStartResponseCarriesTheDashboardFlagWhenSent() {
        assertEquals(true, startResponse("""{"occludeAllTextFields":true}""")?.occludeAllTextFields)
        assertEquals(false, startResponse("""{"occludeAllTextFields":false}""")?.occludeAllTextFields)
    }

    @Test
    fun aStartResponseWithoutTheFlagParsesAsNotSent() {
        val none = startResponse(null)
        assertEquals("s1", none?.sessionID)
        assertNull(none?.occludeAllTextFields)
        assertNull(startResponse("{}")?.occludeAllTextFields)
        assertNull("not an object", startResponse("true")?.occludeAllTextFields)
    }

    // ── The engine ───────────────────────────────────────────────────

    @Test
    fun theDashboardFlagIsOnBeforeTheFirstFrameAndANextStartWithoutItClearsIt() {
        server.startPrivacy = """{"occludeAllTextFields":true}"""
        var atFirstFrame: Boolean? = null
        core.externalPrivacyRectsProvider = {
            if (atFirstFrame == null) atFirstFrame = PrivacyRegistry.remoteOccludeAllTextFields
            emptyList()
        }
        launch(config(nativeCapture = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400)) // the capture timer

        assertEquals("the first frame's masks already include it", true, atFirstFrame)
        assertTrue(PrivacyRegistry.remoteOccludeAllTextFields)

        server.startPrivacy = null
        core.startNewSession()
        awaitSessions(2)
        assertFalse("switched off on the dashboard", PrivacyRegistry.remoteOccludeAllTextFields)
    }

    @Test
    fun aStartNeverClearsTheAppsOwnSwitch() {
        PrivacyRegistry.occludeAllTextFields = true
        server.startPrivacy = """{"occludeAllTextFields":false}"""
        launch(config(nativeCapture = false))

        assertTrue(PrivacyRegistry.occludeAllTextFields)
        assertFalse(PrivacyRegistry.remoteOccludeAllTextFields)
        assertTrue(PrivacyRegistry.textFieldsOccluded)
    }

    // ── Host-fed frames ──────────────────────────────────────────────

    private fun whitePng(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            .compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** Report one host frame and read it back from the server. */
    private fun hostFrame(): Bitmap {
        core.submitFrame(whitePng(), emptyList())
        val shots = core.liveScreenshots ?: error("no live session")
        waitUntil { shots.pendingCount == 1 }
        shots.flush(all = true)
        waitUntil { server.frameCount == 1 }
        val archive = server.on("/v1/mobile/images").single().body
        val jpeg = frameJpegs(archive).single()
        return requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
    }

    private fun luma(px: Int) = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3

    private fun everyPixelNear(b: Bitmap, target: Int) {
        for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) {
            assertTrue("($x,$y) luma ${luma(b.getPixel(x, y))} ≈ $target", Math.abs(luma(b.getPixel(x, y)) - target) <= 8)
        }
    }

    @Test
    fun theWholeScreenOcclusionCoversHostFramesWithOneSolidBox() {
        launch(config(nativeCapture = false))
        PrivacyRegistry.maskStyle = ReplayMaskStyle.BLUR // the cover is solid whatever the style
        PrivacyRegistry.occludeAllScreen = true

        val frame = hostFrame()
        assertEquals(60, frame.width)
        everyPixelNear(frame, 56)
    }

    @Test
    fun aSecureWindowBlacksOutHostFrames() {
        launch(config(nativeCapture = false), SecureActivity::class.java)

        everyPixelNear(hostFrame(), 0)
    }

    @Test
    fun anOrdinaryHostFrameKeepsItsPixels() {
        launch(config(nativeCapture = false))

        everyPixelNear(hostFrame(), 255)
    }

    // ── Host-reported inputs (Replay.trackInput: React Native / Flutter) ──

    @Test
    fun aHostInputIsMaskedByTheRulesTheHostCannotSee() {
        launch(config(nativeCapture = false))
        assertFalse("nothing occluded", core.hostInputMasked())

        PrivacyRegistry.remoteOccludeAllTextFields = true
        assertTrue("dashboard force", core.hostInputMasked())
        PrivacyRegistry.remoteOccludeAllTextFields = false

        PrivacyRegistry.occludeAllTextFields = true
        assertTrue("app occludeAllTextFields", core.hostInputMasked())
        PrivacyRegistry.occludeAllTextFields = false

        PrivacyRegistry.occludeAllScreen = true
        assertTrue("whole screen occluded", core.hostInputMasked())
        PrivacyRegistry.occludeAllScreen = false

        assertFalse(core.hostInputMasked())
    }

    @Test
    fun aHostInputIsMaskedUnderASecureWindow() {
        launch(config(nativeCapture = false), SecureActivity::class.java)
        assertTrue(core.hostInputMasked())
    }
}
