package com.replayfy.android.internal.mobile

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import com.replayfy.android.ReplayConfig
import com.replayfy.android.internal.OptOutStore
import com.replayfy.android.internal.console.ConsoleCapture
import com.replayfy.android.internal.crash.NativeCrashHandler
import com.replayfy.android.internal.privacy.PrivacyRegistry
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Orchestrates the reference mobile recording engine on Android:
 * session start, screenshot capture, binary message collection, and
 * lifecycle. Mirrors the iOS ReplayCore.
 *
 * Listeners call the `send*` methods, which encode a binary message
 * and hand it to the collector. Screenshots flow independently to
 * /v1/mobile/images.
 */
class ReplayCore private constructor() {
    companion object {
        @JvmStatic
        val shared = ReplayCore()

        /** Wrapper-SDK tag (e.g. "react-native") added to the /start payload
         *  so the dashboard can show "Captured by …" while the platform stays
         *  ios/android. Set before start(). */
        @Volatile
        @JvmStatic
        var framework: String? = null
    }

    private var transport: MobileTransport? = null
    private var collector: MobileCollector? = null
    private var screenshots: MobileScreenshots? = null
    private var touch: MobileTouchCapture? = null
    private var perf: MobilePerfMonitor? = null
    private var consoleCapture: ConsoleCapture? = null
    // Live-presence socket (mirrors the web SDK's presence.ts): keeps this
    // session on the dashboard's online list for its lifetime; the backend
    // LiveGateway flips the EndUser online on connect / offline 10s after
    // disconnect. Best-effort — never blocks capture.
    private var presence: MobilePresence? = null
    private val startExecutor = Executors.newSingleThreadExecutor()

    /**
     * Messages emitted before the async `/start` round-trip completes
     * (the launch screen's viewComponent, or an `identify` / `track` /
     * `log` made in the host's `onCreate`) are queued here and drained
     * into the collector once the session token arrives. The reference
     * tracker buffers pre-start messages the same way — without this
     * they'd be dropped because `collector` is still null. Guarded by its
     * own monitor and bounded so a never-completing `/start` can't grow
     * it without limit.
     */
    private val preStart = ArrayList<ByteArray>()
    @Volatile private var ready = false
    @Volatile private var started = false
    // ReplayConfig.autoScreenName — gates the auto screen-name emission from
    // the Activity lifecycle callback.
    @Volatile private var autoScreenName = true

    @Volatile private var currentActivity: Activity? = null
    @Volatile var sessionId: String? = null
    // Project key + ingest host, kept so the presence socket can authenticate
    // with the same credentials as the HTTP transport.
    @Volatile private var projectKey: String? = null
    @Volatile private var host: String? = null
    // Last identified distinct id (from identify → setUserId). Passed to the
    // presence socket so the dashboard attaches this session to the right user.
    @Volatile private var distinctId: String? = null
        private set
    @Volatile var startedAt: Long = 0
        private set

    // Dashboard-controlled capture toggles, set from the /start response.
    @Volatile private var captureConsole = true
    @Volatile private var captureNetwork = true

    /** Sensitive decor-view-coordinate rects to mask in screenshots. */
    var privacyRectsProvider: () -> List<Rect> = { emptyList() }

    fun start(app: Application, config: ReplayConfig) {
        // Idempotent: a second Replay.init (e.g. on an Activity recreate)
        // must not wire a second collector / screenshot timer / perf
        // sampler onto the same singleton.
        if (started) return
        // GDPR: honour an overall opt-out on the LIVE engine — never open a
        // session while opted out (the legacy facade checked this; the live
        // engine didn't).
        if (OptOutStore(app).overallOptOut) return
        started = true
        val projectKey = config.apiKey
        val host = config.apiHost
        this.projectKey = projectKey
        this.host = host
        autoScreenName = config.autoScreenName
        app.registerActivityLifecycleCallbacks(activityCallbacks)
        val transport = MobileTransport(host, projectKey)
        this.transport = transport

        startExecutor.execute {
            val resp = transport.start(deviceParams(app, projectKey)) ?: run {
                started = false // allow a later retry if /start failed
                return@execute
            }
            // Server sampling gate dropped this launch — do not record. Leave
            // `started` true so we don't re-roll on a spurious re-init.
            if (!resp.record) return@execute
            sessionId = resp.sessionID
            startedAt = System.currentTimeMillis()
            // Client config is the default; remote-config (the /start
            // response) overrides it when the server specifies a value.
            captureConsole = resp.captureConsole ?: config.captureConsole
            captureNetwork = resp.captureNetwork ?: config.captureNetwork

            // Open the live-presence socket now that we have a session id. Uses
            // the distinct id known so far (identify() called before /start
            // completed sets it); identify() afterwards calls updateDistinctId.
            presence = MobilePresence().also {
                it.start(host, projectKey, resp.sessionID, distinctId)
            }

            // Wire screenshot privacy masking to the PrivacyRegistry — the
            // same collectors the legacy capture path consults. Without this
            // the frames screenshotter masks nothing: addPrivacyView,
            // occludeAll*, applyOcclusion, occludeSensitiveScreen and the
            // bridge rects all register correctly but never reach the JPEGs.
            // Invoked once per frame on the capture (main) thread, so the
            // View-tree walk is safe.
            privacyRectsProvider = provider@{
                val root = currentActivity?.window?.decorView ?: return@provider emptyList()
                if (PrivacyRegistry.occludeAllScreen) {
                    // Whole-screen occlude (occludeSensitiveScreen): one box over
                    // the whole decor view. Drain any pending bridge rects so they
                    // don't bleed into a later non-full-screen frame.
                    PrivacyRegistry.consumePendingFrameRects()
                    return@provider listOf(Rect(0, 0, root.width, root.height))
                }
                PrivacyRegistry.sensitiveBounds(root) +
                    PrivacyRegistry.bulkBounds(root) +
                    PrivacyRegistry.composeBoundsRelativeTo(root) +
                    PrivacyRegistry.consumePendingFrameRects()
            }

            val collector = MobileCollector(transport)
            val screenshots = MobileScreenshots(
                transport,
                activityProvider = { currentActivity },
                privacyRects = { privacyRectsProvider() },
            ).apply { setSettings(resp.fps, resp.quality) }
            val touch = MobileTouchCapture { label, x, y, isSwipe, direction ->
                if (isSwipe) sendSwipe(label, x, y, direction) else sendClick(label, x, y)
            }

            val perf = MobilePerfMonitor(app) { name, value -> sendPerformance(name, value) }

            this.collector = collector
            this.screenshots = screenshots
            this.touch = touch
            this.perf = perf

            collector.start()
            // captureSnapshotPixels=false → events only, no frames archive.
            if (config.captureSnapshotPixels) screenshots.start()
            perf.start()
            currentActivity?.let { touch.attach(it) }
            // Uncaught-exception crash reporting.
            if (config.captureErrors) {
                MobileCrashHandler.install { name, reason, stack -> sendCrash(name, reason, stack) }
                // NDK / native-signal crashes (SIGSEGV/SIGBUS/SIGABRT/…) the
                // JVM handler can't see. install() also drains a record left
                // by a previous-process native crash. Optional — no-ops when
                // libreplay_crash.so wasn't packed. (Re-homed from the old
                // LegacyCore.autoBootstrap, which dropped these — #119.)
                try {
                    NativeCrashHandler(
                        context = app,
                        onRecoveredCrash = { rec -> sendCrash(rec.className, rec.message, rec.stack) },
                    ).install()
                } catch (t: Throwable) {
                    android.util.Log.w("ReplaySdk", "NDK signal handler init failed: ${t.message}")
                }
            }
            // Native console capture (System.out/err tee → log events). Gated
            // on the resolved captureConsole; re-homed from LegacyCore (#119).
            if (captureConsole) {
                val console = ConsoleCapture(emit = { e ->
                    sendLog(e.level, if (e.stack.isNullOrBlank()) e.message else "${e.message}\n${e.stack}")
                })
                console.start()
                consoleCapture = console
            }

            // Drain anything queued during the /start round-trip, then go
            // live. Holding the monitor across the flip keeps a concurrent
            // enqueue() from racing past the drain and losing its message.
            synchronized(preStart) {
                preStart.forEach { collector.add(it) }
                preStart.clear()
                ready = true
            }
        }
    }

    /**
     * Hand a message to the collector if the session is live, otherwise
     * buffer it until [start]'s drain runs. Bounded at 1000 to cap the
     * worst case where `/start` never returns.
     */
    private fun enqueue(msg: ByteArray) {
        synchronized(preStart) {
            val c = collector
            if (ready && c != null) c.add(msg)
            else if (preStart.size < 1000) preStart.add(msg)
        }
    }

    fun stop() {
        screenshots?.stop(); screenshots = null
        perf?.stop(); perf = null
        consoleCapture?.stop(); consoleCapture = null
        collector?.stop(); collector = null
        presence?.stop(); presence = null
        sessionId = null
    }

    // ── Message API (called by listeners) ────────────────────────────
    fun sendClick(label: String, x: Long, y: Long) =
        enqueue(MobileWire.click(label, x, y, now()))
    fun sendSwipe(label: String, x: Long, y: Long, direction: String) =
        enqueue(MobileWire.swipe(label, x, y, direction, now()))
    fun sendInput(value: String, masked: Boolean, label: String) =
        enqueue(MobileWire.input(value, masked, label, now()))
    fun sendPerformance(name: String, value: Long) =
        enqueue(MobileWire.performance(name, value, now()))
    fun sendLog(severity: String, content: String) {
        if (captureConsole) enqueue(MobileWire.log(severity, content, now()))
    }
    fun sendNetwork(type: String, method: String, url: String, request: String, response: String, status: Long, duration: Long) {
        if (captureNetwork) enqueue(MobileWire.networkCall(type, method, url, request, response, status, duration, now()))
    }
    fun sendCrash(name: String, reason: String, stacktrace: String) {
        enqueue(MobileWire.crash(name, reason, stacktrace, now()))
        // Synchronous — the process is about to die; an async flush wouldn't
        // finish sending.
        collector?.flushBlocking()
    }
    fun sendScreen(screenName: String, viewName: String, visible: Boolean) =
        enqueue(MobileWire.viewComponent(screenName, viewName, visible, now()))
    fun sendEvent(name: String, payload: String) =
        enqueue(MobileWire.event(name, payload, now()))
    fun setUserId(id: String) {
        distinctId = id
        presence?.updateDistinctId(id)
        enqueue(MobileWire.userId(id, now()))
    }
    fun sendMetadata(key: String, value: String) =
        enqueue(MobileWire.metadata(key, value, now()))

    private fun now() = System.currentTimeMillis()

    // ── Activity tracking + lifecycle ────────────────────────────────
    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
            touch?.attach(activity)
            // Resume screen capture on return to foreground.
            screenshots?.resume()
            // Screen tracking → viewComponent message with the activity
            // class name (drives the dashboard Screens tab + route).
            if (autoScreenName) {
                val name = activity.javaClass.simpleName
                sendScreen(name, name, true)
            }
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivity === activity) {
                sendPerformance("background", 1)
                // Pause capture while backgrounded — shooting an invisible
                // window just inflates the archive (and wastes storage +
                // bandwidth). pause() flushes what's pending.
                screenshots?.pause()
                collector?.flush()
            }
        }
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // ── /start device params ─────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun deviceParams(app: Application, projectKey: String): JSONObject {
        val dm: DisplayMetrics = app.resources.displayMetrics
        val pkg = try { app.packageManager.getPackageInfo(app.packageName, 0) } catch (e: Exception) { null }
        val revID = pkg?.versionCode?.toString() ?: "N/A"
        val uuid = try {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) { "unknown" }
        return JSONObject().apply {
            put("projectKey", projectKey)
            put("platform", "android")
            put("trackerVersion", "2.0.0")
            framework?.takeIf { it.isNotEmpty() }?.let { put("framework", it) }
            put("revID", revID)
            put("userUUID", uuid)
            put("userOSVersion", Build.VERSION.RELEASE ?: "")
            put("userDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("userDeviceType", Build.MODEL ?: "")
            put("timestamp", now())
            put("timezone", timezone())
            put("width", dm.widthPixels)
            put("height", dm.heightPixels)
            // If identify() ran before start, send the id so the sampling gate
            // can honour alwaysRecordIdentified.
            distinctId?.let { put("distinctId", it) }
        }
    }

    private fun timezone(): String {
        val offset = java.util.TimeZone.getDefault().rawOffset
        val sign = if (offset >= 0) "+" else "-"
        val abs = Math.abs(offset)
        return String.format("UTC%s%02d:%02d", sign, abs / 3_600_000, (abs % 3_600_000) / 60_000)
    }
}
