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
import com.replayfy.android.internal.crash.CrashHandler
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
    // Opt-in text-input observation (Replay.addObservedInput) — reports an
    // observed field's value on editing-end. Always live (not tied to a single
    // start); sendInput's enqueue gates on recording state.
    private val input = MobileInputCapture { value, masked, label -> sendInput(value, masked, label) }
    private var perf: MobilePerfMonitor? = null
    private var consoleCapture: ConsoleCapture? = null
    // Retained from the first start() so startNewSession() can restart cleanly.
    @Volatile private var application: Application? = null
    @Volatile private var lastConfig: ReplayConfig? = null
    private val verificationListeners =
        java.util.concurrent.CopyOnWriteArrayList<Runnable>()
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
    @Volatile private var captureNetworkHeaders = true
    @Volatile private var captureNetworkBodies = true

    /** Sensitive decor-view-coordinate rects to mask in screenshots. */
    var privacyRectsProvider: () -> List<com.replayfy.android.MaskRect> = { emptyList() }

    /**
     * Additional host-supplied rects, composed into [privacyRectsProvider] by
     * [start]. Unlike [privacyRectsProvider] (which start assigns once the
     * session opens), this is owned by the host and never overwritten — the
     * integration point for hosts that compute occlusion off the View tree
     * (React Native / Flutter, which feed it decor-view-pixel rects pulled
     * from JS/Dart). Empty by default.
     */
    var externalPrivacyRectsProvider: () -> List<com.replayfy.android.MaskRect> = { emptyList() }

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
        application = app
        lastConfig = config
        app.registerActivityLifecycleCallbacks(activityCallbacks)
        // registerActivityLifecycleCallbacks does NOT replay the resume that
        // already happened. A host that calls init() after its Activity is
        // already resumed (React Native / Flutter plugins boot from a method
        // channel, post-onResume) would otherwise leave currentActivity null —
        // and the screenshotter + tap tracker both gate on it. Seed it.
        if (currentActivity == null) currentActivity = resolveResumedActivity()
        // Activities created before init() never replay onActivityCreated, so
        // seed Fragment tracking for the one already on screen.
        currentActivity?.let { registerFragmentTracking(it) }
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
            // API value wins when present; otherwise fall back to local config
            // (so clients can remote-configure from the dashboard, but local
            // config still applies when the server is silent).
            captureNetworkHeaders = resp.captureNetworkHeaders ?: config.captureHeaders
            captureNetworkBodies = resp.captureNetworkBodies ?: config.captureBodies

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
                val style = PrivacyRegistry.maskStyle
                if (PrivacyRegistry.occludeAllScreen) {
                    // Whole-screen occlude (occludeSensitiveScreen): one box over
                    // the whole decor view. Drain any pending bridge rects so they
                    // don't bleed into a later non-full-screen frame.
                    PrivacyRegistry.consumePendingFrameRects()
                    return@provider listOf(
                        com.replayfy.android.MaskRect(Rect(0, 0, root.width, root.height), style))
                }
                // Native/bulk sources render with the global style; the bridge
                // rects (React Native / Flutter) carry their own per-region style.
                (PrivacyRegistry.sensitiveBounds(root) +
                    PrivacyRegistry.bulkBounds(root) +
                    PrivacyRegistry.composeBoundsRelativeTo(root))
                    .map { com.replayfy.android.MaskRect(it, style) } +
                    PrivacyRegistry.consumePendingFrameRects() +
                    externalPrivacyRectsProvider()
            }

            val collector = MobileCollector(transport)
            val screenshots = MobileScreenshots(
                transport,
                activityProvider = { currentActivity },
                privacyRects = { privacyRectsProvider() },
            ).apply { setSettings(resp.fps, resp.quality) }
            val touch = MobileTouchCapture { label, x, y, kind, direction ->
                // kind ∈ tap / swipe / long_press — routed to click / swipe /
                // gesture by reportInteraction.
                reportInteraction(kind, label, x, y, direction)
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
            // Flutter hosts pass captureTouch=false — the Dart layer reports
            // taps with the real widget label via reportInteraction (the native
            // view tree only ever sees the one FlutterView).
            if (config.captureTouch) currentActivity?.let { touch.attach(it) }
            // Uncaught-exception crash reporting.
            if (config.captureErrors) {
                // JVM crashes — disk-backed so the record survives the dying
                // process and is recovered + sent on the NEXT launch (matches
                // iOS's PLCrashReporter disk+recover model; a synchronous
                // in-session send loses crashes that die before the batch
                // flushes). install() also drains a previous-process record
                // and chains to the app's handler (Crashlytics/Sentry fire).
                CrashHandler(
                    context = app,
                    onRecoveredCrash = { rec -> sendCrash(rec.className, rec.message, rec.stack) },
                ).install()
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
            // Session is live — notify onboarding/verification listeners.
            verificationListeners.forEach { runCatching { it.run() } }
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

    // ── Lifecycle controls (facade re-homes, #119) ───────────────────
    /** Pause periodic screenshot capture; events keep flowing. */
    fun pauseCapture() { screenshots?.pause() }

    /** Resume periodic screenshot capture. */
    fun resumeCapture() { screenshots?.resume() }

    /** Ingest a Flutter Dart-side frame (PNG bytes) into the frames archive.
     *  Used when native capture is disabled for Flutter (Android's PixelCopy
     *  can't read the SurfaceView). */
    fun submitFrame(png: ByteArray, rects: List<com.replayfy.android.MaskRect>) {
        screenshots?.submitFrame(png, rects)
    }

    /** Toggle auto screen-name tagging (Activity lifecycle) at runtime. */
    fun setAutoScreenName(enabled: Boolean) { autoScreenName = enabled }

    /** End the current session and immediately start a fresh one using the
     *  config retained from the first start(). No-op before start() runs. */
    fun startNewSession() {
        val app = application ?: return
        val cfg = lastConfig ?: return
        stop()
        start(app, cfg)
    }

    fun addVerificationListener(listener: Runnable) { verificationListeners.add(listener) }

    fun removeVerificationListener(listener: Runnable) { verificationListeners.remove(listener) }

    // ── Message API (called by listeners) ────────────────────────────
    /**
     * Entry point for host-reported interactions (the Flutter plugin's
     * Dart-side gesture capture). `kind` is "tap" / "long_press" / "swipe";
     * a long-press records as a click (the dashboard keys gestures off the
     * label + coordinates), and swipes carry their direction.
     */
    fun reportInteraction(kind: String, label: String, x: Long, y: Long, direction: String) {
        when (kind) {
            "swipe" -> sendSwipe(label, x, y, direction)
            "tap" -> sendClick(label, x, y)
            // long_press / double_tap / pinch → distinct gesture event.
            else -> enqueue(MobileWire.gesture(kind, label, x, y, direction, now()))
        }
    }
    fun sendClick(label: String, x: Long, y: Long) =
        enqueue(MobileWire.click(label, x, y, now()))
    fun sendSwipe(label: String, x: Long, y: Long, direction: String) =
        enqueue(MobileWire.swipe(label, x, y, direction, now()))
    fun sendInput(value: String, masked: Boolean, label: String) =
        enqueue(MobileWire.input(value, masked, label, now()))

    /** Opt an [android.widget.EditText] into input recording (reports on editing-end). */
    fun observeInput(editText: android.widget.EditText) = input.observe(editText)
    fun sendPerformance(name: String, value: Long) =
        enqueue(MobileWire.performance(name, value, now()))
    fun sendLog(severity: String, content: String) {
        if (captureConsole) enqueue(MobileWire.log(severity, content, now()))
    }
    fun sendNetwork(type: String, method: String, url: String, request: String, response: String, status: Long, duration: Long) {
        if (!captureNetwork) return
        // Single chokepoint for native + RN traffic — honour the granular
        // workspace toggles so disabled headers/bodies never leave the device.
        enqueue(MobileWire.networkCall(type, method, url, filterNetSide(request), filterNetSide(response), status, duration, now()))
    }

    /** Strip `headers`/`body` from a `{headers, body}` JSON side per the
     *  workspace toggles. No-op (returns the original) when both are enabled. */
    private fun filterNetSide(json: String): String {
        if (captureNetworkHeaders && captureNetworkBodies) return json
        return try {
            val obj = org.json.JSONObject(json)
            if (!captureNetworkHeaders) obj.put("headers", org.json.JSONObject())
            if (!captureNetworkBodies) obj.put("body", org.json.JSONObject.NULL)
            obj.toString()
        } catch (_: Throwable) {
            json
        }
    }
    fun sendCrash(name: String, reason: String, stacktrace: String) {
        enqueue(MobileWire.crash(name, reason, stacktrace, now()))
        // Synchronous — the process is about to die; an async flush wouldn't
        // finish sending.
        collector?.flushBlocking()
    }
    fun sendScreen(screenName: String, viewName: String, visible: Boolean) =
        enqueue(MobileWire.viewComponent(screenName, viewName, visible, now()))
    fun sendEvent(name: String, payload: String) {
        // RN/Flutter forward console logs as the reserved `$console` event, so
        // honour the API's captureConsole toggle here too (sendLog already does
        // for native logs). Custom events and `$exception` still flow.
        if (name == "\$console" && !captureConsole) return
        enqueue(MobileWire.event(name, payload, now()))
    }
    fun setUserId(id: String) {
        distinctId = id
        presence?.updateDistinctId(id)
        enqueue(MobileWire.userId(id, now()))
    }
    fun sendMetadata(key: String, value: String) =
        enqueue(MobileWire.metadata(key, value, now()))

    private fun now() = System.currentTimeMillis()

    // ── Activity tracking + lifecycle ────────────────────────────────
    /**
     * Best-effort lookup of the currently-resumed Activity via ActivityThread.
     * `registerActivityLifecycleCallbacks` doesn't replay the resume that
     * already happened, so a late init (host boots from a method channel after
     * onResume, as the React Native / Flutter plugins do) needs this to seed
     * [currentActivity]. The same reflection LeakCanary/Sentry use; tolerant of
     * failure (returns null).
     */
    private fun resolveResumedActivity(): Activity? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField =
                atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = activitiesField.get(currentAt) as? Map<*, *> ?: return null
            for (record in activities.values) {
                record ?: continue
                val rClass = record.javaClass
                val paused = rClass.getDeclaredField("paused")
                    .apply { isAccessible = true }.getBoolean(record)
                if (!paused) {
                    return rClass.getDeclaredField("activity")
                        .apply { isAccessible = true }.get(record) as? Activity
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

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
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            registerFragmentTracking(activity)
        }
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // ── Fragment screen tracking (androidx.fragment — compileOnly) ────
    // Auto-emit a screen (viewComponent) per Fragment resume/pause so
    // single-Activity / Navigation / Compose-host apps report real screens,
    // not just the one Activity. Guarded everywhere so a host without
    // androidx.fragment never crashes (the class simply won't load).
    private val trackedFragmentManagers =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>()),
        )

    private val fragmentCallbacks by lazy {
        object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(
                fm: androidx.fragment.app.FragmentManager,
                f: androidx.fragment.app.Fragment,
            ) {
                if (autoScreenName) { val n = f.javaClass.simpleName; sendScreen(n, n, true) }
            }

            override fun onFragmentPaused(
                fm: androidx.fragment.app.FragmentManager,
                f: androidx.fragment.app.Fragment,
            ) {
                if (autoScreenName) { val n = f.javaClass.simpleName; sendScreen(n, n, false) }
            }
        }
    }

    private fun registerFragmentTracking(activity: Activity) {
        try {
            if (activity !is androidx.fragment.app.FragmentActivity) return
            val fm = activity.supportFragmentManager
            if (!trackedFragmentManagers.add(fm)) return // already tracking this FragmentManager
            fm.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
        } catch (_: Throwable) {
            // androidx.fragment not on the host's classpath, or registration
            // failed — screen tracking just falls back to Activities.
        }
    }

    /**
     * Manually mark a view as a screen — emits a screen (viewComponent) when it
     * attaches to the window and again (hidden) on detach. For Compose screens
     * / custom views the Activity + Fragment hooks can't see.
     */
    fun observeView(view: android.view.View, screenName: String, viewName: String) {
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) { sendScreen(screenName, viewName, true) }
            override fun onViewDetachedFromWindow(v: android.view.View) { sendScreen(screenName, viewName, false) }
        })
        if (view.isAttachedToWindow) sendScreen(screenName, viewName, true)
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
            // Total device RAM in KB (mirrors iOS physicalMemory/1024) so the
            // dashboard's device panel shows memory for Android sessions too.
            put("deviceMemory", deviceMemoryKb(app))
            put("timestamp", now())
            put("timezone", timezone())
            put("connectionType", connectionType(app))
            put("width", dm.widthPixels)
            put("height", dm.heightPixels)
            // If identify() ran before start, send the id so the sampling gate
            // can honour alwaysRecordIdentified.
            distinctId?.let { put("distinctId", it) }
        }
    }

    private fun deviceMemoryKb(app: Application): Long {
        return try {
            val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem / 1024
        } catch (e: Exception) { 0L }
    }

    /**
     * Transport the device is on at session start — "wifi" / "cellular" /
     * "ethernet" / "none". Mirrors the reference's ConnectivityManager probe.
     * Wrapped so a missing ACCESS_NETWORK_STATE (or any failure) yields
     * "unknown" rather than breaking start.
     */
    private fun connectionType(app: Application): String {
        return try {
            val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return "unknown"
            val net = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(net) ?: return "none"
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun timezone(): String {
        val offset = java.util.TimeZone.getDefault().rawOffset
        val sign = if (offset >= 0) "+" else "-"
        val abs = Math.abs(offset)
        return String.format("UTC%s%02d:%02d", sign, abs / 3_600_000, (abs % 3_600_000) / 60_000)
    }
}
