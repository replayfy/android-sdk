package com.replayfy.android.internal

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.Locale
import com.replayfy.android.BuildConfig
import com.replayfy.android.ReplayConfig
import com.replayfy.android.internal.capture.AssetUploader
import com.replayfy.android.internal.capture.SnapshotCapture
import com.replayfy.android.internal.capture.ThumbnailUploader
import com.replayfy.android.internal.tracker.TapTracker
import com.replayfy.android.internal.upload.BatchUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internal orchestrator. The public [com.replayfy.android.Replay]
 * object delegates here; everything that mutates state lives in this
 * singleton.
 *
 * Owns:
 *  - the singleton [SessionRuntime] (rotates on background→foreground)
 *  - the [BatchSender] (one per process)
 *  - the periodic flush coroutine
 *  - the [SessionLifecycleObserver] registration
 *
 * Bootstrapped TWICE:
 *  1. [ReplayContentProvider.onCreate] calls [autoBootstrap] with the
 *     Application context — early enough to register lifecycle observers
 *     before user code runs. No config yet, so we don't have an apiKey
 *     to send batches with; we buffer events in-memory waiting for init.
 *  2. User calls [com.replayfy.android.Replay.init] with their config —
 *     we wire up the sender, flush any pre-init events, start the
 *     periodic-flush loop.
 *
 * Mirrors the web SDK's `initReplay.ts` two-phase pattern (without the
 * rrweb-specific snapshot dance — that lives in the snapshot pipeline
 * commit).
 */
internal object ReplayCore {

    private val initialized = AtomicBoolean(false)
    private val bootstrapped = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var config: ReplayConfig? = null
    @Volatile private var runtime: SessionRuntime? = null
    @Volatile private var sender: BatchSender? = null
    @Volatile private var uploader: BatchUploader? = null
    @Volatile private var lifecycleObserver: SessionLifecycleObserver? = null
    @Volatile private var tapTracker: TapTracker? = null
    @Volatile private var snapshotCapture: SnapshotCapture? = null
    @Volatile private var perfMetrics: com.replayfy.android.internal.perf.PerfMetricsManager? = null
    @Volatile private var crashHandler: com.replayfy.android.internal.crash.CrashHandler? = null

    /** Whole-SDK coroutine scope. Cancelled on [stop]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flushJob: Job? = null

    // -----------------------------------------------------------------
    //  Phase 1: ContentProvider auto-bootstrap (no config yet)
    // -----------------------------------------------------------------

    /**
     * Called by [ReplayContentProvider.onCreate]. Sets up the bare
     * minimum: stores the app context, registers the lifecycle
     * observer. Events fired between bootstrap and init are dropped
     * (not buffered) in the foundation commit — the dashboard will
     * see a session_start with whatever was foregrounded when init
     * eventually lands. We can revisit if customers need pre-init
     * events kept.
     */
    fun autoBootstrap(context: Context) {
        if (!bootstrapped.compareAndSet(false, true)) return
        appContext = context.applicationContext

        // Register on the main thread — ProcessLifecycleOwner expects
        // it. Bootstrap fires on the main thread (ContentProvider
        // contract), so direct call is fine.
        val observer = SessionLifecycleObserver(
            onAppForegrounded = ::onAppForegrounded,
            onAppBackgrounded = ::onAppBackgrounded,
        )
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            lifecycleObserver = observer
        } catch (t: Throwable) {
            // Lifecycle module not on the classpath, or test env without
            // a real Lifecycle — log and continue. SDK will still work
            // if the customer calls init explicitly.
            android.util.Log.w(TAG, "ProcessLifecycleOwner unavailable: ${t.message}")
        }

        // Tap tracker attaches to the Application's
        // ActivityLifecycleCallbacks here — earlier than init() so we
        // catch the very first activity (otherwise the user's launcher
        // activity could be onResume'd before init lands, and we'd
        // miss every tap on the welcome screen).
        val app = context.applicationContext as? Application
        if (app != null) {
            try {
                val tracker = TapTracker(emit = ::emitTap)
                tracker.attach(app)
                tapTracker = tracker

                // Snapshot pipeline. Shares the activity holder
                // ownership with the tracker via TapTracker.currentActivity().
                // Fires on screen resume + 500ms after each tap.
                // Bitmap capture + asset uploader are wired in
                // [init] once we have the api key — pre-init
                // snapshots ship as tree-only.
                val snapshot = SnapshotCapture(
                    activityProvider = { tracker.currentActivity() },
                    emit = ::emitSnapshot,
                )
                tracker.onScreenResumed = { snapshot.captureNow("screen_appeared") }
                tracker.onTapEmitted = { snapshot.scheduleIdle() }
                snapshotCapture = snapshot

                // Native perf metrics — cold_start_ms,
                // frame_drop_pct, frozen_frame_count, memory_rss_mb,
                // thermal_state. Cold start emits separately from
                // onAppForegrounded so we capture cold-launch →
                // user-sees-app delay, not just process spin-up.
                val perf = com.replayfy.android.internal.perf.PerfMetricsManager(
                    context = app,
                    onMetric = ::emitPerformance,
                )
                perf.start()
                perfMetrics = perf

                // Crash handler — install EARLY so we catch
                // exceptions thrown during Application.onCreate /
                // first-activity-onCreate (the most crash-prone
                // moments in a session). Drain-on-install also
                // recovers crashes from the previous process before
                // anything else runs. The handler is config-
                // independent (writes to disk regardless); if the
                // recovered record arrives before runtime is ready,
                // it's queued in pendingCrash and flushed on first
                // session start.
                val crash = com.replayfy.android.internal.crash.CrashHandler(
                    context = app,
                    onRecoveredCrash = ::onRecoveredCrash,
                )
                crash.install()
                crashHandler = crash
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "TapTracker attach failed: ${t.message}")
            }
        }
    }

    // -----------------------------------------------------------------
    //  Phase 2: User-supplied config
    // -----------------------------------------------------------------

    fun init(applicationContext: Context, cfg: ReplayConfig) {
        if (!initialized.compareAndSet(false, true)) {
            android.util.Log.w(TAG, "Replay.init called twice — ignoring second call")
            return
        }
        appContext = applicationContext
        config = cfg
        val newSender = BatchSender(cfg)
        sender = newSender
        // Wrap with the persistent-queue uploader. flushNow() goes
        // through this so failed batches land on disk and get drained
        // later by SessionUploadWorker. One-shot drain scheduled
        // immediately below picks up anything queued during a
        // previous process lifetime.
        uploader = BatchUploader(applicationContext, cfg, newSender)
        uploader?.scheduleDrain()

        if (cfg.distinctId != null) {
            newSender.identity = IdentifyPayload(distinctId = cfg.distinctId)
        }

        // Now that we have an apiKey + apiHost, the snapshot pipeline
        // can upload bitmap assets. Wire the uploader + thumbnail
        // uploader + the pixels-capture flag (defaults true; remote
        // config may flip it off later).
        snapshotCapture?.let { snap ->
            snap.assetUploader = AssetUploader(cfg)
            snap.thumbnailUploader = ThumbnailUploader(cfg)
            snap.captureBitmaps = cfg.captureSnapshotPixels
            // Provide current sessionId at snapshot time — ensures
            // the thumbnail upload targets the live session even
            // after background→foreground rotations.
            snap.sessionIdProvider = { runtime?.sessionId }
        }

        // Network capture wire — the Interceptor itself is exposed
        // via Replay.networkInterceptor() for the customer to add to
        // their OkHttpClient. Here we just wire the emit + config
        // flags so the instance the customer creates can find them.
        com.replayfy.android.internal.network.ReplayInterceptor.currentEmit = ::emitNetwork
        com.replayfy.android.internal.network.ReplayInterceptor.configure(cfg)

        // Build the runtime + emit the session_start event for the
        // current screen. If the app is already in the foreground when
        // init lands (the typical case from Application.onCreate), the
        // session is immediately recording.
        val rt = startNewSession()
        emitSessionStart(rt)

        // Start the periodic-flush coroutine. Cancelled on stop().
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(cfg.flushIntervalMs)
                flushNow()
            }
        }

        android.util.Log.i(TAG, "Replay.init complete — session=${rt.sessionId}")
    }

    // -----------------------------------------------------------------
    //  Identity + tracks
    // -----------------------------------------------------------------

    fun identify(distinctId: String, properties: Map<String, Any?>?) {
        val s = sender ?: run {
            android.util.Log.w(TAG, "identify before init — dropping")
            return
        }
        s.identity = IdentifyPayload(
            distinctId = distinctId,
            customProps = properties,
        )
        // Force-flush so the identity attaches as soon as possible.
        scope.launch(Dispatchers.IO) { flushNow() }
    }

    fun track(name: String, properties: Map<String, Any?>?) {
        val rt = runtime ?: run {
            android.util.Log.w(TAG, "track before init — dropping '$name'")
            return
        }
        if (name.isBlank()) return
        val safeName = name.trim().take(80)
        val data = CustomEventData(
            kind = "track",
            name = safeName,
            properties = safeProps(properties),
        )
        push(rt, type = "custom", data = data)
    }

    // -----------------------------------------------------------------
    //  Lifecycle
    // -----------------------------------------------------------------

    private fun onAppForegrounded() {
        // No config yet → bootstrap has nothing to do. We'll start the
        // session when init lands.
        if (config == null) return
        // Already-running session AND we haven't backgrounded yet → no-op.
        if (runtime != null) return
        val rt = startNewSession()
        emitSessionStart(rt)
        // Cold-start measurement — emit once per process. Idempotent
        // (returns null after the first call), so subsequent
        // foregrounds don't re-emit.
        perfMetrics?.reportFirstForeground()
    }

    private fun onAppBackgrounded() {
        val rt = runtime ?: return
        emitSessionEnd(rt, reason = "background")
        scope.launch(Dispatchers.IO) {
            flushNow()
        }
    }

    fun stop() {
        val rt = runtime
        if (rt != null) {
            emitSessionEnd(rt, reason = "manual")
            scope.launch(Dispatchers.IO) {
                flushNow()
            }
        }
        flushJob?.cancel()
        flushJob = null
        runtime = null
        // Stop background perf collectors — Choreographer callback,
        // memory timer, thermal listener. Restarts when ReplayCore
        // re-initialises (which doesn't happen in v1 but the
        // teardown discipline is correct).
        perfMetrics?.stop()
        perfMetrics = null
        // Clear the network-interceptor emit closure so any
        // in-flight OkHttp calls' interceptor doesn't push into
        // the stopped runtime. The customer's interceptor instance
        // stays installed on their OkHttpClient (we don't own it),
        // but it'll silently no-op once emit is null.
        com.replayfy.android.internal.network.ReplayInterceptor.currentEmit = null
        com.replayfy.android.internal.network.ReplayInterceptor.enabled = false
    }

    // -----------------------------------------------------------------
    //  Tap tracker hook — relays TapEventData into the event buffer.
    // -----------------------------------------------------------------

    private fun emitTap(tap: TapEventData) {
        val rt = runtime ?: return
        push(rt, type = "tap", data = tap)
    }

    private fun emitSnapshot(snapshot: NativeSnapshotEventData) {
        val rt = runtime ?: return
        push(rt, type = "native_snapshot", data = snapshot)
    }

    private fun emitPerformance(perf: PerformanceEventData) {
        val rt = runtime ?: return
        push(rt, type = "performance", data = perf)
    }

    private fun emitNetwork(event: NetworkEventData) {
        val rt = runtime ?: return
        push(rt, type = "network", data = event)
    }

    /** Recovered previous-launch crashes pending emission. Populated
     *  in autoBootstrap (which runs before any session exists) and
     *  drained in [emitSessionStart]. Synchronized; one-shot per
     *  install — typically ≤ 1 entry. */
    private val pendingCrashes = mutableListOf<com.replayfy.android.internal.crash.CrashRecord>()
    private val pendingCrashesLock = Any()

    /** Surface a previous-launch crash as an `error` event on the
     *  current session. If no session exists yet (callback fires
     *  during autoBootstrap, before first foreground), queue and
     *  flush on session start. */
    private fun onRecoveredCrash(record: com.replayfy.android.internal.crash.CrashRecord) {
        val rt = runtime
        if (rt == null) {
            synchronized(pendingCrashesLock) { pendingCrashes.add(record) }
            return
        }
        pushCrash(rt, record)
    }

    private fun pushCrash(
        rt: SessionRuntime,
        record: com.replayfy.android.internal.crash.CrashRecord,
    ) {
        val data = CrashEventData(
            kind = "crash",
            message = "${record.className}: ${record.message}".take(400),
            stack = record.stack.ifBlank { null },
        )
        push(rt, type = "error", data = data)
    }

    /** Manual screen tag override. Called by Replay.tagScreenName.
     *  Also triggers an immediate snapshot so the dashboard shows
     *  the tagged screen with its new identity. */
    fun setRoute(route: String) {
        tapTracker?.setRoute(route)
        snapshotCapture?.captureNow("manual")
    }

    fun isRecording(): Boolean = runtime != null

    // -----------------------------------------------------------------
    //  Stubs — fill in as the engine lands.
    // -----------------------------------------------------------------

    fun stub(call: String) {
        android.util.Log.v(TAG, "stub: $call (engine not yet wired)")
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private fun startNewSession(): SessionRuntime {
        val ctx = appContext ?: error("autoBootstrap should have set appContext")
        val rt = SessionRuntime(
            config = config!!,
            deviceContext = buildPageContext(ctx),
        )
        runtime = rt
        // Reset the once-per-session thumbnail flag so the new
        // session's first snapshot fires a fresh upload (not
        // skipped by the previous session's flag).
        snapshotCapture?.thumbnailUploader?.reset()
        return rt
    }

    private fun emitSessionStart(rt: SessionRuntime) {
        val data = SessionStartEventData(
            href = rt.deviceContext.url,
            path = rt.deviceContext.url.substringAfter("//", "/"),
            referrer = "",
        )
        push(rt, type = "session_start", data = data)
        // Flush any crashes recovered during autoBootstrap (before
        // the runtime existed). One push per pending record — they
        // ride along in the first batch.
        val toFlush = synchronized(pendingCrashesLock) {
            if (pendingCrashes.isEmpty()) emptyList()
            else pendingCrashes.toList().also { pendingCrashes.clear() }
        }
        for (record in toFlush) pushCrash(rt, record)
    }

    private fun emitSessionEnd(rt: SessionRuntime, reason: String) {
        push(rt, type = "session_end", data = SessionEndEventData(reason))
    }

    private fun push(rt: SessionRuntime, type: String, data: Any) {
        val now = System.currentTimeMillis()
        val event = ReplayEvent(
            id = rt.makeEventId(),
            ts = now,
            offsetMs = now - rt.startedAt,
            type = type,
            source = PLATFORM_ANDROID,
            data = data,
        )
        rt.push(event)
        if (rt.shouldForceFlush()) {
            scope.launch(Dispatchers.IO) { flushNow() }
        }
    }

    /**
     * Drain the buffer and POST via [BatchUploader] — which falls
     * back to disk persistence + WorkManager retry when the live
     * send fails. Safe to call from any thread (uploader internals
     * handle their own threading).
     */
    private fun flushNow() {
        val rt = runtime ?: return
        val up = uploader ?: return
        val events = rt.drain()
        if (events.isEmpty()) return
        val envelope = ReplayBatchEnvelope(
            sessionId = rt.sessionId,
            segmentId = rt.segmentId,
            sequence = rt.nextSequence(),
            sentAt = System.currentTimeMillis(),
            sdk = rt.sdk,
            page = rt.deviceContext,
            events = events,
            projectId = config?.projectId,
        )
        up.upload(envelope)
    }

    /**
     * Coerce arbitrary property maps to JSON-safe shapes. Mirrors the
     * web SDK's `safeProps` — cap at 20 keys, 200 chars per string
     * value, drop functions/non-serializables.
     */
    private fun safeProps(props: Map<String, Any?>?): Map<String, Any?>? {
        if (props.isNullOrEmpty()) return null
        val out = LinkedHashMap<String, Any?>(props.size.coerceAtMost(20))
        var i = 0
        for ((k, v) in props) {
            if (i >= 20) break
            i++
            out[k] = when (v) {
                null, is Boolean, is Number -> v
                is String -> v.take(200)
                is Iterable<*> -> v.take(20).map { if (it is String) it.take(200) else it }
                else -> v.toString().take(200)
            }
        }
        return out
    }

    private fun buildPageContext(ctx: Context): ReplayPageContext {
        val res = ctx.resources
        val dm: DisplayMetrics = res.displayMetrics
        return ReplayPageContext(
            // Native sessions don't have a URL — we synthesize a
            // replay:// scheme deep-link so the backend's `url` column
            // stays a string + the dashboard's path filters keep
            // working. tagScreenName() will rewrite the trailing
            // segment when it lands.
            url = "replay://app/${ctx.packageName}",
            userAgent = buildUserAgent(ctx),
            viewport = ViewportDimensions(width = dm.widthPixels, height = dm.heightPixels),
            timezone = TimeZone.getDefault().id,
            // Locale.getDefault() works on every API level we support
            // (21+). Configuration.locales requires API 24, which
            // would force a runtime version guard for no benefit.
            language = Locale.getDefault().toLanguageTag(),
        )
    }

    private fun buildUserAgent(ctx: Context): String {
        val pkg = ctx.packageName
        val version = try {
            ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
        } catch (_: Throwable) { "?" }
        return "${BuildConfig.SDK_NAME}/${BuildConfig.SDK_VERSION} " +
            "($pkg/$version; ${Build.MANUFACTURER} ${Build.MODEL}; " +
            "Android ${Build.VERSION.RELEASE}; api ${Build.VERSION.SDK_INT})"
    }

    private const val TAG = "ReplaySdk"
}
