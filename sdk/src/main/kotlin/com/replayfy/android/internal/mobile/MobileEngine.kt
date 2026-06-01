package com.replayfy.android.internal.mobile

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Orchestrates the reference mobile recording engine on Android:
 * session start, screenshot capture, binary message collection, and
 * lifecycle. Mirrors the iOS MobileEngine.
 *
 * Listeners call the `send*` methods, which encode a binary message
 * and hand it to the collector. Screenshots flow independently to
 * /v1/mobile/images.
 */
class MobileEngine private constructor() {
    companion object {
        @JvmStatic
        val shared = MobileEngine()
    }

    private var transport: MobileTransport? = null
    private var collector: MobileCollector? = null
    private var screenshots: MobileScreenshots? = null
    private var touch: MobileTouchCapture? = null
    private val startExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var currentActivity: Activity? = null
    @Volatile var sessionId: String? = null
        private set
    @Volatile var startedAt: Long = 0
        private set

    /** Sensitive decor-view-coordinate rects to mask in screenshots. */
    var privacyRectsProvider: () -> List<Rect> = { emptyList() }

    fun start(app: Application, projectKey: String, host: String) {
        app.registerActivityLifecycleCallbacks(activityCallbacks)
        val transport = MobileTransport(host, projectKey)
        this.transport = transport

        startExecutor.execute {
            val resp = transport.start(deviceParams(app, projectKey)) ?: return@execute
            sessionId = resp.sessionID
            startedAt = System.currentTimeMillis()

            val collector = MobileCollector(transport)
            val screenshots = MobileScreenshots(
                transport,
                activityProvider = { currentActivity },
                privacyRects = { privacyRectsProvider() },
            ).apply { setSettings(resp.fps, resp.quality) }
            val touch = MobileTouchCapture { label, x, y, isSwipe, direction ->
                if (isSwipe) sendSwipe(label, x, y, direction) else sendClick(label, x, y)
            }

            this.collector = collector
            this.screenshots = screenshots
            this.touch = touch

            collector.start()
            screenshots.start()
            currentActivity?.let { touch.attach(it) }
        }
    }

    fun stop() {
        screenshots?.stop(); screenshots = null
        collector?.stop(); collector = null
        sessionId = null
    }

    // ── Message API (called by listeners) ────────────────────────────
    fun sendClick(label: String, x: Long, y: Long) =
        collector?.add(MobileWire.click(label, x, y, now()))
    fun sendSwipe(label: String, x: Long, y: Long, direction: String) =
        collector?.add(MobileWire.swipe(label, x, y, direction, now()))
    fun sendInput(value: String, masked: Boolean, label: String) =
        collector?.add(MobileWire.input(value, masked, label, now()))
    fun sendPerformance(name: String, value: Long) =
        collector?.add(MobileWire.performance(name, value, now()))
    fun sendLog(severity: String, content: String) =
        collector?.add(MobileWire.log(severity, content, now()))
    fun sendNetwork(type: String, method: String, url: String, request: String, response: String, status: Long, duration: Long) =
        collector?.add(MobileWire.networkCall(type, method, url, request, response, status, duration, now()))
    fun sendCrash(name: String, reason: String, stacktrace: String) {
        collector?.add(MobileWire.crash(name, reason, stacktrace, now()))
        collector?.flush()
    }
    fun sendScreen(screenName: String, viewName: String, visible: Boolean) =
        collector?.add(MobileWire.viewComponent(screenName, viewName, visible, now()))
    fun sendEvent(name: String, payload: String) =
        collector?.add(MobileWire.event(name, payload, now()))
    fun setUserId(id: String) =
        collector?.add(MobileWire.userId(id, now()))

    private fun now() = System.currentTimeMillis()

    // ── Activity tracking + lifecycle ────────────────────────────────
    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
            touch?.attach(activity)
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivity === activity) {
                sendPerformance("background", 1)
                screenshots?.flush()
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
            put("revID", revID)
            put("userUUID", uuid)
            put("userOSVersion", Build.VERSION.RELEASE ?: "")
            put("userDevice", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("userDeviceType", Build.MODEL ?: "")
            put("timestamp", now())
            put("timezone", timezone())
            put("width", dm.widthPixels)
            put("height", dm.heightPixels)
        }
    }

    private fun timezone(): String {
        val offset = java.util.TimeZone.getDefault().rawOffset
        val sign = if (offset >= 0) "+" else "-"
        val abs = Math.abs(offset)
        return String.format("UTC%s%02d:%02d", sign, abs / 3_600_000, (abs % 3_600_000) / 60_000)
    }
}
