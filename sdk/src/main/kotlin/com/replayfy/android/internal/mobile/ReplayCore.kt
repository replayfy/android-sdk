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
import com.replayfy.android.internal.privacy.WebViewMaskScanner
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
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
class ReplayCore internal constructor() {
    companion object {
        @JvmStatic
        val shared = ReplayCore()

        /** Wrapper-SDK tag (e.g. "react-native") added to the /start payload
         *  so the dashboard can show "Captured by …" while the platform stays
         *  ios/android. Set before start(). */
        @Volatile
        @JvmStatic
        var framework: String? = null

        /** Under the app's filesDir: final batches waiting to be resent. */
        private const val LATE_DIR = "replayfy-late"
    }

    private var transport: MobileTransport? = null
    private var collector: MobileCollector? = null
    private var screenshots: MobileScreenshots? = null
    // The session's WebView password scan (see WebViewMaskScanner).
    private var webViewMasks: WebViewMaskScanner? = null
    // The WebViews the privacy walk met in this capture tick, for the WebView
    // scan that follows it in the same tick. Set on the main thread once the
    // whole lookup has succeeded, handed over (and dropped) by that scan, and
    // held weakly: a tick whose frame never reaches the scan (a lookup that
    // throws, a session that ends) can't keep a WebView, or its Activity.
    @Volatile private var walkedWebViews: List<WeakReference<android.webkit.WebView>> = emptyList()
    // The masks a host SDK (React Native, Flutter) added to the last frame,
    // in frame coordinates: typed values check them too.
    @Volatile private var lastHostMasks: List<Rect> = emptyList()
    private var touch: MobileTouchCapture? = null
    // Opt-in text-input observation (Replay.addObservedInput) — reports an
    // observed field's value on editing-end. Always live (not tied to a single
    // start); sendInput's enqueue gates on recording state.
    private val input = MobileInputCapture(
        onInput = { value, masked, label -> sendInput(value, masked, label) },
        hostMasks = { lastHostMasks },
    )
    private var perf: MobilePerfMonitor? = null
    private var consoleCapture: ConsoleCapture? = null
    // Retained from the first start() so startNewSession() can restart cleanly.
    @Volatile private var application: Application? = null
    @Volatile private var lastConfig: ReplayConfig? = null
    private val verificationListeners =
        java.util.concurrent.CopyOnWriteArrayList<Runnable>()
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
    // Every stop() / cancelSession() moves the generation on. A start's /start
    // task wires its session only while the generation it began in is still
    // current, checked in the same sessionLock hold as the wiring, which stop()
    // and cancelSession() take to tear down. A session ended (or rotated away)
    // while its /start was in flight therefore never goes live, instead of
    // wiring up afterwards with nothing left to stop it. Lock order:
    // sessionLock, then preStart.
    private val sessionLock = Any()
    private var sessionGeneration = 0
    // ReplayConfig.autoScreenName — gates the auto screen-name emission from
    // the Activity lifecycle callback.
    @Volatile private var autoScreenName = true

    // ── Screen exclusion ("don't record this screen") ────────────────
    // While an excluded screen is foreground the FRAME loop pauses (taps /
    // network / console / perf keep flowing, mirroring pauseRecording).
    // Seeded from ReplayConfig.excludedScreens, mutable at runtime.
    @Volatile private var excludedScreens: Set<String> = emptySet()
    // Whether the frame loop is paused BECAUSE of screen exclusion, so we only
    // pause/resume on an actual enter/leave transition.
    @Volatile private var framesExcluded = false
    // The host paused frames (pauseRecording / optOutSchematicRecordings).
    // Survives backgrounding and session rotation; a process restart resets it
    // (the opt-out itself is persisted, and read back through optOutStore).
    @Volatile private var hostPaused = false
    @Volatile private var optOutStore: OptOutStore? = null
    /** Frames must not be captured or accepted right now, for any reason. */
    private val framesHeld: Boolean
        get() = framesExcluded || hostPaused || optOutStore?.schematicOptOut == true
    // A manual Replay.tagScreenName takes precedence over the Activity/Fragment
    // auto-tag for the SAME screen (mirrors iOS + the autoScreenName doc
    // contract). Set by a manual tag, consumed by the next auto-tag emit so the
    // auto class name doesn't override it. Without this, "Menu" (manual) +
    // "MenuActivity" (auto) both fire and a name-based screen exclusion flaps.
    @Volatile private var manualTagPending = false
    // The screen the last manual tag named for each live Activity: the one in
    // front when it was tagged, or the one being created. A later resume of
    // that Activity re-shows that screen (a return from the background, a
    // dialog closing, a visit to another Activity), as does the instance a
    // rotation recreates, so it is named again instead of auto-tagged:
    // emitting the class name there un-excluded a manually tagged excluded
    // screen. See nameResumedScreen. Guarded by its own monitor.
    private val manualScreens = java.util.WeakHashMap<Activity, String>()
    // A manual tag made while no Activity was in front (one being created, or
    // recreated by a rotation): the next Activity to resume shows it.
    @Volatile private var unclaimedManualScreen: String? = null
    // The last screen sent as visible this session, so a re-shown manual
    // screen is named again only when another screen came in between.
    @Volatile private var lastScreen: String? = null
    // The Activity currently resumed (null between one's pause and the next
    // resume), and the Fragments whose next resume re-shows the screen they
    // already showed rather than navigating: paused because their host paused,
    // or restored with a host a rotation recreated.
    @Volatile private var resumedActivity: WeakReference<Activity>? = null
    private val fragmentsReshowing: MutableSet<Any> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>()),
        )

    // ── Short break ("allow another app briefly") ────────────────────
    // A background gap ≤ shortBreakWindowMs keeps the SAME session; a longer gap
    // (or allow=false) rotates to a fresh session on return, so a long absence
    // can't inflate one multi-hour session (the mobile analogue of the web SDK's
    // inactivity rotation). Default tolerates 30 min so brief/medium app-switches
    // don't fragment sessions.
    @Volatile private var allowShortBreak = true
    @Volatile private var shortBreakWindowMs = 1_800_000L
    @Volatile private var backgroundedAt = 0L
    // The app's started (not yet stopped) Activities. Empty after a stop = the
    // app went to the background, as opposed to an in-app Activity transition,
    // so short-break rotation doesn't fire on ordinary navigation. Config
    // changes (rotation) are excluded via isChangingConfigurations. The
    // reference tracker counts started Activities the same way; a set instead
    // of a count, because an Activity started before the engine was listening
    // (a splash screen, with init in the next Activity's onCreate) stops
    // without ever having been counted, and a count read that stop as "every
    // Activity stopped" while the next one was in front.
    private val startedActivities: MutableSet<Activity> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>()),
        )
    // The app is in the background: its last started Activity stopped (not for
    // a config change). Cleared when an Activity starts again.
    @Volatile private var inBackground = false
    // Lifecycle callbacks are registered once per process; kept across restarts.
    @Volatile private var callbacksRegistered = false
    // Final (/late) batches an earlier launch could not deliver are looked for
    // once per process (see resendLateMessages).
    @Volatile private var lateChecked = false
    @Volatile private var lateStore: MobileLateMessages? = null

    // ── Multi-session gate (record one session, or many) ─────────────
    // ENABLED → every rotation opens a session (default). NOT_STARTED → the
    // current/next session is still allowed, then no more. DISABLED → refuse to
    // open any further session this process. Evaluated at the start() chokepoint,
    // mirroring the reference's 3-state model. Set via setMultiSessionRecord.
    private enum class MultiSessionState { ENABLED, NOT_STARTED, DISABLED }
    @Volatile private var multiSessionState = MultiSessionState.ENABLED

    @Volatile private var currentActivity: Activity? = null
    @Volatile var sessionId: String? = null
    // Project key + ingest host for the HTTP transport.
    @Volatile private var projectKey: String? = null
    @Volatile private var host: String? = null
    // Last identified distinct id (from identify → setUserId), sent on the wire.
    @Volatile private var distinctId: String? = null
        private set
    @Volatile var startedAt: Long = 0
        private set

    // Dashboard-controlled capture toggles, set from the /start response and
    // refreshed mid-session by applyRemoteConfig (see below).
    @Volatile private var captureConsole = true
    @Volatile private var captureNetwork = true
    @Volatile private var captureNetworkHeaders = true
    @Volatile private var captureNetworkBodies = true

    /**
     * Re-apply dashboard config mid-session. The periodic RemoteConfigFetcher
     * (owned by LegacyCore) pushes here on every refresh, so flipping a capture
     * toggle on the dashboard takes effect on the live mobile session without
     * an app restart — not just at /start. Toggles are @Volatile, so capture
     * threads see the new value immediately.
     */
    internal fun applyRemoteConfig(cfg: com.replayfy.android.internal.RemoteConfig) {
        captureConsole = cfg.captureConsole
        captureNetwork = cfg.captureNetwork
        captureNetworkHeaders = cfg.captureNetworkHeaders
        captureNetworkBodies = cfg.captureNetworkBodies
    }

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

    fun start(app: Application, config: ReplayConfig) = start(app, config, keepScreenState = false)

    /** [keepScreenState]: a session rotation (startNewSession) keeps the
     *  exclusions set at runtime instead of re-seeding them from [config]. */
    private fun start(app: Application, config: ReplayConfig, keepScreenState: Boolean) {
        // Idempotent: a second Replay.init (e.g. on an Activity recreate)
        // must not wire a second collector / screenshot timer / perf
        // sampler onto the same singleton.
        if (started) return
        // GDPR: honour an overall opt-out on the LIVE engine — never open a
        // session while opted out (the legacy facade checked this; the live
        // engine didn't).
        val optOut = OptOutStore(app)
        if (optOut.overallOptOut) return
        // Multi-session gate — the single place a session opens. NOT_STARTED
        // allows exactly this one (then flips to DISABLED); DISABLED refuses.
        when (multiSessionState) {
            MultiSessionState.DISABLED -> return
            MultiSessionState.NOT_STARTED -> multiSessionState = MultiSessionState.DISABLED
            MultiSessionState.ENABLED -> {}
        }
        val generation = synchronized(sessionLock) {
            started = true
            sessionGeneration
        }
        optOutStore = optOut
        val projectKey = config.apiKey
        val host = config.apiHost
        this.projectKey = projectKey
        this.host = host
        autoScreenName = config.autoScreenName
        if (!keepScreenState) excludedScreens = config.excludedScreens.map { it.lowercase() }.toSet()
        application = app
        lastConfig = config
        // Register the lifecycle callbacks once per process — startNewSession
        // (stop + start) must not double-register them (that would fire every
        // callback twice); they reference the live fields, which this start()
        // repopulates.
        if (!callbacksRegistered) {
            app.registerActivityLifecycleCallbacks(activityCallbacks)
            callbacksRegistered = true
            // The callbacks don't replay what already happened. Count an
            // Activity that is already on screen (React Native / Flutter boot
            // from a method channel, after onResume) as started, so the app
            // isn't taken for backgrounded until it stops too. Only a visible
            // one: an init from onCreate/onStart still gets that Activity's
            // own onStart through the callbacks.
            resolveResumedActivity()
                ?.takeIf { it.window?.peekDecorView()?.isAttachedToWindow == true }
                ?.let {
                    startedActivities.add(it)
                    resumedActivity = WeakReference(it)
                }
        }
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
            val late = lateStore ?: MobileLateMessages(File(app.filesDir, LATE_DIR)).also { lateStore = it }
            if (!lateChecked) {
                lateChecked = true
                resendLateMessages(late, transport)
            }
            val resp = transport.start(deviceParams(app, projectKey)) ?: run {
                // Allow a later retry, unless this start's session ended in the
                // meantime: `started` then belongs to whatever came after it.
                synchronized(sessionLock) { if (generation == sessionGeneration) started = false }
                return@execute
            }
            // Server sampling gate dropped this launch — do not record. Leave
            // `started` true so we don't re-roll on a spurious re-init.
            if (!resp.record) return@execute
            // Wire the session up, unless it was ended or rotated away while
            // /start was in flight (see sessionGeneration): then nothing is
            // built, so nothing is left capturing or holding threads with no
            // owner to stop it.
            val live = synchronized(sessionLock) {
                if (generation != sessionGeneration) return@synchronized false
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
                // The dashboard's "always mask input values". Set before the
                // screenshotter exists, so the first frame is already covered;
                // a /start without it clears it (switched off on the
                // dashboard). Its own flag, OR-ed with the app's
                // occludeAllTextFields: the dashboard can only add masking.
                PrivacyRegistry.remoteOccludeAllTextFields = resp.occludeAllTextFields ?: false

                // Wire screenshot privacy masking to the PrivacyRegistry — the
                // same collectors the legacy capture path consults. Without this
                // the frames screenshotter masks nothing: addPrivacyView,
                // occludeAll*, applyOcclusion, occludeSensitiveScreen and the
                // bridge rects all register correctly but never reach the JPEGs.
                // Invoked once per frame on the capture (main) thread, so the
                // View-tree walk is safe.
                privacyRectsProvider = provider@{
                    walkedWebViews = emptyList()
                    val root = currentActivity?.window?.decorView ?: return@provider emptyList()
                    val style = PrivacyRegistry.maskStyle
                    if (PrivacyRegistry.occludeAllScreen) {
                        // Whole-screen occlude (occludeSensitiveScreen): one solid box
                        // over the whole decor view, whatever the global style (the
                        // reference tracker paints it opaque). Drain any pending
                        // bridge rects so they don't bleed into a later
                        // non-full-screen frame.
                        PrivacyRegistry.consumePendingFrameRects()
                        return@provider listOf(
                            com.replayfy.android.MaskRect(
                                Rect(0, 0, root.width, root.height),
                                com.replayfy.android.ReplayMaskStyle.OVERLAY,
                            ),
                        )
                    }
                    // Native sources render with the global style, except the
                    // auto-masked fields, which bulkMaskRects always paints solid;
                    // the bridge rects (React Native / Flutter) carry their own
                    // per-region style. The same walk collects the WebViews for
                    // the WebView scan below.
                    val webViews = ArrayList<android.webkit.WebView>()
                    val native = PrivacyRegistry.sensitiveBounds(root).map { com.replayfy.android.MaskRect(it, style) } +
                        PrivacyRegistry.bulkMaskRects(root, style, webViews) +
                        PrivacyRegistry.composeBoundsRelativeTo(root).map { com.replayfy.android.MaskRect(it, style) }
                    val host = PrivacyRegistry.consumePendingFrameRects() + externalPrivacyRectsProvider()
                    lastHostMasks = host.map { it.rect }
                    walkedWebViews = webViews.map { WeakReference(it) }
                    // The native rects are in the decor's coordinates; the frame
                    // is the window's surface, which shows the content moved up
                    // while the window is panned for the keyboard (adjustPan).
                    // The host's rects are passed through as they come.
                    PrivacyRegistry.inFrame(native, root) + host
                }

                val collector = MobileCollector(transport, late)
                val webViewMasks = WebViewMaskScanner()
                val screenshots = MobileScreenshots(
                    transport,
                    activityProvider = { currentActivity },
                    privacyRects = { privacyRectsProvider() },
                    // Host-fed capture (Flutter) must never start the native timer.
                    nativeCapture = config.captureSnapshotPixels,
                    // Password fields inside the WebViews the walk above found
                    // (always on, as in the reference tracker). On the main
                    // thread, right after privacyRects in the same tick. The
                    // whole-screen cover already hides them.
                    webViewRects = { decor ->
                        val found = walkedWebViews.mapNotNull { it.get() }
                        walkedWebViews = emptyList()
                        if (PrivacyRegistry.occludeAllScreen) null
                        else webViewMasks.begin(decor, found, PrivacyRegistry.textFieldsOccluded)
                    },
                ).apply { setSettings(resp.fps, resp.quality) }
                val touch = MobileTouchCapture { label, x, y, kind, direction ->
                    // kind ∈ tap / swipe / long_press — routed to click / swipe /
                    // gesture by reportInteraction.
                    reportInteraction(kind, label, x, y, direction)
                }

                val perf = MobilePerfMonitor(app) { name, value -> sendPerformance(name, value) }

                this.collector = collector
                this.screenshots = screenshots
                this.webViewMasks = webViewMasks
                this.touch = touch
                this.perf = perf

                collector.start()
                // captureSnapshotPixels=false → events only, no frames archive.
                // Also honour a hold that started BEFORE start() finished (an
                // excluded screen tagged during the async /start round-trip, a host
                // pause, the schematic opt-out) or an app already backgrounded —
                // don't start the loop yet; it resumes when nothing holds it.
                if (config.captureSnapshotPixels && !framesHeld && !inBackground) screenshots.start()
                perf.start()
                // Flutter hosts pass captureTouch=false — the Dart layer reports
                // taps with the real widget label via reportInteraction (the native
                // view tree only ever sees the one FlutterView). Attached on the
                // main thread: it reads the window's decor view (creating it if the
                // Activity is still in onCreate, racing the main thread's own) and
                // swaps the window callback.
                if (config.captureTouch) {
                    currentActivity?.let { activity ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post { touch.attach(activity) }
                    }
                }
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
                true
            }
            if (!live) return@execute
            android.util.Log.i("ReplaySdk", "session live: ${resp.sessionID}")
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

    /** End the session: stop capturing and send everything buffered — frames,
     *  then the messages, whose last batch goes to /late (the server's
     *  end-of-session signal). Both drains run on their own threads and finish
     *  after the references are dropped here; [completion] runs once both have
     *  settled. */
    @JvmOverloads
    fun stop(completion: (() -> Unit)? = null) {
        val drains = java.util.concurrent.atomic.AtomicInteger(2)
        val settled: () -> Unit = { if (drains.decrementAndGet() == 0) completion?.invoke() }
        val ended = endSession()
        ended.screenshots?.stop(settled) ?: settled()
        ended.perf?.stop()
        ended.console?.stop()
        ended.collector?.stop(settled) ?: settled()
    }

    /** [stop], then block for up to [timeoutMs] until the session's last frames
     *  and messages have been sent (or, for the final message batch, kept on
     *  disk for the next launch). For Replay.stopApplicationAndUploadData, just
     *  before the host exits the process, which would kill the drains. */
    fun stopAndWait(timeoutMs: Long) {
        val done = java.util.concurrent.CountDownLatch(1)
        stop { done.countDown() }
        try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Discard the current session WITHOUT uploading. Our engine streams
     *  batches live every ~5s, so (unlike a deferred-upload model where cancel
     *  is a pure local delete) we must signal the backend to DROP what already
     *  landed, then clear the in-memory buffers and tear down — never flushing.
     *  A fresh session can start afterward (the multi-session gate is untouched). */
    fun cancelSession() {
        val t = transport
        val ended = endSession()
        // Backend drop first, while the session token is still valid — off the
        // main thread (network), best-effort.
        val sid = ended.sessionId
        if (sid != null && t != null) startExecutor.execute { runCatching { t.cancelSession(sid) } }
        ended.screenshots?.discard()   // discard() = teardown WITHOUT flush
        ended.perf?.stop()
        ended.console?.stop()
        ended.collector?.discard()
    }

    /** The parts of a session [endSession] takes down. */
    private class EndedSession(
        val sessionId: String?,
        val screenshots: MobileScreenshots?,
        val perf: MobilePerfMonitor?,
        val console: ConsoleCapture?,
        val collector: MobileCollector?,
    )

    /** Detach the live session and hand back its parts for the caller to stop
     *  or discard. Under sessionLock, so a /start still in flight sees the
     *  session gone instead of wiring it up afterwards (see sessionGeneration);
     *  the caller tears the parts down after the lock is released, so no
     *  completion runs under it. Also resets the run flags and drops buffered
     *  pre-start messages, so a following start() (startNewSession) actually
     *  opens a fresh session. The lifecycle callbacks are registered once for
     *  the process (see callbacksRegistered) and deliberately kept across a
     *  restart — they reference the live fields, which start() repopulates. */
    private fun endSession(): EndedSession = synchronized(sessionLock) {
        sessionGeneration++
        val ended = EndedSession(sessionId, screenshots, perf, consoleCapture, collector)
        screenshots = null
        // What the WebView scan remembers is the session's: dropped with it.
        webViewMasks?.clear()
        webViewMasks = null
        walkedWebViews = emptyList()
        lastHostMasks = emptyList()
        perf = null
        consoleCapture = null
        collector = null
        sessionId = null
        framesExcluded = false
        lastScreen = null
        started = false
        synchronized(preStart) { preStart.clear(); ready = false }
        ended
    }

    // ── Advanced gestures / short break / multi-session / exclude ─────
    /** Configure the short-break policy (see [allowShortBreak]). A zero/negative
     *  window keeps the current default rather than disabling the window. */
    fun setAllowShortBreak(allow: Boolean, windowMs: Long) {
        allowShortBreak = allow
        if (windowMs > 0) shortBreakWindowMs = windowMs
    }

    /** Turn multi-session recording on/off. Off → the current/next session is
     *  still allowed, then no further sessions open this process. */
    fun setMultiSessionRecord(enabled: Boolean) {
        multiSessionState = if (enabled) MultiSessionState.ENABLED else MultiSessionState.NOT_STARTED
    }

    /** Replace the excluded-screens denylist. */
    fun setExcludedScreens(names: List<String>) { excludedScreens = names.map { it.lowercase() }.toSet() }
    /** Add one screen to the excluded set. */
    fun excludeScreen(name: String) { excludedScreens = excludedScreens + name.lowercase() }
    /** Remove one screen from the excluded set (recording resumes next time it shows). */
    fun unexcludeScreen(name: String) { excludedScreens = excludedScreens - name.lowercase() }

    // ── Lifecycle controls (facade re-homes, #119) ───────────────────
    /** Host pause (pauseRecording / schematic opt-out): stop frames until
     *  resumeCapture(); events keep flowing. Holds across background/foreground
     *  and session rotation, and also stops host-fed (Flutter) frames. */
    fun pauseCapture() {
        hostPaused = true
        screenshots?.pause()
    }

    /** Host resume. Frames restart unless something else still holds them (an
     *  excluded screen in front, a persisted schematic opt-out) or the app is
     *  in the background (onAppForeground resumes them). MobileScreenshots
     *  restarts its timer only for native capture. */
    fun resumeCapture() {
        hostPaused = false
        if (!framesHeld && !inBackground) screenshots?.resume()
    }

    /** Schematic opt-out (Replay.optOutSchematicRecordings): persisted, and
     *  applied to the live engine like [pauseCapture] / [resumeCapture]. Not
     *  the same hold: opting back in must not lift a pauseRecording still in
     *  effect (a consent sync on every resume would restart frames on a PIN
     *  screen). */
    fun setSchematicOptOut(optOut: Boolean) {
        val wasHeld = framesHeld
        (optOutStore ?: application?.let { OptOutStore(it) })?.schematicOptOut = optOut
        val held = framesHeld
        // Transition-gated, so a repeated call doesn't flush partial archives.
        if (held == wasHeld) return
        if (held) screenshots?.pause() else if (!inBackground) screenshots?.resume()
    }

    /** Ingest a Flutter Dart-side frame (PNG bytes) into the frames archive.
     *  Used when native capture is disabled for Flutter (Android's PixelCopy
     *  can't read the SurfaceView). */
    fun submitFrame(png: ByteArray, rects: List<com.replayfy.android.MaskRect>) {
        // Host-fed frames obey the same holds as native capture: an excluded
        // screen, pauseRecording and the schematic opt-out. Nor are they taken
        // while the app is in the background: unlike iOS, Android keeps the
        // process (and Flutter's capture timer) running there, and frames of
        // a screen nobody sees only stretch the replay.
        if (framesHeld || inBackground) return
        val shots = screenshots ?: return
        // The native guards apply to host frames too, whatever rects the host
        // sent: a FLAG_SECURE window records black, as native frames do, and
        // occludeSensitiveScreen replaces the frame with one solid box.
        val cover = when {
            PrivacyRegistry.isSecureWindow(currentActivity?.window) -> android.graphics.Color.BLACK
            PrivacyRegistry.occludeAllScreen -> MobileScreenshots.MASK_COLOR
            else -> null
        }
        shots.submitFrame(png, rects, cover)
    }

    /** Toggle auto screen-name tagging (Activity lifecycle) at runtime. */
    fun setAutoScreenName(enabled: Boolean) { autoScreenName = enabled }

    /** End the current session and immediately start a fresh one using the
     *  config retained from the first start(). No-op before start() runs. */
    fun startNewSession() {
        val app = application ?: return
        val cfg = lastConfig ?: return
        // A rotation doesn't change what is on screen: keep the exclusions set
        // at runtime (start() would re-seed them from the config) and whether
        // an excluded screen is in front, so its frames stay paused in the new
        // session until the user leaves it.
        val excluded = framesExcluded
        stop()
        framesExcluded = excluded
        start(app, cfg, keepScreenState = true)
    }

    fun addVerificationListener(listener: Runnable) { verificationListeners.add(listener) }

    fun removeVerificationListener(listener: Runnable) { verificationListeners.remove(listener) }

    /** The live session's frame buffer and message queue. For tests. */
    internal val liveScreenshots: MobileScreenshots? get() = screenshots
    /** WebViews a privacy lookup left for the WebView scan. For tests. */
    internal val pendingWebViewCount: Int get() = walkedWebViews.size
    internal val liveCollector: MobileCollector? get() = collector

    /** Block until the /start work queued so far has run. For tests. */
    internal fun awaitStartQueue(timeoutMs: Long) {
        val done = java.util.concurrent.CountDownLatch(1)
        startExecutor.execute { done.countDown() }
        done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

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
            // long_press / double_tap / pinch / rotate → distinct gesture event.
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
    /** A manual Replay.tagScreenName — authoritative for this screen, so the
     *  Activity/Fragment auto-tag that follows (same physical screen) is
     *  suppressed until the next screen. Mirrors iOS's manual-tag precedence. */
    fun tagScreenManual(screenName: String) {
        manualTagPending = true
        // The Activity in front shows the screen this names; while one is being
        // created (none resumed yet) its upcoming resume claims it.
        val activity = resumedActivity?.get()
        synchronized(manualScreens) {
            if (activity != null) manualScreens[activity] = screenName else unclaimedManualScreen = screenName
        }
        sendScreen(screenName, screenName, true)
    }

    fun sendScreen(screenName: String, viewName: String, visible: Boolean) {
        enqueue(MobileWire.viewComponent(screenName, viewName, visible, now()))
        // "Don't record this screen": pause the frame loop when we ENTER an
        // excluded screen and resume when we LEAVE it (transition-gated so we
        // don't churn on every tag). Everything else (this event, taps, network,
        // console, perf) keeps flowing. Only act on a screen becoming visible.
        if (!visible) return
        lastScreen = screenName
        val excluded = excludedScreens.contains(screenName.lowercase())
        if (excluded != framesExcluded) {
            framesExcluded = excluded
            // Not pauseCapture/resumeCapture: those are the host's own pause.
            // Leaving an excluded screen doesn't restart frames a host pause or
            // opt-out still holds, nor while the app is in the background.
            if (excluded) {
                screenshots?.pause()
                android.util.Log.i("ReplaySdk", "screen-exclude: PAUSED frames on '$screenName'")
            } else if (!framesHeld && !inBackground) {
                screenshots?.resume()
                android.util.Log.i("ReplaySdk", "screen-exclude: RESUMED frames on '$screenName'")
            }
        }
    }
    fun sendEvent(name: String, payload: String) {
        // RN/Flutter forward console logs as the reserved `$console` event, so
        // honour the API's captureConsole toggle here too (sendLog already does
        // for native logs). Custom events and `$exception` still flow.
        if (name == "\$console" && !captureConsole) return
        enqueue(MobileWire.event(name, payload, now()))
    }
    fun setUserId(id: String) {
        distinctId = id
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
            resumedActivity = WeakReference(activity)
            touch?.attach(activity)
            // Screen tracking → viewComponent message with the activity
            // class name (drives the dashboard Screens tab + route).
            nameResumedScreen(activity)
        }
        override fun onActivityPaused(activity: Activity) {
            // Capture is no longer paused here: this fires on every Activity
            // change and for a permission dialog, not just when the app goes
            // to the background (see onAppBackground).
            if (resumedActivity?.get() === activity) resumedActivity = null
        }
        override fun onActivityStarted(activity: Activity) {
            // App-level foreground detection: the first Activity to start after
            // the app went to the background (not an in-app Activity transition,
            // and not the Activity a config change recreates).
            startedActivities.add(activity)
            if (inBackground) {
                inBackground = false
                onAppForeground()
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            registerFragmentTracking(activity)
        }
        override fun onActivityStopped(activity: Activity) {
            startedActivities.remove(activity)
            // App went to background (not a config change / rotation).
            if (startedActivities.isEmpty() && !activity.isChangingConfigurations && !inBackground) {
                onAppBackground()
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            // A config change (rotation) recreates the Activity, and the new
            // instance shows the same screen: it takes over the manual name.
            if (!activity.isChangingConfigurations) return
            synchronized(manualScreens) {
                manualScreens.remove(activity)?.let { unclaimedManualScreen = it }
            }
        }
    }

    /** The app went to the background: its last started Activity stopped. */
    private fun onAppBackground() {
        inBackground = true
        sendPerformance("background", 1)
        // Stamp the break start so onAppForeground can size the gap.
        backgroundedAt = System.currentTimeMillis()
        // Upload everything buffered now. Frames upload only once a batch of 20
        // fills, and the next flush may never come: the system can freeze a
        // cached app (Android 11+) or kill its process soon after it leaves the
        // screen. Unlike iOS, Android doesn't suspend the app the moment it
        // backgrounds, so these drains (on the SDK's own threads, one request
        // at a time) normally finish first; whatever doesn't get out stays
        // queued and goes out on onAppForeground (nothing is discarded). A
        // session end is covered on disk as well (MobileLateMessages). pause()
        // also stops capture so we don't inflate the archive with
        // invisible-window frames.
        screenshots?.pause()
        collector?.drain()
    }

    /** The app returned to the foreground. */
    private fun onAppForeground() {
        val elapsed = if (backgroundedAt > 0L) System.currentTimeMillis() - backgroundedAt else 0L
        backgroundedAt = 0L
        // Short-break policy: a gap longer than the window (or short breaks
        // disallowed) rotates to a fresh session so a long absence can't extend
        // one session for hours. startNewSession() is gated by the multi-session
        // state machine, so a disabled multi-session simply ends recording
        // instead of rotating.
        if (!allowShortBreak || elapsed > shortBreakWindowMs) {
            android.util.Log.i(
                "ReplaySdk",
                "short-break: gap ${elapsed / 1000}s > window ${shortBreakWindowMs / 1000}s — rotating session",
            )
            // The returning Activity's resume then names its screen in the new
            // session (a manual name too, see nameResumedScreen).
            startNewSession()
            return
        }
        sendPerformance("background", 0)
        // Push what the background could not get out (a frozen process's
        // uploads fail on return) rather than waiting for the next full batch
        // or timer tick. An excluded screen still in front keeps its frames
        // paused, as does a host pause or opt-out; only leftovers go.
        if (framesHeld) screenshots?.flush(all = true) else screenshots?.resume()
        collector?.flush()
    }

    /**
     * Name the screen [activity] shows as it resumes. A manual tagScreenName
     * made while this Activity was being created, or while it was in front,
     * names its screen until another screen is tagged in it: its later resumes
     * re-show that same screen, so the manual name is sent again if another
     * screen came in between (another Activity's, or a new session's first),
     * never the class name. Emitting the class name there replaced the manual
     * name and un-excluded a manually tagged excluded screen. Otherwise the
     * class name, when automatic screen names are on.
     */
    private fun nameResumedScreen(activity: Activity) {
        val manual = synchronized(manualScreens) {
            unclaimedManualScreen?.let {
                manualScreens[activity] = it
                unclaimedManualScreen = null
            }
            manualScreens[activity]
        }
        manualTagPending = false
        when {
            manual == null -> if (autoScreenName) {
                val name = activity.javaClass.simpleName
                sendScreen(name, name, true)
            }
            manual != lastScreen -> sendScreen(manual, manual, true)
        }
    }

    /** The manual name of the screen [activity] shows, if any. */
    private fun manualScreenOf(activity: Activity): String? =
        synchronized(manualScreens) { manualScreens[activity] }

    /**
     * Resend the batches an earlier launch could not deliver (the reference's
     * late-messages file, see [MobileLateMessages]). Listed before this
     * launch's first session can write its own; sent on their own thread so a
     * slow network never delays /start, each under the token of the session
     * it belongs to, as a plain batch (see [MobileTransport.sendSavedMessages]).
     */
    private fun resendLateMessages(store: MobileLateMessages, transport: MobileTransport) {
        val files = store.pending()
        if (files.isEmpty()) return
        Thread({ store.resend(files) { batch, token -> transport.sendSavedMessages(batch, token) } }, "replay-late")
            .start()
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
                // Re-shown with its host Activity (a return from the background,
                // a dialog closing, a rotation) while a manual tag names that
                // Activity's screen: not a navigation, so the class name must
                // not replace the manual one (see nameResumedScreen). A tag the
                // Fragment just made for itself in onResume names that same
                // screen, so it is spent here too; left pending, it swallowed
                // the next Fragment's name.
                val reshown = fragmentsReshowing.remove(f)
                val host = f.activity
                if (reshown && host != null && manualScreenOf(host) != null) {
                    manualTagPending = false
                    return
                }
                if (autoScreenName) {
                    if (manualTagPending) manualTagPending = false
                    else {
                        host?.let { synchronized(manualScreens) { manualScreens.remove(it) } }
                        val n = f.javaClass.simpleName; sendScreen(n, n, true)
                    }
                }
            }

            override fun onFragmentPaused(
                fm: androidx.fragment.app.FragmentManager,
                f: androidx.fragment.app.Fragment,
            ) {
                // The host's own onActivityPaused runs first, so a host that is
                // no longer the resumed Activity means this Fragment paused with
                // it, rather than being navigated away from.
                val host = f.activity
                if (host != null && resumedActivity?.get() !== host) fragmentsReshowing.add(f)
                if (autoScreenName) { val n = f.javaClass.simpleName; sendScreen(n, n, false) }
            }

            override fun onFragmentCreated(
                fm: androidx.fragment.app.FragmentManager,
                f: androidx.fragment.app.Fragment,
                savedInstanceState: Bundle?,
            ) {
                // Restored from saved state while its host is being recreated (a
                // rotation): it shows the screen it showed before. Not one
                // popped back later from the back stack, with its host in front:
                // that is a navigation.
                val host = f.activity
                if (savedInstanceState != null && host != null && resumedActivity?.get() !== host) {
                    fragmentsReshowing.add(f)
                }
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
        // Marketing version (e.g. "1.4.2") — the human release identifier
        // Release Intelligence prefers, over the build number in revID.
        val appVersion = pkg?.versionName ?: ""
        val uuid = try {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) { "unknown" }
        return JSONObject().apply {
            put("projectKey", projectKey)
            put("platform", "android")
            // The SDK's own release, so the dashboard shows which build a
            // session came from (it was a constant "2.0.0" for every release).
            put("trackerVersion", com.replayfy.android.BuildConfig.SDK_VERSION)
            framework?.takeIf { it.isNotEmpty() }?.let { put("framework", it) }
            put("revID", revID)
            put("appVersion", appVersion)
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
