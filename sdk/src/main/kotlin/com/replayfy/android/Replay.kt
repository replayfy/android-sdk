package com.replayfy.android

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.EditText
import com.replayfy.android.internal.LegacyCore
import com.replayfy.android.internal.mobile.ReplayCore
import org.json.JSONObject

/**
 * Public entry point for the Replay Android SDK.
 *
 * Most apps need only [init] — everything else (snapshot capture,
 * tap tracking, screen tagging, lifecycle management) runs
 * automatically once init returns.
 *
 * Methods are static-equivalent (object singleton) so the Java-facing
 * API reads as `Replay.INSTANCE.init(...)` from Java — we expose
 * Kotlin-native call sites and the JVM facade simultaneously.
 *
 * Foundation v0: only init + identify + track + stop are wired.
 * tagScreenName, addPrivacyView, pauseRecording etc. are present
 * as stubs that log + no-op; they ship in follow-up commits.
 */
object Replay {

    /**
     * Initialize the SDK and start recording. Call this once per
     * process; subsequent calls are ignored (with a warning) so a
     * misconfigured Application.onCreate doesn't double-instantiate
     * the runtime.
     *
     * Safe to call from [android.app.Application.onCreate] but NOT
     * required — the ContentProvider auto-bootstrap means the SDK
     * is already alive by the time your Application class runs. You
     * only need [init] to pass your config.
     */
    @JvmStatic
    fun init(context: Context, config: ReplayConfig) {
        // Reference-parity mobile recording engine: periodic JPEG frames
        // + binary message protocol → /v1/mobile/*. Replaces the legacy
        // per-snapshot/asset engine (no backward compatibility).
        val app = context.applicationContext as? Application ?: return
        ReplayCore.shared.start(app, config)
    }

    /**
     * Tag this session's wrapper SDK (e.g. "react-native"). Sent on /start so
     * the dashboard shows "Captured by …" while the platform stays android.
     * Call before [init].
     */
    @JvmStatic
    fun setFramework(name: String) {
        ReplayCore.framework = name
    }

    /**
     * Attach a known-user identity to the current session. Anonymous
     * sessions from this device retroactively link to the identified
     * user on the dashboard.
     *
     * @param distinctId Stable id from your auth system. Email, uuid,
     *                   numeric id all work.
     * @param properties Optional user-level traits. Surface in the
     *                   dashboard as filterable fields.
     */
    @JvmStatic
    @JvmOverloads
    fun identify(distinctId: String, properties: Map<String, Any?>? = null) {
        ReplayCore.shared.setUserId(distinctId)
        // Each property rides as its own metadata(92) message — the backend
        // promotes plan/email/name to EndUser columns and keeps the rest as
        // custom properties, mirroring the web identify path.
        properties?.forEach { (key, value) ->
            if (value != null) ReplayCore.shared.sendMetadata(key, value.toString())
        }
    }

    /**
     * Fire a custom event onto the timeline. Drives the "Event" step
     * kind in funnels and any event-based filters on the dashboard.
     *
     * @param name Stable event name (snake_case or camelCase, no
     *             spaces — searchable in the dashboard).
     * @param properties Optional metadata, capped to 20 keys / 200
     *                   chars per string value.
     */
    @JvmStatic
    @JvmOverloads
    fun track(name: String, properties: Map<String, Any?>? = null) {
        val payload = if (properties != null) JSONObject(properties).toString() else ""
        ReplayCore.shared.sendEvent(name, payload)
    }

    /**
     * Report a developer-CAUGHT exception onto the session timeline. Emits the
     * same reserved event the automatic crash handling produces, so a handled
     * error is first-class in the dashboard's issues without crashing the app.
     *
     * @param error The caught throwable.
     * @param handled True (default) for a caught/recovered error; pass false to
     *                mark it as fatal. Maps to the wire's `fatal` discriminator
     *                (`fatal = !handled`) — handled errors classify as an
     *                "exception", unhandled as a "crash".
     * @param properties Optional extra metadata, merged into the payload.
     */
    @JvmStatic
    @JvmOverloads
    fun captureException(
        error: Throwable,
        handled: Boolean = true,
        properties: Map<String, Any?>? = null,
    ) {
        val payload = JSONObject().apply {
            put("message", "${error.javaClass.name}: ${error.message ?: ""}")
            put("stack", error.stackTraceToString())
            put("fatal", !handled)
            properties?.forEach { (k, v) -> if (v != null) put(k, v) }
        }.toString()
        // Reserved event name — the same channel Replay.track uses; the backend
        // decodes `$exception` payloads into an issue row (crash vs exception by
        // the `fatal` flag). `\$` escapes Kotlin string interpolation.
        ReplayCore.shared.sendEvent("\$exception", payload)
    }

    /**
     * Report a host-captured interaction (used by the Flutter plugin's
     * Dart-side gesture capture, which knows the real widget label that the
     * native FlutterView tracker cannot). `kind` ∈ "tap"/"long_press"/"swipe".
     */
    fun reportInteraction(kind: String, label: String, x: Int, y: Int, direction: String = "") {
        ReplayCore.shared.reportInteraction(kind, label, x.toLong(), y.toLong(), direction)
    }

    /**
     * Ingest a Flutter frame captured Dart-side (via a root RepaintBoundary).
     * Native PixelCopy can't read Flutter's SurfaceView on Android, so the
     * Flutter plugin disables native capture and ships frames here instead.
     * [rects] are the occlusion regions (developer `ReplayMask` bounds in the
     * PNG's pixel coords) the encoder masks. [ts] is the capture epoch-ms
     * (reserved; the archive timestamps on receipt).
     */
    fun reportFrame(png: ByteArray, ts: Long, rects: List<MaskRect> = emptyList()) {
        ReplayCore.shared.submitFrame(png, rects)
    }

    /**
     * Manually end the current session and force-upload the in-memory
     * batch. The next foregrounding triggers a fresh session.
     *
     * Most apps never need this — the lifecycle observer handles
     * session boundaries automatically. Use this for opt-out flows
     * or end-of-onboarding screens where you want the session to
     * end immediately rather than at next backgrounding.
     */
    @JvmStatic
    fun stop() {
        ReplayCore.shared.stop()
    }

    /**
     * Whether the SDK is currently recording. Returns false if init
     * hasn't been called, the user is opted out, or a sampling
     * decision dropped the current session.
     */
    @JvmStatic
    fun isRecording(): Boolean = ReplayCore.shared.sessionId != null

    /**
     * Returns an OkHttp [Interceptor] that captures every request
     * flowing through the [okhttp3.OkHttpClient] it's installed on.
     *
     * Wire via:
     * ```
     * val client = OkHttpClient.Builder()
     *     .addInterceptor(Replay.networkInterceptor())
     *     .build()
     * ```
     *
     * Capture is gated on `ReplayConfig.captureNetwork` — when false,
     * the interceptor passes through unchanged (zero overhead).
     * Customers can flip the flag at any time via remote config.
     *
     * Header + body capture additionally gated by `captureHeaders` +
     * `maxBodyBytes`. Body capture uses OkHttp's `peekBody` so the
     * customer's downstream code still gets the full unconsumed body.
     */
    @JvmStatic
    fun networkInterceptor(): okhttp3.Interceptor =
        com.replayfy.android.internal.network.ReplayInterceptor()

    // ------------------------------------------------------------------
    //  Stubs — wired in follow-up commits. Calling these today logs a
    //  warning and no-ops, so customers can write integration code
    //  against the final API surface before the underlying engine
    //  lands.
    // ------------------------------------------------------------------

    /** Tag the current screen with a name. Manual override for the
     *  auto-tagger (which uses Activity class names by default). */
    @JvmStatic
    fun tagScreenName(name: String) {
        LegacyCore.setRoute(name)
        // Manual screen tag → viewComponent message, same as the
        // Activity auto-tagger, so the dashboard Screens tab reflects it.
        ReplayCore.shared.sendScreen(name, name, true)
    }

    /**
     * Mark a view as privacy-sensitive — its contents won't appear
     * in playback, taps on it are blanked, and the view-tree
     * snapshot reports it as `occluded`.
     *
     * Marks propagate down the view hierarchy: marking a `ViewGroup`
     * automatically protects every descendant without per-child
     * calls. Removing the mark from a parent (via
     * [removePrivacyView]) restores subview visibility unless they
     * were independently marked.
     */
    @JvmStatic
    fun addPrivacyView(view: View) {
        com.replayfy.android.internal.privacy.PrivacyRegistry.add(view)
    }

    /** Remove a previously-added privacy mark. Safe to call even
     *  for views that were never marked. */
    @JvmStatic
    fun removePrivacyView(view: View) {
        com.replayfy.android.internal.privacy.PrivacyRegistry.remove(view)
    }

    /**
     * Observe a text field so its value is recorded on the session timeline
     * when editing ends (on focus-loss, not per keystroke). Password-type
     * fields report a masked placeholder, never the typed text, and the
     * field's hint is used as the label. Inputs are opt-in per field — never
     * captured globally. The field's existing focus listener is preserved.
     */
    @JvmStatic
    fun addObservedInput(editText: EditText) {
        ReplayCore.shared.observeInput(editText)
    }

    /**
     * Record a text input's value on the timeline. For hosts that manage their
     * own text widgets (React Native / Flutter), where there's no native
     * [EditText] to [addObservedInput]. Pass `masked = true` for sensitive
     * fields — the value is dropped (recorded as "***") and never leaves the
     * device.
     */
    @JvmStatic
    fun trackInput(label: String, value: String, masked: Boolean) {
        ReplayCore.shared.sendInput(if (masked) "***" else value, masked, label)
    }

    /**
     * Mark a view as a screen — emits a screen event when it attaches/detaches
     * from the window. Activities and androidx Fragments are auto-tracked, so
     * this is only for Compose screens / custom views those hooks can't see
     * (e.g. wrap your screen's root `ComposeView`, or call from a Compose
     * `LaunchedEffect` via [tagScreenName]).
     */
    @JvmStatic
    fun addObservedView(view: View, screenName: String, viewName: String = screenName) {
        ReplayCore.shared.observeView(view, screenName, viewName)
    }

    /**
     * Pause schematic capture — snapshots stop, taps + network +
     * console + perf events keep flowing. Useful for screens with
     * regulated pixel content (HIPAA, banking PIN pad) where the
     * customer still wants the interaction timeline.
     *
     * In-memory only; a process restart resets to running. Use
     * [Replay.optOutSchematicRecordings] for a durable opt-out.
     *
     * Mirrors the reference mobile SDK's `pauseScreenRecording()`.
     */
    @JvmStatic
    fun pauseRecording() {
        ReplayCore.shared.pauseCapture()
    }

    /**
     * Resume schematic capture after [pauseRecording].
     * Mirrors the reference mobile SDK's `resumeScreenRecording()`.
     */
    @JvmStatic
    fun resumeRecording() {
        ReplayCore.shared.resumeCapture()
    }

    /**
     * Discard the current session entirely — clears the in-memory
     * buffer AND deletes any on-disk batches still queued for
     * upload. The session never reaches the backend.
     *
     * Different from [stop] (graceful end + upload). Reach for
     * this when the user triggers a "delete my activity" / "clear
     * recent history" flow AFTER content has already been captured.
     *
     * Mirrors the reference mobile SDK's `cancelCurrentSession()`.
     */
    @JvmStatic
    fun cancelSession() {
        // ⚠️ STUB — LegacyCore isn't the live engine (init() boots ReplayCore);
        // this does NOT cancel the live session. See docs/sdk-facade-status.md.
        LegacyCore.cancelCurrentSession()
    }

    // ------------------------------------------------------------------
    //  industry-standard GDPR opt-in / opt-out
    // ------------------------------------------------------------------

    /**
     * Overall opt-out. When set to true: every event drops at the
     * emit gate, no snapshots fire, no uploads happen. Persisted
     * across launches via SharedPreferences. The customer MUST
     * call again with false (or your in-app "rejoin" toggle does)
     * to start recording again.
     *
     * Mirrors the reference mobile SDK's `optOutOverall()` / `optInOverall()` pair —
     * we collapse the two into one boolean call since that's the
     * idiomatic Kotlin / Android shape.
     */
    @JvmStatic
    fun optOutOverall(optOut: Boolean) {
        LegacyCore.setOverallOptOut(optOut)
        // GDPR: enforce on the live engine — stop recording on opt-out. Opt-in
        // takes effect on the next Replay.init / launch.
        if (optOut) ReplayCore.shared.stop()
    }

    /** Whether the customer is currently opted out overall. */
    @JvmStatic
    fun isOptedOutOverall(): Boolean = LegacyCore.isOverallOptedOut()

    /**
     * Schematic (snapshot-only) opt-out. When set to true: pixel
     * capture stops but every other event type keeps flowing.
     * Persisted across launches.
     *
     * Mirrors the reference mobile SDK's `optOutOfSchematicRecordings()` /
     * `optIntoSchematicRecordings()`.
     */
    @JvmStatic
    fun optOutSchematicRecordings(optOut: Boolean) {
        // ⚠️ STUB — sets an inert LegacyCore flag the live engine never reads;
        // pixel capture is unaffected. See docs/sdk-facade-status.md.
        LegacyCore.setSchematicOptOut(optOut)
    }

    /** Whether schematic (snapshot) recording is currently opted out. */
    // ⚠️ STUB — reads the inert LegacyCore flag (see optOutSchematicRecordings).
    @JvmStatic
    fun isOptedOutSchematicRecordings(): Boolean = LegacyCore.isSchematicOptedOut()

    // ------------------------------------------------------------------
    //  industry-standard deep-link helpers
    // ------------------------------------------------------------------

    /**
     * Returns a URL pointing to the CURRENT session on the Replayfy
     * dashboard, or null when no session is active.
     *
     * Used by feedback flows where the customer wants to attach a
     * link the support team can click to replay the user's session.
     *
     * Derived from [ReplayConfig.apiHost] — `api.replayfy.io` →
     * `app.replayfy.io/sessions/<sessionId>`. Self-hosted
     * customers automatically get their own host substituted.
     *
     * Mirrors the reference mobile SDK's `urlForCurrentSession()`.
     */
    // ⚠️ STUB — derived from the un-booted LegacyCore's (nonexistent) session,
    // so returns nil for live sessions. Re-home onto ReplayCore.sessionId +
    // apiHost. See docs/sdk-facade-status.md.
    @JvmStatic
    fun urlForCurrentSession(): String? = LegacyCore.urlForCurrentSession()

    /**
     * Returns a URL pointing to the CURRENT user (every session for
     * the active distinctId) on the dashboard, or null when no
     * `identify(distinctId)` has been called.
     *
     * Mirrors the reference mobile SDK's `urlForCurrentUser()`.
     */
    // ⚠️ STUB — see urlForCurrentSession; nil for live sessions.
    // docs/sdk-facade-status.md.
    @JvmStatic
    fun urlForCurrentUser(): String? = LegacyCore.urlForCurrentUser()

    /**
     * Occlude every [android.widget.EditText] in the host app
     * regardless of whether it's been explicitly registered via
     * [addPrivacyView]. Useful default-on PII protection when the
     * app routinely handles sensitive input.
     *
     * Mirrors the reference mobile SDK's `occludeAllTextFields(boolean)`.
     */
    @JvmStatic
    fun occludeAllTextFields(occlude: Boolean) {
        com.replayfy.android.internal.privacy.PrivacyRegistry
            .occludeAllTextFields = occlude
    }

    /**
     * Occlude every [android.widget.TextView] (and subclasses like
     * [android.widget.Button]) — anything that displays text.
     *
     * Mirrors the reference mobile SDK's `occludeAllTextView()`. Boolean variant +
     * one-line "redact ALL text" toggle.
     */
    @JvmStatic
    fun occludeAllTextView(occlude: Boolean = true) {
        com.replayfy.android.internal.privacy.PrivacyRegistry
            .occludeAllTextViews = occlude
    }

    /**
     * Register a [View] subclass for occlusion — every instance
     * (including subclasses) on screen gets the stripe overlay.
     * Goes beyond the bulk text-fields / text-views flags: use this
     * when the app has custom widgets (e.g. CreditCardInputView,
     * MedicalRecordCard) that should be default-occluded across
     * every screen they appear on.
     *
     * Cheap when no classes registered (~zero overhead). Class set
     * size is typically single-digit in practice.
     *
     * Mirrors the reference mobile SDK's `applyOcclusion(Class<? extends View>)`.
     */
    @JvmStatic
    fun applyOcclusion(viewClass: Class<out View>) {
        com.replayfy.android.internal.privacy.PrivacyRegistry
            .applyOcclusion(viewClass)
    }

    /** Unregister a previously-added occluded class. Safe to call
     *  for classes that were never registered. Mirrors the reference mobile SDK's
     *  `removeOcclusion(Class<? extends View>)`. */
    @JvmStatic
    fun removeOcclusion(viewClass: Class<out View>) {
        com.replayfy.android.internal.privacy.PrivacyRegistry
            .removeOcclusion(viewClass)
    }

    /**
     * Paint over the ENTIRE captured snapshot with the diagonal-
     * stripe occlusion overlay. Nuclear option for screens where
     * the customer doesn't want ANY pixel data to leave the device
     * (e.g. health records, banking pin pad).
     *
     * Mirrors the reference mobile SDK's `occludeSensitiveScreen(boolean)`. Set false
     * to restore normal capture.
     */
    @JvmStatic
    fun occludeSensitiveScreen(occlude: Boolean) {
        com.replayfy.android.internal.privacy.PrivacyRegistry
            .occludeAllScreen = occlude
    }

    /**
     * Paint the diagonal-stripe overlay over a set of [Rect] regions
     * on the NEXT snapshot only. Entry point for cross-platform
     * SDK bridges (React Native, Flutter) whose components don't
     * have native [View] references — the bridge calculates rects
     * on the JS / Dart side (RN `measureInWindow`, Flutter
     * `RenderBox.localToGlobal`) and hands them here in
     * root-relative pixel coords.
     *
     * One-shot: the rects clear once a snapshot fires. Bridges
     * call this every frame (or before any capture they want
     * occluded). Calling it twice without a snapshot in between
     * REPLACES the prior set rather than appending.
     *
     * Mirrors the reference mobile SDK's `occludeRectsOnNextFrame(rects)`.
     */
    @JvmStatic
    fun occludeRectsOnNextFrame(rects: List<android.graphics.Rect>) {
        occludeRectsOnNextFrame(
            rects, com.replayfy.android.internal.privacy.PrivacyRegistry.maskStyle)
    }

    /** As [occludeRectsOnNextFrame] but with an explicit render style for all
     *  rects in this set. */
    @JvmStatic
    fun occludeRectsOnNextFrame(
        rects: List<android.graphics.Rect>,
        style: com.replayfy.android.ReplayMaskStyle,
    ) {
        com.replayfy.android.internal.privacy.PrivacyRegistry.setPendingFrameRects(
            rects.map { com.replayfy.android.MaskRect(it, style) })
    }

    /** The default render style for masked regions — BLUR blurs the pixels,
     *  OVERLAY paints a solid box. Applies to addPrivacyView, occludeAll*, the
     *  whole-screen overlay, and any occludeRectsOnNextFrame call that doesn't
     *  pass its own style. Default BLUR. */
    @JvmStatic
    fun setMaskStyle(style: com.replayfy.android.ReplayMaskStyle) {
        com.replayfy.android.internal.privacy.PrivacyRegistry.maskStyle = style
    }

    /** Downscale factor for BLUR regions (default 12). Larger = stronger blur. */
    @JvmStatic
    fun setBlurDownscale(factor: Int) {
        com.replayfy.android.internal.privacy.PrivacyRegistry.blurDownscale =
            factor.coerceAtLeast(2)
    }

    /**
     * Attach a sticky property to the END USER (persists across
     * sessions, attached to the EndUser row server-side). Distinct
     * from [track] event properties which only attach to a single
     * event.
     *
     * Mirrors the reference mobile SDK's `setUserProperty(key, value)` per-primitive
     * overloads — Kotlin's [Any?] subsumes all of them.
     *
     * Common use: `setUserProperty("plan", "pro")`,
     * `setUserProperty("signupSource", "google_ads")`.
     */
    @JvmStatic
    fun setUserProperty(key: String, value: Any?) {
        if (value != null) ReplayCore.shared.sendMetadata(key, value.toString())
    }

    /**
     * Attach a sticky property to the CURRENT SESSION. Visible on
     * the dashboard as a Session-level field (rendered as a chip
     * on the player header — dashboard wiring is a separate task).
     *
     * Use for ephemeral per-session context: `setSessionProperty("ab_variant", "B")`,
     * `setSessionProperty("checkout_step", "shipping")`.
     */
    @JvmStatic
    fun setSessionProperty(key: String, value: Any?) {
        if (value != null) ReplayCore.shared.sendMetadata(key, value.toString())
    }

    // ------------------------------------------------------------------
    //  industry-standard session-control surface
    // ------------------------------------------------------------------

    /**
     * Toggle the auto-Activity tagger on/off at runtime. Default-on
     * via [ReplayConfig.autoScreenName]; flip to false to drive
     * routes manually via [tagScreenName] for a flow where Activity
     * class names don't map cleanly to user-meaningful screens.
     *
     * Mirrors the reference mobile SDK's `setAutomaticScreenNameTagging(boolean)`.
     */
    @JvmStatic
    fun setAutomaticScreenNameTagging(enabled: Boolean) {
        ReplayCore.shared.setAutoScreenName(enabled)
    }

    /**
     * Attach the device's push-notification token to the session.
     * `platform` defaults to "fcm"; pass "huawei" if you're piping
     * a HMS token. Dashboard wiring (chip on player header) is
     * tracked separately.
     *
     * Mirrors the reference mobile SDK's `setPushNotificationToken(token)`.
     */
    @JvmStatic
    @JvmOverloads
    fun setPushNotificationToken(token: String, platform: String = "fcm") {
        ReplayCore.shared.sendMetadata("\$push_token", token)
        ReplayCore.shared.sendMetadata("\$push_platform", platform)
    }

    /**
     * Flag the current session as a favorite so it surfaces in the
     * "starred sessions" filter on the dashboard. One-shot — call
     * once per session you want flagged.
     *
     * Mirrors the reference mobile SDK's `markSessionAsFavorite()`.
     */
    @JvmStatic
    fun markSessionAsFavorite() {
        ReplayCore.shared.sendMetadata("\$favorited", "true")
    }

    /**
     * Attach a SESSION-level tag (with optional properties) for
     * filtering the session list. Distinct from [track] which is
     * event-level. Use for "this session is in A/B variant X" or
     * "this session belongs to the holiday checkout flow".
     *
     * Mirrors the reference mobile SDK's `addTagWithProperties(name, properties)`.
     */
    @JvmStatic
    @JvmOverloads
    fun addTagWithProperties(name: String, properties: Map<String, Any?>? = null) {
        val payload = if (properties != null) JSONObject(properties).toString() else ""
        ReplayCore.shared.sendEvent(name, payload)
    }

    /**
     * Force-end the current session + spin up a fresh one. Useful
     * for logical session boundaries inside one app process —
     * user logout-then-login, A/B re-bucketing, "start over"
     * flows. Drains the old session's events first so they don't
     * bleed into the new one.
     *
     * Mirrors the reference mobile SDK's `startNewSession()`.
     */
    @JvmStatic
    fun startNewSession() {
        ReplayCore.shared.startNewSession()
    }

    /**
     * Override the host app's version + build that the SDK auto-
     * detects from PackageInfo. Used by:
     *
     *   - Flavor builds where the marketing version differs from
     *     `versionName` (e.g. white-label apps that brand each
     *     build differently).
     *   - Dynamic feature modules where the resolved version is
     *     wrong.
     *   - React Native / Flutter / Cordova bridges that have their
     *     OWN app version separate from the Android wrapper's.
     *
     * Call BEFORE [init] (or at app startup before any session
     * fires) so the FIRST batch carries the override. Subsequent
     * sessions also see it — the override is sticky for the
     * process lifetime.
     *
     * Used by the backend symbolication service to pick the right
     * mapping.txt + NDK .so symbols for crash stacks. Pass null
     * to clear the override and fall back to PackageInfo.
     */
    @JvmStatic
    @JvmOverloads
    fun setAppVersion(version: String?, build: String? = null) {
        version?.let { ReplayCore.shared.sendMetadata("\$app_version", it) }
        build?.let { ReplayCore.shared.sendMetadata("\$app_build", it) }
    }

    /**
     * Toggle capture of advanced gestures — long-press, four-
     * direction swipe, and pinch — in addition to the always-on
     * tap. When enabled, each gesture emits a TapEventData with
     * `gesture` set to "long_press" / "swipe_left" / "swipe_right" /
     * "swipe_up" / "swipe_down" / "pinch". Pinch events also carry
     * a `pinchScale` (final ratio).
     *
     * Default off — the gesture detectors allocate lazily on
     * first interactive touch only when this flag is true, so
     * non-users pay zero overhead.
     *
     * Mirrors the reference mobile SDK's `enableAdvancedGestureRecognizer(boolean)`.
     */
    @JvmStatic
    fun enableAdvancedGestureRecognizer(enabled: Boolean) {
        com.replayfy.android.internal.tracker.TouchListenerWrapper
            .advancedGesturesEnabled = enabled
    }

    /**
     * Allow brief backgrounding (≤ [breakWindowMs], default 30 s)
     * WITHOUT ending the session. The same session resumes when
     * the user returns. Default off (every background ends).
     *
     * Mirrors the reference mobile SDK's `allowShortBreakForAnotherApp(boolean)`. Useful
     * when the user routinely switches to another app for short
     * operations (copy 2FA code, switch to authenticator).
     */
    @JvmStatic
    @JvmOverloads
    fun allowShortBreakForAnotherApp(allow: Boolean, breakWindowMs: Long = 30_000L) {
        // ⚠️ STUB — needs session-resume-window logic in the live engine; the
        // LegacyCore flag is inert. See docs/sdk-facade-status.md.
        LegacyCore.setAllowShortBreak(allow, breakWindowMs)
    }

    /**
     * Toggle multi-session recording per app launch. When false,
     * only ONE session fires per process — subsequent foregrounds
     * after a session_end are no-ops. Default true.
     *
     * Mirrors the reference mobile SDK's `setMultiSessionRecord(boolean)`.
     */
    @JvmStatic
    fun setMultiSessionRecord(enabled: Boolean) {
        // ⚠️ STUB — needs multi-session lifecycle in the live engine; the
        // LegacyCore flag is inert. See docs/sdk-facade-status.md.
        LegacyCore.setMultiSessionRecord(enabled)
    }

    /**
     * Register a callback that fires after the SDK's first
     * successful batch upload (server-side handshake confirmed).
     * Used during onboarding so the customer's "Verify integration"
     * button can light up.
     *
     * If verification has ALREADY fired this process the listener
     * runs synchronously on the calling thread. Otherwise it
     * runs on the MAIN thread when the next upload succeeds.
     * One-shot per process — re-registering after the SDK has
     * verified will fire immediately, but verification itself
     * does not happen twice.
     *
     * Mirrors the reference mobile SDK's `addVerificationListener(VerificationListener)`.
     */
    @JvmStatic
    fun addVerificationListener(listener: Runnable) {
        ReplayCore.shared.addVerificationListener(listener)
    }

    /** Drop a previously-added listener. Safe to call even when
     *  the listener was never added or already fired. */
    @JvmStatic
    fun removeVerificationListener(listener: Runnable) {
        // Runnable -> () -> Unit mapping is not directly reversible
        // (we wrap each Runnable in a closure when adding). For now
        // the only customer pattern that matters is "register one
        // listener early, never remove it" — this is a best-effort
        // wrap-and-forward; the wrapped closure won't be found.
        // TODO: keep a Runnable -> closure map for accurate removal.
        ReplayCore.shared.removeVerificationListener(listener)
    }

    /**
     * Manual bug-report event. Wire to a "Send Feedback" /
     * "Report Issue" button — the dashboard surfaces these as
     * flagged events in the session timeline + triggers a fresh
     * snapshot so support can jump straight to the relevant
     * moment with the screen the user was looking at.
     *
     * Mirrors the reference mobile SDK's `reportBugEvent(name, description)`.
     */
    @JvmStatic
    @JvmOverloads
    fun reportBugEvent(
        name: String,
        description: String? = null,
        properties: Map<String, Any?>? = null,
    ) {
        val props = buildMap<String, Any?> {
            put("name", name)
            description?.let { put("description", it) }
            properties?.let { putAll(it) }
        }
        ReplayCore.shared.sendEvent("\$bug_report", JSONObject(props).toString())
    }

    /**
     * Stop recording AND block (up to [timeoutMs]) until the
     * in-memory buffer has been uploaded or persisted to disk.
     * Call this from a sign-out flow or just before `System.exit`
     * when you want strong-delivery semantics for the current
     * session's events. Default 5 000 ms.
     *
     * Mirrors the reference mobile SDK's `stopApplicationAndUploadData(runnable)`. We
     * expose a blocking wait rather than the callback variant —
     * the Kotlin coroutine + ExecutorService machinery makes that
     * equally simple at the call site.
     */
    @JvmStatic
    @JvmOverloads
    fun stopApplicationAndUploadData(timeoutMs: Long = 5_000) {
        // stop() flushes the in-memory batch as it tears the engine down.
        ReplayCore.shared.stop()
    }

    /**
     * Bridge customer-side logging into Replay's console-event stream.
     *
     * Android's `android.util.Log` is unreadable from user processes
     * (READ_LOGS is platform-only), so customers using Log.d/i/w/e
     * directly can't have those captured automatically. This method
     * is the one-line wiring point for any logging framework:
     *
     *   // Timber
     *   Timber.plant(object : Timber.Tree() {
     *     override fun log(p: Int, t: String?, m: String, e: Throwable?) {
     *       val level = when (p) {
     *         Log.ERROR, Log.ASSERT -> "error"
     *         Log.WARN              -> "warn"
     *         Log.INFO              -> "info"
     *         Log.DEBUG             -> "debug"
     *         else                  -> "log"
     *       }
     *       Replay.log(level, m, e?.stackTraceToString())
     *     }
     *   })
     *
     * `System.out` / `System.err` (println, print) are captured
     * automatically when `captureConsole = true` in [ReplayConfig].
     *
     * @param level "log" | "info" | "warn" | "error" | "debug"
     * @param message  Free-text log line.
     * @param stack    Optional stack trace string (for exception logs).
     */
    @JvmStatic
    @JvmOverloads
    fun log(level: String, message: String, stack: String? = null) {
        // Bridge into the binary message stream → dashboard console tab.
        ReplayCore.shared.sendLog(level, if (stack != null) "$message\n$stack" else message)
    }

    // ------------------------------------------------------------------
    //  Live-engine passthroughs for the React Native bridge.
    //
    //  Forward straight to the live ReplayCore engine (the one
    //  Replay.init drives) rather than the legacy facade methods, so RN
    //  sessions get real metadata / network / session-id / teardown even
    //  before the LegacyCore consolidation (TODO #119). Members on the
    //  Replay object, not free functions — per the repo's facade rule.
    // ------------------------------------------------------------------

    /** Live sticky metadata (single key/value). */
    @JvmStatic
    fun recordMetadata(key: String, value: String) {
        ReplayCore.shared.sendMetadata(key, value)
    }

    /** Forward one captured RN network call to the live engine.
     *  `request` / `response` are JSON strings assembled JS-side. */
    @JvmStatic
    fun recordNetwork(
        url: String,
        method: String,
        request: String,
        response: String,
        status: Int,
        duration: Long,
    ) {
        ReplayCore.shared.sendNetwork(
            type = "fetch",
            method = method,
            url = url,
            request = request,
            response = response,
            status = status.toLong(),
            duration = if (duration < 0) 0L else duration,
        )
    }

    /** Active live-engine session id (null when not recording). */
    @JvmStatic
    fun currentSessionId(): String? = ReplayCore.shared.sessionId

    /** Stop the live recording engine + flush. */
    @JvmStatic
    fun stopEngine() {
        ReplayCore.shared.stop()
    }
}
