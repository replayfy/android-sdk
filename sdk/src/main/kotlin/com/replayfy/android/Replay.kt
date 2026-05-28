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
}
