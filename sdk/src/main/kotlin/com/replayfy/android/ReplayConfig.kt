package com.replayfy.android

/**
 * Configuration passed to [Replay.init]. Everything except [apiKey] +
 * [apiHost] is optional with sensible defaults.
 *
 * Defaults intentionally favour shipping useful data: console + errors
 * are on, network is off (PII risk), schematic recording is on. Customer
 * can flip via the dashboard's /v1/sdk/config endpoint mid-session
 * without re-init.
 */
data class ReplayConfig(
    /** Project API key from the dashboard. Required. */
    val apiKey: String,

    /** Base URL of the ingest API — e.g. https://ingest.replayfy.io */
    val apiHost: String,

    /** Optional project id — only used when one key covers multiple projects. */
    val projectId: String? = null,

    /**
     * Distinct user id, if known at init time. Anonymous sessions
     * (no distinctId) get a generated install-stable id from
     * [android.provider.Settings.Secure.ANDROID_ID].
     */
    val distinctId: String? = null,

    /** ms between auto-flushes of the in-memory batch. */
    val flushIntervalMs: Long = 5_000L,

    /** Cap on events buffered in-memory before a flush is forced. */
    val maxBufferSize: Int = 500,

    /** When false, presence/live-mode WebSocket is skipped. */
    val liveMode: Boolean = true,

    /** Capture LogCat / `Log.x` output at runtime. */
    val captureConsole: Boolean = true,

    /** Capture OkHttp + HttpURLConnection requests via interceptor. */
    val captureNetwork: Boolean = false,

    /** Capture uncaught exceptions via Thread.UncaughtExceptionHandler. */
    val captureErrors: Boolean = true,

    /** Include request/response headers in network capture. PII risk. */
    val captureHeaders: Boolean = false,

    /** Max captured body size per network event. Bodies above are truncated. */
    val maxBodyBytes: Int = 4_096,

    /**
     * Capture pixel bytes (full-screen bitmap) alongside the view
     * tree on each snapshot. Off → tree-only ("wireframe") playback,
     * still useful but visually plain. On → pixel-accurate playback
     * via the asset endpoint.
     *
     * Default on, but the dashboard's remote config can flip it off
     * for plans without snapshot pixels, or workspaces that want
     * lower bandwidth.
     */
    val captureSnapshotPixels: Boolean = true,

    /**
     * Cadence of the continuous snapshot loop, in milliseconds.
     *
     * Matches UXCam's default of ~2 FPS (500 ms). Lower values mean
     * smoother playback at the cost of CPU + upload bandwidth.
     * Floor is clamped to 200 ms inside [com.replayfy.android.internal.capture.SnapshotCapture]
     * to keep the main thread responsive.
     */
    val snapshotIntervalMs: Long = 500L,

    /**
     * Auto-detect the current screen from each Activity resume
     * (route becomes `activity.javaClass.simpleName`). When false,
     * the SDK only sets a route when the customer calls
     * [com.replayfy.android.Replay.tagScreenName] explicitly.
     *
     * Mirrors iOS's same-named flag (default-on). Apps that name
     * activities cryptically (`MainActivity`, `Fragment2`) often
     * disable this and tag manually for clearer dashboard rows.
     */
    val autoScreenName: Boolean = true,

    /**
     * Pull configuration from the dashboard's `/v1/sdk/config`
     * endpoint and let it OVERRIDE the values supplied here. When
     * true (default), every customer-passed field that maps to a
     * dashboard setting becomes a fallback used only during the
     * cold-launch first-fetch race. After the first successful
     * fetch the dashboard always wins — including subsequent
     * flips while the session is running (15 min refresh cycle).
     *
     * Set to false ONLY when the customer intentionally wants
     * hard-coded behaviour (kiosk apps, smoke tests, white-label
     * builds whose dashboard isn't theirs).
     */
    val useRemoteConfig: Boolean = true,
)
