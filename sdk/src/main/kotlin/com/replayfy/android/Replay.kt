package com.replayfy.android

import android.content.Context
import android.view.View
import com.replayfy.android.internal.ReplayCore

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
        ReplayCore.init(context.applicationContext, config)
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
        ReplayCore.identify(distinctId, properties)
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
        ReplayCore.track(name, properties)
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
        ReplayCore.stop()
    }

    /**
     * Whether the SDK is currently recording. Returns false if init
     * hasn't been called, the user is opted out, or a sampling
     * decision dropped the current session.
     */
    @JvmStatic
    fun isRecording(): Boolean = ReplayCore.isRecording()

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
        ReplayCore.setRoute(name)
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

    /** Pause schematic capture; events keep flowing. */
    @JvmStatic
    fun pauseRecording() {
        ReplayCore.stub("pauseRecording")
    }

    /** Resume schematic capture after pauseRecording. */
    @JvmStatic
    fun resumeRecording() {
        ReplayCore.stub("resumeRecording")
    }

    /** Discard the current session entirely — no upload. */
    @JvmStatic
    fun cancelSession() {
        ReplayCore.stub("cancelSession")
    }

    /**
     * Occlude every [android.widget.EditText] in the host app
     * regardless of whether it's been explicitly registered via
     * [addPrivacyView]. Useful default-on PII protection when the
     * app routinely handles sensitive input.
     *
     * Mirrors UXCam's `occludeAllTextFields(boolean)`.
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
     * Mirrors UXCam's `occludeAllTextView()`. Boolean variant +
     * one-line "redact ALL text" toggle.
     */
    @JvmStatic
    fun occludeAllTextView(occlude: Boolean = true) {
        com.replayfy.android.internal.privacy.PrivacyRegistry
            .occludeAllTextViews = occlude
    }

    /**
     * Paint over the ENTIRE captured snapshot with the diagonal-
     * stripe occlusion overlay. Nuclear option for screens where
     * the customer doesn't want ANY pixel data to leave the device
     * (e.g. health records, banking pin pad).
     *
     * Mirrors UXCam's `occludeSensitiveScreen(boolean)`. Set false
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
     * Mirrors UXCam's `occludeRectsOnNextFrame(rects)`.
     */
    @JvmStatic
    fun occludeRectsOnNextFrame(rects: List<android.graphics.Rect>) {
        com.replayfy.android.internal.privacy.PrivacyRegistry
            .setPendingFrameRects(rects)
    }

    /**
     * Attach a sticky property to the END USER (persists across
     * sessions, attached to the EndUser row server-side). Distinct
     * from [track] event properties which only attach to a single
     * event.
     *
     * Mirrors UXCam's `setUserProperty(key, value)` per-primitive
     * overloads — Kotlin's [Any?] subsumes all of them.
     *
     * Common use: `setUserProperty("plan", "pro")`,
     * `setUserProperty("signupSource", "google_ads")`.
     */
    @JvmStatic
    fun setUserProperty(key: String, value: Any?) {
        ReplayCore.setUserProperty(key, value)
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
        ReplayCore.setSessionProperty(key, value)
    }

    // ------------------------------------------------------------------
    //  UXCam-parity session-control surface
    // ------------------------------------------------------------------

    /**
     * Toggle the auto-Activity tagger on/off at runtime. Default-on
     * via [ReplayConfig.autoScreenName]; flip to false to drive
     * routes manually via [tagScreenName] for a flow where Activity
     * class names don't map cleanly to user-meaningful screens.
     *
     * Mirrors UXCam's `setAutomaticScreenNameTagging(boolean)`.
     */
    @JvmStatic
    fun setAutomaticScreenNameTagging(enabled: Boolean) {
        ReplayCore.setAutomaticScreenNameTagging(enabled)
    }

    /**
     * Attach the device's push-notification token to the session.
     * `platform` defaults to "fcm"; pass "huawei" if you're piping
     * a HMS token. Dashboard wiring (chip on player header) is
     * tracked separately.
     *
     * Mirrors UXCam's `setPushNotificationToken(token)`.
     */
    @JvmStatic
    @JvmOverloads
    fun setPushNotificationToken(token: String, platform: String = "fcm") {
        ReplayCore.setPushNotificationToken(token, platform)
    }

    /**
     * Flag the current session as a favorite so it surfaces in the
     * "starred sessions" filter on the dashboard. One-shot — call
     * once per session you want flagged.
     *
     * Mirrors UXCam's `markSessionAsFavorite()`.
     */
    @JvmStatic
    fun markSessionAsFavorite() {
        ReplayCore.markSessionAsFavorite()
    }

    /**
     * Attach a SESSION-level tag (with optional properties) for
     * filtering the session list. Distinct from [track] which is
     * event-level. Use for "this session is in A/B variant X" or
     * "this session belongs to the holiday checkout flow".
     *
     * Mirrors UXCam's `addTagWithProperties(name, properties)`.
     */
    @JvmStatic
    @JvmOverloads
    fun addTagWithProperties(name: String, properties: Map<String, Any?>? = null) {
        ReplayCore.addTagWithProperties(name, properties)
    }

    /**
     * Force-end the current session + spin up a fresh one. Useful
     * for logical session boundaries inside one app process —
     * user logout-then-login, A/B re-bucketing, "start over"
     * flows. Drains the old session's events first so they don't
     * bleed into the new one.
     *
     * Mirrors UXCam's `startNewSession()`.
     */
    @JvmStatic
    fun startNewSession() {
        ReplayCore.forceStartNewSession()
    }

    /**
     * Stop recording AND block (up to [timeoutMs]) until the
     * in-memory buffer has been uploaded or persisted to disk.
     * Call this from a sign-out flow or just before `System.exit`
     * when you want strong-delivery semantics for the current
     * session's events. Default 5 000 ms.
     *
     * Mirrors UXCam's `stopApplicationAndUploadData(runnable)`. We
     * expose a blocking wait rather than the callback variant —
     * the Kotlin coroutine + ExecutorService machinery makes that
     * equally simple at the call site.
     */
    @JvmStatic
    @JvmOverloads
    fun stopApplicationAndUploadData(timeoutMs: Long = 5_000) {
        ReplayCore.stopAndUploadSync(timeoutMs)
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
        ReplayCore.logExplicit(level = level, message = message, stack = stack)
    }
}
