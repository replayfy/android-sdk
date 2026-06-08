package com.replayfy.android.internal

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.Locale
import com.replayfy.android.BuildConfig
import com.replayfy.android.ReplayConfig
import com.replayfy.android.internal.capture.SnapshotCapture
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
internal object LegacyCore {

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
    @Volatile private var consoleCapture: com.replayfy.android.internal.console.ConsoleCapture? = null
    @Volatile private var optOutStore: OptOutStore? = null
    @Volatile private var remoteConfigFetcher: RemoteConfigFetcher? = null

    /** Effective remote config. Null until the first fetch (or
     *  cached cold-launch apply) completes. Read by samplingGate(),
     *  shouldDropSessionAtEnd(), and any other code that needs to
     *  ask "did the dashboard say X?" without a fetch round-trip. */
    @Volatile private var remoteConfig: RemoteConfig? = null

    /** Per-session sampling decision — flips to true at session
     *  start when Math.random() > samplingRate (and no override
     *  applies). When true, [push] silently drops every event. */
    @Volatile private var sessionSampledOut: Boolean = false

    /**
     * Verification listeners — fired after the SDK's first
     * successful batch upload (server-side handshake confirmed).
     * Used during onboarding for "Verify integration" UI affordances.
     *
     * UXCam parity: `addVerificationListener` / `removeVerificationListener`.
     *
     * One-shot semantics — listeners run ONCE per process (no
     * re-fire on subsequent uploads even after [stop] / re-init).
     * Concurrent-safe; protected by [verificationListenersLock].
     */
    private val verificationListenersLock = Any()
    private val verificationListeners = ArrayList<() -> Unit>()
    @Volatile private var verificationFired: Boolean = false

    /** Snapshot-pipeline pause flag — toggled by Replay.pauseRecording /
     *  resumeRecording. Independent of opt-out (which is a privacy
     *  setting, persisted; this is a runtime toggle, in-memory only). */
    @Volatile private var snapshotPaused: Boolean = false

    // -----------------------------------------------------------------
    //  Session lifecycle controls (UXCam allowShortBreak +
    //  setMultiSessionRecord)
    // -----------------------------------------------------------------
    //
    // allowShortBreak — when true, a brief backgrounding (≤
    // shortBreakMs) does NOT end the session. The user returns
    // to the same session. Default false (every backgrounding
    // ends the session, matching the simpler analytics model).
    //
    // setMultiSessionRecord — when false, only ONE session per app
    // launch. Subsequent foregroundings DON'T start a new session;
    // they're no-ops. Default true.

    @Volatile private var allowShortBreak: Boolean = false
    @Volatile private var shortBreakMs: Long = 30_000L
    @Volatile private var multiSessionRecord: Boolean = true
    /** Counter incremented every time we [startNewSession] →
     *  emitSessionStart. Used by the multiSessionRecord=false gate
     *  to skip subsequent foreground events. */
    @Volatile private var sessionsThisProcess: Int = 0
    /** When allowShortBreak is on, a Runnable scheduled on the
     *  main handler that will fire `emitSessionEnd` after
     *  [shortBreakMs] of background. Foregrounding cancels it. */
    @Volatile private var pendingShortBreakEnd: Runnable? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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

        // Opt-out store — read FIRST so every downstream init
        // (lifecycle observer, crash handler, tap tracker) can
        // consult it. SharedPreferences is sync, ~1ms; fine on
        // the main thread (ContentProvider.onCreate calls us
        // there anyway).
        optOutStore = OptOutStore(context.applicationContext)
        if (optOutStore?.overallOptOut == true) {
            android.util.Log.i(
                TAG,
                "Replay SDK opted out — skipping autoBootstrap",
            )
            return
        }

        // Capture is owned entirely by the live ReplayCore engine (started
        // by Replay.init). LegacyCore no longer installs its own lifecycle /
        // tap / snapshot / perf / crash / NDK / console handlers: they
        // duplicated the live engine (waste + a second uncaught-exception
        // handler), and the legacy-only paths (console, NDK, crash recovery)
        // routed to the inert runtime and were dropped. Opt-out is now
        // enforced by ReplayCore itself. Re-homing console + NDK capture onto
        // the live engine so they actually record is the remaining #119
        // follow-up.
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
        // Re-wire the verification callback now that uploader
        // exists — covers the case where customer registered a
        // listener BEFORE init completed.
        synchronized(verificationListenersLock) {
            if (verificationListeners.isNotEmpty()) wireVerificationCallback()
        }

        if (cfg.distinctId != null) {
            newSender.identity = IdentifyPayload(distinctId = cfg.distinctId)
        }

        // Honor the customer's captureConsole opt-out. The collector
        // started UNCONDITIONALLY in autoBootstrap (so pre-init logs
        // aren't lost) gets stopped here when the customer disabled
        // it via config.
        if (!cfg.captureConsole) {
            consoleCapture?.stop()
            consoleCapture = null
        }

        // Wire the snapshot pipeline's cadence + kick off the periodic
        // loop. (Legacy bitmap-asset + thumbnail upload was removed with
        // the /v1/replay/assets + /v1/replay/thumbnail endpoints — this
        // path now emits tree-only snapshots.)
        snapshotCapture?.let { snap ->
            // Honor the customer's snapshot cadence preference;
            // remote config can re-tune this mid-session.
            snap.periodicIntervalMs = cfg.snapshotIntervalMs.coerceAtLeast(200L)
            // UXCam-style ~2 FPS continuous capture so the player
            // doesn't feel like a static gallery between screen
            // changes. Throttled by minIntervalMs inside doCapture
            // so a fast cadence + back-to-back triggers don't
            // double-fire.
            snap.startPeriodic()
        }

        // Honor the autoScreenName opt-out. Default-true; flipping
        // off means TapTracker.onActivityResumed no longer mutates
        // the route — only explicit Replay.tagScreenName() does.
        tapTracker?.autoScreenNameEnabled = cfg.autoScreenName

        // Network capture wire — the Interceptor itself is exposed
        // via Replay.networkInterceptor() for the customer to add to
        // their OkHttpClient. Here we just wire the emit + config
        // flags so the instance the customer creates can find them.
        com.replayfy.android.internal.network.ReplayInterceptor.currentEmit = ::emitNetwork
        com.replayfy.android.internal.network.ReplayInterceptor.configure(cfg)

        // Remote config fetcher — pulls dashboard settings + applies
        // them, OVERRIDING the customer-passed values above. Cold
        // launches use last-known-good from SharedPreferences;
        // periodic refresh (every 15 min) propagates dashboard flips
        // mid-session. Customers can opt out via
        // ReplayConfig.useRemoteConfig=false (kiosk / smoke-test).
        if (cfg.useRemoteConfig) {
            val fetcher = RemoteConfigFetcher(
                context = applicationContext,
                config = cfg,
                onApply = ::applyRemoteConfig,
            )
            remoteConfigFetcher = fetcher
            fetcher.start()
        }

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
        // Hoist well-known property keys to their dedicated
        // IdentifyPayload fields so the backend's upsertEndUser
        // can promote them to EndUser columns (used by the
        // dashboard's user list + filters). Everything else stays
        // in customProps as a JSON blob.
        //
        // Matches UXCam's behaviour: setUserIdentity("foo@bar.com")
        // populates the email column even when the customer passed
        // it via the generic properties bag.
        val email = (properties?.get("email") as? String)?.trim()?.lowercase()
        val name = (properties?.get("name") as? String)?.trim()
        val plan = (properties?.get("plan") as? String)?.trim()
        val rest = properties?.filter { (k, _) ->
            k != "email" && k != "name" && k != "plan"
        }?.takeIf { it.isNotEmpty() }

        s.identity = IdentifyPayload(
            distinctId = distinctId,
            email = email,
            name = name,
            plan = plan,
            customProps = rest,
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
    //  setUserProperty / setSessionProperty — UXCam-parity API
    // -----------------------------------------------------------------
    //
    // UXCam ships setUserProperty(key, value) + setSessionProperty(key,
    // value) as STICKY props (vs `track()` properties which only
    // attach to that one event). User props ride on EndUser.customProps
    // via the identity payload that's re-shipped with every batch.
    // Session props ride as custom events with kind="session_property"
    // — dashboard rendering as filterable Session columns is a
    // follow-up (see docs/sdk-capture-matrix.md §Dashboard items).
    //
    // Both methods accept Any? for value to match Kotlin/Java idioms
    // (String, Int, Double, Boolean, null all serializable via
    // JSONObject). UXCam has overloads per primitive type; Kotlin
    // doesn't need them.

    /** Local merged user-property store. Sent in every IdentifyPayload
     *  so re-batches carry the latest set. Thread-safe via the lock
     *  below — callers may invoke from any thread. */
    private val userProps = mutableMapOf<String, Any?>()
    private val userPropsLock = Any()

    fun setUserProperty(key: String, value: Any?) {
        if (key.isBlank()) return
        synchronized(userPropsLock) { userProps[key.take(80)] = value }
        // Re-send identify so the backend's upsertEndUser sees the
        // updated customProps on the next batch. Identity may be
        // null (no identify() call yet) — in that case the props
        // sit in our local map and ship when identify() is first
        // called.
        val s = sender ?: return
        val current = s.identity ?: return
        s.identity = current.copy(
            customProps = synchronized(userPropsLock) { userProps.toMap() },
        )
    }

    /** Local merged session-property store. Emitted as a single
     *  `session_property` custom event per call so the timeline
     *  preserves the order + values the customer set. */
    fun setSessionProperty(key: String, value: Any?) {
        val rt = runtime ?: run {
            android.util.Log.w(TAG, "setSessionProperty before session — dropping '$key'")
            return
        }
        if (key.isBlank()) return
        val safeKey = key.trim().take(80)
        val data = CustomEventData(
            kind = "session_property",
            name = safeKey,
            properties = safeProps(mapOf(safeKey to value)),
        )
        push(rt, type = "custom", data = data)
    }

    // -----------------------------------------------------------------
    //  UXCam-parity session-control surface
    // -----------------------------------------------------------------

    /**
     * Flip the auto-screen-detect Activity-tagger on/off at runtime.
     * Mirrors [ReplayConfig.autoScreenName] but at the call site
     * rather than init — customers turn auto-tagging off for a
     * specific modal flow (where the Activity class names don't map
     * to user-meaningful screens) and back on when they leave it.
     *
     * Mirrors UXCam's `setAutomaticScreenNameTagging(boolean)`.
     */
    fun setAutomaticScreenNameTagging(enabled: Boolean) {
        tapTracker?.autoScreenNameEnabled = enabled
    }

    /**
     * Attach the device's push-notification token to the session.
     * Emitted as a custom event with kind="push_token" so the
     * dashboard can render it as a chip on the player header AND
     * use it to deliver push from the replay UI (future feature;
     * dashboard side tracked in docs/sdk-capture-matrix.md).
     *
     * @param platform Defaults to "fcm" on Android; pass "huawei"
     *                 for HMS Push tokens. Forwarded as-is to the
     *                 backend so any consumer can filter by it.
     */
    @JvmOverloads
    fun setPushNotificationToken(token: String, platform: String = "fcm") {
        val rt = runtime ?: run {
            android.util.Log.w(TAG, "setPushNotificationToken before session — dropping")
            return
        }
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        // Cap at 256 chars — FCM tokens are ~152, HMS are similar;
        // anything longer is malformed + we don't want unbounded cost.
        val safeToken = trimmed.take(256)
        val data = CustomEventData(
            kind = "push_token",
            name = platform,
            properties = safeProps(mapOf("token" to safeToken, "platform" to platform)),
        )
        push(rt, type = "custom", data = data)
    }

    /**
     * Flag the current session as a favorite so it surfaces in the
     * "starred sessions" filter on the dashboard. Emitted as a
     * `session_favorite` custom event; backend promotes the flag
     * to Session.favorite when it processes the batch (backend
     * task tracked in docs).
     *
     * Mirrors UXCam's `markSessionAsFavorite()`. One-shot — call
     * once per session you want flagged.
     */
    fun markSessionAsFavorite() {
        val rt = runtime ?: run {
            android.util.Log.w(TAG, "markSessionAsFavorite before session — dropping")
            return
        }
        val data = CustomEventData(
            kind = "session_favorite",
            name = "favorite",
            properties = safeProps(mapOf("favorite" to true)),
        )
        push(rt, type = "custom", data = data)
    }

    /**
     * Attach a SESSION-level tag with optional properties. Distinct
     * from [track] (event-level): UXCam customers use session tags
     * for "this session belongs to A/B variant X" or "this session
     * is part of the holiday-checkout flow" — values that filter the
     * whole session list rather than show up as timeline events.
     *
     * Mirrors UXCam's `addTagWithProperties(name, properties)`. Ships
     * the same custom-event envelope; kind="session_tag" signals to
     * the backend that the tag should be promoted into Session.tags
     * (separate Prisma column — see docs).
     */
    @JvmOverloads
    fun addTagWithProperties(name: String, properties: Map<String, Any?>? = null) {
        val rt = runtime ?: run {
            android.util.Log.w(TAG, "addTagWithProperties before session — dropping '$name'")
            return
        }
        if (name.isBlank()) return
        val safeName = name.trim().take(80)
        val data = CustomEventData(
            kind = "session_tag",
            name = safeName,
            properties = safeProps(properties),
        )
        push(rt, type = "custom", data = data)
    }

    // -----------------------------------------------------------------
    //  GDPR opt-in / opt-out (UXCam parity)
    // -----------------------------------------------------------------

    /**
     * Set the overall opt-out flag. When true: every [push] call
     * silently drops, no new sessions start, no uploads fire,
     * nothing reaches the network. Persisted across launches so
     * once the customer opts out they STAY opted out until they
     * call [setOverallOptOut] with false (or your in-app
     * "rejoin" toggle does).
     *
     * Calling with true while a session is running:
     *   - Subsequent events drop at the [push] gate.
     *   - The current in-memory buffer is NOT auto-flushed (callers
     *     wanting that should chain to [stopAndUploadSync] first).
     *   - The on-disk queue is NOT auto-purged (use [cancelSession]
     *     for that).
     */
    fun setOverallOptOut(optedOut: Boolean) {
        val store = optOutStore ?: run {
            // autoBootstrap hadn't run yet (rare — would mean the
            // customer called Replay.optOut() before the
            // ContentProvider fired). Lazily build one against the
            // first context we can find.
            appContext?.let {
                val s = OptOutStore(it)
                optOutStore = s
                s
            } ?: run {
                android.util.Log.w(TAG, "setOverallOptOut before bootstrap — dropping")
                return
            }
        }
        store.overallOptOut = optedOut
        if (optedOut) {
            // Stop the active session if any — customers opting out
            // mid-flow expect everything to halt immediately.
            stop()
        }
    }

    /** Get the persisted overall opt-out flag. Defaults to false
     *  (opted in) when bootstrap hasn't run. */
    fun isOverallOptedOut(): Boolean = optOutStore?.overallOptOut == true

    /**
     * Set the schematic opt-out flag. When true: snapshots stop
     * being captured + native_snapshot events drop at [push].
     * Other event types (tap, network, console, perf) keep
     * flowing so the dashboard still gets an interaction log,
     * just without pixel data. Persisted across launches.
     *
     * Useful for screens with regulated pixel content (HIPAA
     * health UI) where the customer still wants click + flow
     * analytics.
     */
    fun setSchematicOptOut(optedOut: Boolean) {
        val store = optOutStore ?: run {
            appContext?.let {
                val s = OptOutStore(it)
                optOutStore = s
                s
            } ?: run {
                android.util.Log.w(TAG, "setSchematicOptOut before bootstrap — dropping")
                return
            }
        }
        store.schematicOptOut = optedOut
        syncSnapshotEnabled()
    }

    /** Get the persisted schematic-capture opt-out flag. */
    fun isSchematicOptedOut(): Boolean = optOutStore?.schematicOptOut == true

    /** Compute the effective recording-enabled state from the two
     *  independent toggles (schematic opt-out + runtime pause) and
     *  push it down to SnapshotCapture. Called whenever either
     *  flag flips. */
    private fun syncSnapshotEnabled() {
        val optedOut = optOutStore?.schematicOptOut == true
        snapshotCapture?.enabled = !snapshotPaused && !optedOut
    }

    /** Runtime pause toggle. Independent of opt-out — pauses only
     *  the snapshot pipeline; events keep flowing. Used by
     *  `Replay.pauseRecording()` / `resumeRecording()`. NOT
     *  persisted: process restart resets to running. */
    fun setSnapshotPaused(paused: Boolean) {
        snapshotPaused = paused
        syncSnapshotEnabled()
    }

    fun isSnapshotPaused(): Boolean = snapshotPaused

    // -----------------------------------------------------------------
    //  Remote-config apply
    // -----------------------------------------------------------------

    /** Apply a fresh [RemoteConfig] to the running SDK. Called
     *  from the fetcher on every successful fetch + once at cold
     *  launch with the cached value (if any). Idempotent — repeat
     *  applies of the same config are no-ops at the toggle level
     *  because we set rather than mutate every flag. */
    private fun applyRemoteConfig(cfg: RemoteConfig) {
        android.util.Log.i(TAG, "applyRemoteConfig: capture.network=${cfg.captureNetwork} capture.console=${cfg.captureConsole} sampling=${cfg.samplingRate}")
        remoteConfig = cfg

        // Console capture — start/stop dynamically. The collector
        // installs System.out/err interception, idempotent.
        val ctx = appContext
        if (cfg.captureConsole) {
            if (consoleCapture == null && ctx != null) {
                val cc = com.replayfy.android.internal.console.ConsoleCapture { ev ->
                    runtime?.let { rt -> push(rt, type = "console", data = ev) }
                }
                cc.start()
                consoleCapture = cc
            }
        } else {
            consoleCapture?.stop()
            consoleCapture = null
        }

        // Network capture flags — Interceptor reads from the static
        // companion every request, so flipping these takes effect on
        // the next OkHttp call. captureNetwork=false turns the
        // interceptor into a pass-through (emit=null).
        com.replayfy.android.internal.network.ReplayInterceptor.enabled = cfg.captureNetwork
        com.replayfy.android.internal.network.ReplayInterceptor.captureHeaders = cfg.captureNetworkHeaders
        // bodies: zero out maxBodyBytes when captureNetworkBodies=false
        // so peekBody returns empty. Customers who set a custom
        // baseline maxBodyBytes lose it when the dashboard flips off
        // — that's the override semantic.
        com.replayfy.android.internal.network.ReplayInterceptor.maxBodyBytes =
            if (cfg.captureNetworkBodies) 4_096 else 0
        com.replayfy.android.internal.network.ReplayInterceptor.redactPatterns =
            cfg.privacyRedactUrlPatterns.mapNotNull {
                try { Regex(it) } catch (_: Throwable) { null }
            }

        // Performance metrics — start/stop the manager. Stopping
        // tears down Choreographer + memory poller + thermal
        // listener cleanly.
        if (cfg.capturePerformance) {
            perfMetrics?.start()
        } else {
            perfMetrics?.stop()
        }

        // Privacy — maskAllInputs maps to occludeAllTextFields(true).
        // The dashboard's setting is a workspace-level default; the
        // customer's per-call occludeAllTextFields(false) STILL gets
        // overridden by a true dashboard value (dashboard always wins).
        com.replayfy.android.internal.privacy.PrivacyRegistry.occludeAllTextFields =
            cfg.privacyMaskAllInputs
    }

    /** Decide whether to sample IN the current session. Called once
     *  at session_start. Uses a uniform [0,1) random draw against
     *  the dashboard's samplingRate, with two override gates:
     *
     *    - alwaysRecordIdentified: a sender.identity.distinctId
     *      set BEFORE session_start (from config.distinctId or a
     *      pre-init identify()) forces sampling in.
     *    - alwaysRecordErrors: applied retroactively at session
     *      end — a sampled-out session that DID emit an error gets
     *      flushed anyway (handled in stop()).
     *
     *  When sampled out, the session_start still fires (so the
     *  dashboard can show "N sessions sampled out today"), but
     *  every subsequent event is dropped at push() — including
     *  snapshot uploads. */
    private fun computeSamplingDecision(): Boolean {
        val rc = remoteConfig ?: return false // no remote yet → sample in
        if (rc.samplingRate >= 1.0) return false
        if (rc.samplingRate <= 0.0) return true
        // alwaysRecordIdentified override.
        if (rc.samplingAlwaysRecordIdentified) {
            val did = sender?.identity?.distinctId
            if (!did.isNullOrEmpty()) return false
        }
        return Math.random() > rc.samplingRate
    }

    // -----------------------------------------------------------------
    //  Session lifecycle controls (UXCam parity)
    // -----------------------------------------------------------------

    /**
     * Toggle the "brief backgrounding doesn't end the session"
     * behaviour. When true, a backgrounding ≤ [breakWindowMs]
     * does NOT trigger session_end — the same session resumes
     * when the user returns. Default false.
     *
     * Mirrors UXCam's `allowShortBreakForAnotherApp(boolean)`. The
     * window defaults to 30 s; pass a custom value when the
     * customer wants tighter / looser semantics (e.g. 60 s for
     * authentication flows where the user switches to an
     * authenticator app).
     */
    @JvmOverloads
    fun setAllowShortBreak(allow: Boolean, breakWindowMs: Long = 30_000L) {
        allowShortBreak = allow
        shortBreakMs = breakWindowMs.coerceAtLeast(0L)
    }

    fun isAllowShortBreak(): Boolean = allowShortBreak

    /**
     * Toggle multi-session recording per app launch. When false,
     * only ONE session fires per process — subsequent foregrounds
     * after a session_end are no-ops. Default true (every
     * foreground after end yields a new session).
     *
     * Mirrors UXCam's `setMultiSessionRecord(boolean)`. Useful for
     * customers tracking ONE-launch flows (kiosks, onboarding
     * wizards) where breaking into multiple sessions confuses
     * analytics.
     */
    fun setMultiSessionRecord(enabled: Boolean) {
        multiSessionRecord = enabled
    }

    fun isMultiSessionRecord(): Boolean = multiSessionRecord

    // -----------------------------------------------------------------
    //  UXCam-parity deep-link helpers
    // -----------------------------------------------------------------

    /**
     * Returns the dashboard URL for the current session, or null
     * when no session is active. URL shape:
     *   `<dashboardHost>/sessions/<sessionId>`
     * where `dashboardHost` is derived from [ReplayConfig.apiHost]
     * by substituting the leading `api.` host segment with `app.`
     * (so `https://api.replayfy.io` → `https://app.replayfy.io`).
     * Self-hosted customers' hosts work unchanged.
     */
    fun urlForCurrentSession(): String? {
        val rt = runtime ?: return null
        val host = dashboardHost() ?: return null
        return "$host/sessions/${rt.sessionId}"
    }

    /**
     * Returns the dashboard URL for the currently-identified user
     * (all sessions for the active distinctId), or null when no
     * `identify()` has been called yet.
     */
    fun urlForCurrentUser(): String? {
        val did = sender?.identity?.distinctId ?: return null
        val host = dashboardHost() ?: return null
        return "$host/users/$did"
    }

    /** Derive the dashboard host from the configured apiHost. */
    private fun dashboardHost(): String? {
        val api = config?.apiHost ?: return null
        // Strip trailing slash so we can append cleanly.
        val trimmed = api.trimEnd('/')
        // Replace the FIRST occurrence of "://api." with "://app."
        // — works for the canonical replayfy.io host AND any
        // self-hosted deployment that follows the same convention.
        val idx = trimmed.indexOf("://api.")
        return if (idx >= 0) {
            trimmed.substring(0, idx) + "://app." + trimmed.substring(idx + 7)
        } else {
            // Unknown host shape (e.g. customer using an internal
            // domain). Return as-is — better a working link to the
            // ingest host than nil, the dashboard can redirect.
            trimmed
        }
    }

    /**
     * Discard the current session entirely — clears the in-memory
     * buffer and DELETES any on-disk batches still queued for
     * upload so the session never reaches the backend.
     *
     * Different from [stop] which gracefully ends the session +
     * uploads what it has. Customers reach for this when the user
     * triggers a flow they actively don't want recorded (e.g. a
     * "delete my activity" button) AFTER content has already been
     * captured.
     *
     * No new session auto-starts — the next foregrounding will, per
     * the standard lifecycle. Call [stop] first if you also want
     * to suppress that.
     *
     * Mirrors UXCam's `cancelCurrentSession()`.
     */
    fun cancelCurrentSession() {
        val rt = runtime
        if (rt != null) {
            // Drop the buffered events without emitting session_end
            // (we're abandoning the session, not closing it).
            rt.drain()
            runtime = null
        }
        flushJob?.cancel()
        flushJob = null
        // Wipe the on-disk retry queue too. Anything that was
        // persisted from a prior failed upload gets dropped along
        // with the active buffer.
        try { uploader?.clearQueue() } catch (_: Throwable) {}
        // Stop perf collectors + console capture so they don't
        // bleed into the NEXT auto-started session as if no break
        // had happened.
        perfMetrics?.stop()
        perfMetrics = null
        consoleCapture?.stop()
        consoleCapture = null
    }

    // -----------------------------------------------------------------
    //  Verification listeners (UXCam onboarding parity)
    // -----------------------------------------------------------------

    /** Register a listener — invoked on the main thread immediately
     *  if verification has already fired this process, otherwise
     *  on the first successful batch upload. Idempotent (adding the
     *  same closure instance twice is harmless; both will fire once
     *  each since we hold them by identity). */
    fun addVerificationListener(listener: () -> Unit) {
        // If verification already happened this process, fire
        // synchronously so customers calling addListener AFTER
        // onboarding don't get stuck waiting for a second upload
        // that never comes.
        if (verificationFired) {
            try { listener() } catch (_: Throwable) {}
            return
        }
        synchronized(verificationListenersLock) {
            verificationListeners.add(listener)
            // Lazy wire on first listener add. uploader may be null
            // if init hasn't completed yet — we'll re-wire post-init
            // via [wireVerificationCallback].
            wireVerificationCallback()
        }
    }

    /** Drop a previously-added listener. Safe to call even when
     *  the listener was never added or already fired. */
    fun removeVerificationListener(listener: () -> Unit) {
        synchronized(verificationListenersLock) {
            verificationListeners.remove(listener)
        }
    }

    /** Hook the uploader's one-shot success callback to drain the
     *  listener list. Called when listeners are added AND when
     *  the uploader becomes available during init. */
    private fun wireVerificationCallback() {
        val up = uploader ?: return
        if (verificationFired) return
        up.onFirstUploadSuccess = {
            verificationFired = true
            val toFire = synchronized(verificationListenersLock) {
                val copy = verificationListeners.toList()
                verificationListeners.clear()
                copy
            }
            // Hop to main thread for the callback — customers
            // typically use it to update UI ("Verified ✓" badge).
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            for (l in toFire) {
                mainHandler.post {
                    try { l() } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * Emit a manual bug-report event. UXCam customers wire this to
     * a "Send Feedback" / "Report Issue" button in their app —
     * the dashboard then surfaces these as flagged events in the
     * session timeline so support can jump straight to the
     * relevant moment.
     *
     * Triggers an IMMEDIATE snapshot so the dashboard has fresh
     * pixels at the bug-report timestamp regardless of where in
     * the regular capture cadence we are.
     *
     * Mirrors UXCam's `reportBugEvent(name, description)`. Any
     * additional structured properties the customer wants attached
     * (form fields, device state) go via the [properties] map.
     */
    @JvmOverloads
    fun reportBugEvent(
        name: String,
        description: String? = null,
        properties: Map<String, Any?>? = null,
    ) {
        val rt = runtime ?: run {
            android.util.Log.w(TAG, "reportBugEvent before session — dropping '$name'")
            return
        }
        if (name.isBlank()) return
        val safeName = name.trim().take(80)
        // Merge the caller's optional properties with description
        // (if any). description gets a reserved key so the dashboard
        // can render it as the prominent text body.
        val merged = HashMap<String, Any?>()
        if (!description.isNullOrBlank()) {
            merged["description"] = description.take(2_000)
        }
        if (properties != null) merged.putAll(properties)
        val data = CustomEventData(
            kind = "bug_report",
            name = safeName,
            properties = safeProps(merged),
        )
        push(rt, type = "custom", data = data)
        // Fire a fresh snapshot so the dashboard has the current
        // screen at the moment the bug was reported. Customers
        // who pair this with a "screenshot attached" UI affordance
        // will see exactly what the user saw.
        snapshotCapture?.captureNow("bug_report")
    }

    /**
     * Force-end the current session + start a fresh one. UXCam's
     * `startNewSession()` — used by customers who track logical
     * session boundaries inside one app process (logout-then-login,
     * A/B re-bucketing, "start over" flows).
     *
     * Drains the current session's buffer first so its events don't
     * bleed into the new session, then spawns a new SessionRuntime
     * with a new sessionId. Auto-screen-tagger picks up the next
     * Activity as the new session's first screen.
     */
    fun forceStartNewSession() {
        val rt = runtime
        if (rt != null) {
            emitSessionEnd(rt, reason = "manual_new")
            scope.launch(Dispatchers.IO) { flushNow() }
        }
        val newRt = startNewSession()
        emitSessionStart(newRt)
    }

    /**
     * Stop recording AND block (up to [timeoutMs]) until the
     * in-memory buffer has been uploaded or persisted to disk.
     * Customers call this from a sign-out flow or just before
     * `System.exit` when they want strong-delivery semantics for
     * the current session's events.
     *
     * Caps the wait so a wedged network can't hang the host
     * process. Default 5 000 ms.
     *
     * Mirrors UXCam's `stopApplicationAndUploadData(runnable)`. We
     * expose a blocking wait rather than the callback variant
     * because the Kotlin coroutine + ExecutorService machinery
     * makes it equally simple.
     */
    @JvmOverloads
    fun stopAndUploadSync(timeoutMs: Long = 5_000) {
        val rt = runtime
        if (rt != null) {
            emitSessionEnd(rt, reason = "app_terminating")
            val latch = java.util.concurrent.CountDownLatch(1)
            scope.launch(Dispatchers.IO) {
                try { flushNow() } finally { latch.countDown() }
            }
            // Cap so a wedged network doesn't hang shutdown.
            try {
                latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // Tear down the rest of the SDK as if stop() had been called.
        stop()
    }

    // -----------------------------------------------------------------
    //  Lifecycle
    // -----------------------------------------------------------------

    private fun onAppForegrounded() {
        // No config yet → bootstrap has nothing to do. We'll start the
        // session when init lands.
        if (config == null) return
        // If we backgrounded recently AND allowShortBreak is on,
        // cancel the pending end-of-session task — the user is
        // resuming the SAME session, not starting a new one. UXCam
        // parity: "switching to copy a 2FA code shouldn't fragment
        // analytics into two sessions."
        if (pendingShortBreakEnd != null) {
            mainHandler.removeCallbacks(pendingShortBreakEnd!!)
            pendingShortBreakEnd = null
            // Runtime still alive — nothing else to do.
            if (runtime != null) return
        }
        // Already-running session AND we haven't backgrounded yet → no-op.
        if (runtime != null) return
        // setMultiSessionRecord(false) gate: if a session has
        // ALREADY existed this process, don't start a new one on
        // re-foreground. The customer wants exactly one session
        // per launch.
        if (!multiSessionRecord && sessionsThisProcess > 0) {
            android.util.Log.i(TAG, "multiSessionRecord=false — skipping new session on foreground")
            return
        }
        val rt = startNewSession()
        sessionsThisProcess++
        emitSessionStart(rt)
        // Cold-start measurement — emit once per process. Idempotent
        // (returns null after the first call), so subsequent
        // foregrounds don't re-emit.
        perfMetrics?.reportFirstForeground()
        // Resume the UXCam-style continuous capture loop. Idempotent
        // — startPeriodic cancels the previous Runnable first, so an
        // accidental double-foreground (e.g. dialog dismiss racing
        // with onResume) won't double-schedule.
        snapshotCapture?.startPeriodic()
    }

    private fun onAppBackgrounded() {
        val rt = runtime ?: return
        if (allowShortBreak && shortBreakMs > 0) {
            // Schedule the session-end to fire AFTER shortBreakMs;
            // foreground returning before that cancels the task and
            // keeps the same session alive.
            pendingShortBreakEnd?.let { mainHandler.removeCallbacks(it) }
            val task = Runnable {
                pendingShortBreakEnd = null
                val r = runtime ?: return@Runnable
                emitSessionEnd(r, reason = "background")
                scope.launch(Dispatchers.IO) { flushNow() }
            }
            pendingShortBreakEnd = task
            mainHandler.postDelayed(task, shortBreakMs)
            return
        }
        emitSessionEnd(rt, reason = "background")
        // Halt the continuous capture loop while the app is in the
        // background — we'd otherwise burn the main thread cancelling
        // PixelCopy requests that can't acquire a SurfaceView. Resumes
        // when the customer foregrounds and we kick off a new session.
        snapshotCapture?.stopPeriodic()
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
        // Stop continuous capture before tearing down — leaving the
        // Runnable scheduled would resurrect captures into a stopped
        // runtime (snapshotCapture.enabled is true by default).
        snapshotCapture?.stopPeriodic()
        flushJob?.cancel()
        flushJob = null
        runtime = null
        // Stop background perf collectors — Choreographer callback,
        // memory timer, thermal listener. Restarts when LegacyCore
        // re-initialises (which doesn't happen in v1 but the
        // teardown discipline is correct).
        perfMetrics?.stop()
        perfMetrics = null
        // Restore original System.out / System.err. Idempotent —
        // safe to call when consoleCapture was never started (when
        // captureConsole=false).
        consoleCapture?.stop()
        consoleCapture = null
        // Clear the network-interceptor emit closure so any
        // in-flight OkHttp calls' interceptor doesn't push into
        // the stopped runtime. The customer's interceptor instance
        // stays installed on their OkHttpClient (we don't own it),
        // but it'll silently no-op once emit is null.
        com.replayfy.android.internal.network.ReplayInterceptor.currentEmit = null
        com.replayfy.android.internal.network.ReplayInterceptor.enabled = false
        // Stop the remote-config fetcher's periodic poll so we don't
        // leak a coroutine after the SDK shuts down. The next init()
        // re-creates the fetcher with the new ReplayConfig.
        remoteConfigFetcher?.stop()
        remoteConfigFetcher = null
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

    private fun emitConsole(event: ConsoleEventData) {
        val rt = runtime ?: return
        push(rt, type = "console", data = event)
    }

    /** Public-API entry for explicit console logging — called by
     *  [com.replayfy.android.Replay.log] after the SDK is initialised. */
    fun logExplicit(level: String, message: String, stack: String?) {
        consoleCapture?.emitExplicit(level = level, message = message, stack = stack)
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
     *  the tagged screen with its new identity.
     *
     *  Also emits a `custom { kind: "screen" }` event so the
     *  dashboard's `/screens` endpoint has something to count.
     *  Without this row, the dashboard's Screens tab shows
     *  "No screens recorded" even after navigation — `pageCount`
     *  on the Session row (driven by batch envelope page.url
     *  increments) goes up, but the ClickHouse `kind='screen'`
     *  rows that the Screens panel queries stay empty. */
    fun setRoute(route: String) {
        val cleaned = route.trim().take(120)
        if (cleaned.isBlank()) return
        tapTracker?.setRoute(cleaned)
        // Same-route duplicate suppression — the auto-screen detector
        // re-fires on every Activity onResume, including dialog
        // dismiss / config change cases where the route hasn't
        // actually changed. Drop those so the dashboard timeline
        // doesn't fill with phantom screens.
        val rt = runtime
        if (rt != null && cleaned != lastEmittedScreen) {
            lastEmittedScreen = cleaned
            val data = CustomEventData(
                kind = "screen",
                name = cleaned,
                properties = mapOf("name" to cleaned),
            )
            push(rt, type = "custom", data = data)
        }
        snapshotCapture?.captureNow("manual")
    }

    /** Last screen name we shipped a `kind="screen"` event for —
     *  used by [setRoute] to dedupe the auto-screen detector firing
     *  repeatedly with the same name. Reset to null on session start. */
    @Volatile
    private var lastEmittedScreen: String? = null

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
        // Resolve app version on each session start so customers
        // running side-loaded / in-app update flows pick up new
        // versions without restart. Reads are cheap (PackageInfo
        // is cached by PackageManager).
        val (appVer, appBuild) = resolveAppVersion(ctx)
        val rt = SessionRuntime(
            config = config!!,
            deviceContext = buildPageContext(ctx),
            appVersion = appVersionOverride ?: appVer,
            appBuild = appBuildOverride ?: appBuild,
        )
        runtime = rt
        // Sampling decision — one per session, computed against the
        // dashboard's samplingRate. Sampled-out sessions emit
        // session_start (so the dashboard can count them) but then
        // every subsequent event drops at push().
        sessionSampledOut = computeSamplingDecision()
        if (sessionSampledOut) {
            android.util.Log.i(TAG, "session ${rt.sessionId} sampled out (rate=${remoteConfig?.samplingRate})")
        }
        // Reset the per-session screen-dedup memo so the first
        // setRoute call after a new session ships even if the
        // route happens to match the last session's last screen.
        lastEmittedScreen = null
        return rt
    }

    /** Customer overrides for [SessionRuntime] app-version capture.
     *  Set via [Replay.setAppVersion]. When non-null, these take
     *  precedence over the values read from PackageManager. */
    @Volatile private var appVersionOverride: String? = null
    @Volatile private var appBuildOverride: String? = null

    /** Public setter for the app-version override. Wired from
     *  [Replay.setAppVersion]; called by customers whose host-app
     *  versioning doesn't match the PackageInfo values (e.g. flavor
     *  builds, dynamic feature modules). */
    fun setAppVersionOverride(version: String?, build: String?) {
        appVersionOverride = version
        appBuildOverride = build
    }

    /** Read the host app's versionName + versionCode via PackageManager.
     *  Returns nulls if the lookup throws (PackageManager.NameNotFound
     *  is theoretically possible but vanishingly rare). */
    private fun resolveAppVersion(ctx: Context): Pair<String?, String?> {
        return try {
            val pm = ctx.packageManager
            val info = pm.getPackageInfo(ctx.packageName, 0)
            val name = info.versionName
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
            Pair(name, code)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "app version lookup failed: ${t.message}")
            Pair(null, null)
        }
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
        // Overall opt-out → silently drop. Single gate over the
        // entire emit pipeline; every other path (tap, snapshot,
        // crash, perf metric) ends up here. session_end events fired
        // by [stop] still pass through because stop() runs before
        // the opt-out toggle in normal use; toggling opt-out
        // mid-session is a customer choice that we honour by
        // dropping subsequent events.
        if (optOutStore?.overallOptOut == true) return
        // Snapshot opt-out → drop native_snapshot events only. The
        // rest of the timeline (taps, network, console) keeps
        // flowing so the dashboard still shows an interaction
        // record even though the player stage is empty.
        if (type == "native_snapshot" && optOutStore?.schematicOptOut == true) return
        // Sampling drop. session_start + session_end ALWAYS go
        // through (so the dashboard's sampled-out count is
        // accurate); everything in between drops silently.
        if (sessionSampledOut && type != "session_start" && type != "session_end") {
            // alwaysRecordErrors retroactive override — let crashes
            // through even on sampled-out sessions so the customer
            // can debug them. The flag flips for the rest of the
            // session so subsequent taps + perf events also land
            // (without context the stack alone isn't actionable).
            val rc = remoteConfig
            if (rc?.samplingAlwaysRecordErrors == true && type == "error") {
                android.util.Log.i(TAG, "sampled-out session unmuted by alwaysRecordErrors")
                sessionSampledOut = false
            } else {
                return
            }
        }
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
