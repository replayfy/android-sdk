package com.replayfy.android.internal

/**
 * Kotlin mirrors of the backend's `@replay/replay-schema` types. We
 * keep these hand-written (rather than codegen'd from a shared spec)
 * because the SDK surface is small and codegen would add a build-step
 * tax on every consumer. When the schema package changes, this file
 * gets updated in the same PR.
 *
 * Field names + JSON keys are camelCase to match the backend, which
 * deserializes via Nest + class-validator (relaxed) and our manual
 * persistence-service event handling.
 */

internal const val PLATFORM_ANDROID = "android"

/** Top-level batch envelope POST'd to /v1/replay/batch. */
internal data class ReplayBatchEnvelope(
    val sessionId: String,
    val segmentId: String,
    val sequence: Int,
    val sentAt: Long,
    val sdk: ReplaySdkDescriptor,
    val page: ReplayPageContext,
    val events: List<ReplayEvent>,
    val projectId: String? = null,
)

internal data class ReplaySdkDescriptor(
    val name: String,
    val version: String,
    val platform: String,
)

/**
 * "Page" is a misnomer on native — we reuse the field name for parity
 * with the web envelope so the backend doesn't have to fork its
 * deserializer. `url` becomes a `replay://app/{ScreenName}` deep-link
 * style ref on native; `userAgent` is built from device + SDK info.
 */
internal data class ReplayPageContext(
    val url: String,
    val userAgent: String,
    val viewport: ViewportDimensions,
    val title: String? = null,
    val referrer: String? = null,
    val timezone: String? = null,
    val language: String? = null,
)

internal data class ViewportDimensions(
    val width: Int,
    val height: Int,
)

/**
 * Single event in the timeline. `data` is a polymorphic blob —
 * serialized differently per [type]. We use [Any] here (rather than a
 * sealed class) so the JSON shape stays flat / matches the web SDK
 * verbatim.
 */
internal data class ReplayEvent(
    val id: String,
    val ts: Long,
    val offsetMs: Long,
    val type: String,
    val source: String,
    val data: Any,
)

// --- Event data shapes -------------------------------------------------

internal data class SessionStartEventData(
    val href: String,
    val path: String,
    val referrer: String,
)

internal data class SessionEndEventData(
    val reason: String, // "manual" | "background" | "process_kill"
)

/** Custom event (from Replay.track()). */
internal data class CustomEventData(
    val kind: String, // "track"
    val name: String,
    val properties: Map<String, Any?>? = null,
)

/**
 * Bounds in screen-relative pixels. Player positions tap markers /
 * snapshot nodes against these directly.
 */
internal data class TapBounds(
    val x: Int, val y: Int, val w: Int, val h: Int,
)

/** Tap location in screen-relative pixels. */
internal data class TapPoint(
    val x: Int, val y: Int,
)

/**
 * One captured tap. Shape mirrors the `tap` event in
 * @replay/replay-schema and the schema docs in
 * replay-web-sdk/docs/native-snapshot-format.md.
 *
 * `uiType` is the wire-format string ("button", "field", "text",
 * "image", "compound", "container", "unknown") — kept as a String
 * here rather than an enum so JSON serialization is trivial.
 */
internal data class TapEventData(
    val bounds: TapBounds,
    val point: TapPoint,
    val route: String,
    val uiClass: String,
    val uiType: String,
    val uiValue: String,
    val uiId: String,
    val isSensitive: Boolean,
)

/**
 * Bounds for a view tree node. Same shape as [TapBounds] — kept
 * separate for clarity (different semantic: tap bounds are
 * screen-relative, node bounds are parent-relative).
 */
internal data class NativeNodeBounds(
    val x: Int, val y: Int, val w: Int, val h: Int,
)

/**
 * One node in a captured view tree. Mirrors NativeViewNode in
 * replay-web-sdk/docs/native-snapshot-format.md.
 *
 * Lean by design — every extra field is multiplied by tree size
 * (10k views per screen is plausible on dense lists). Null/zero
 * fields are omitted from the serialized JSON via the data-class
 * encoder in BatchSender.
 */
internal data class NativeViewNode(
    /** Sibling-index path id, e.g. "0/2/0". Player resolves taps
     *  to nodes by walking this path. */
    val id: String,
    /** Wire-format type string from WidgetClassifier.UiType.wireName. */
    val type: String,
    /** Native class simple name (UIButton, ElevatedButton,
     *  MaterialButton...). Player ignores; debugging only. */
    val className: String? = null,
    val bounds: NativeNodeBounds,
    val text: String? = null,
    /** Content-addressed asset hash. Wired in a follow-up commit when
     *  bitmap capture lands. Null today. */
    val imageRef: String? = null,
    /** RGBA hex if solid color, e.g. "#ff336699". */
    val backgroundColor: String? = null,
    /** 0..1 alpha. Omitted when 1.0. */
    val opacity: Double? = null,
    /** True if this node or any ancestor was marked occluded. */
    val occluded: Boolean? = null,
    /** Accessibility content description. */
    val ariaLabel: String? = null,
    /** Children in back-to-front render order. */
    val children: List<NativeViewNode>? = null,
)

/**
 * The snapshot event payload. Triggered on screen_appeared / idle /
 * tap / manual.
 *
 * @param trigger why the snapshot fired — informs the player whether
 *                to render it eagerly (screen change) or lazily
 *                (background idle capture).
 */
internal data class NativeSnapshotEventData(
    val recorder: String, // "native"
    val width: Int,
    val height: Int,
    val pixelRatio: Double,
    val trigger: String, // "screen_appeared" | "idle" | "tap" | "manual"
    val root: NativeViewNode,
)

/**
 * Network request captured by the OkHttp interceptor. Shape matches
 * the web SDK + iOS SDK so the dashboard's Network panel renders
 * without forking per-platform.
 */
internal data class NetworkEventData(
    val requestId: String,
    val transport: String, // "okhttp"
    val method: String,
    val url: String,
    val statusCode: Int?,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMs: Long?,
    val ok: Boolean?,
    val requestHeaders: Map<String, String>? = null,
    val responseHeaders: Map<String, String>? = null,
    val requestBody: String? = null,
    val responseBody: String? = null,
    val error: String? = null,
)

/**
 * Console log event — same shape as the web SDK's ConsoleEventData
 * so the dashboard Console panel renders both platforms uniformly.
 *
 * `args` is a stringified list for native (vs the web SDK's typed
 * unknown[]) because Kotlin can't safely round-trip arbitrary
 * platform types through JSON. The dashboard treats both the same
 * way — string concat into the message column.
 */
internal data class ConsoleEventData(
    val level: String, // "log" | "info" | "warn" | "error" | "debug"
    val message: String,
    val args: List<String> = emptyList(),
    val stack: String? = null,
)

/**
 * Crash event — emitted on next launch after a previous-process
 * crash. Ships as the standard `error` event type so the backend's
 * existing errorCount counter increments correctly; the `kind`
 * field discriminates from regular runtime errors ("error",
 * "unhandledrejection") and lets the dashboard filter to crashes
 * specifically.
 */
internal data class CrashEventData(
    val kind: String,     // "crash"
    val message: String,  // throwable class + message
    val stack: String?,   // throwable.stackTraceToString() — Android's JVM
                          // stack traces are already readable; no
                          // symbolication step needed
)

/**
 * Performance event — same `kind: "perf"` envelope the web SDK
 * emits. Backend's countEvents switch dispatches on `metric`.
 *
 * Metric names defined in
 * replay-web-sdk/docs/mobile-vitals-matrix.md. Backend has columns
 * for: cold_start_ms, frame_drop_pct (×100 int), frozen_frame_count,
 * memory_rss_mb, thermal_state, etc.
 */
internal data class PerformanceEventData(
    val kind: String, // "perf"
    val metric: String,
    val value: Double,
    val unit: String, // "ms" | "mb" | "pct" | "count" | ""
    val rating: String?, // "good" | "needs-improvement" | "poor"
)

/**
 * Identify payload — shipped out-of-band on the request body alongside
 * the envelope, not as a timeline event. Matches the web SDK's
 * BatchSender.setIdentify pattern.
 */
internal data class IdentifyPayload(
    val distinctId: String,
    val email: String? = null,
    val name: String? = null,
    val plan: String? = null,
    val customProps: Map<String, Any?>? = null,
)

/** Full request body to /v1/replay/batch. */
internal data class IngestRequest(
    val envelope: ReplayBatchEnvelope,
    val identify: IdentifyPayload? = null,
    val fingerprint: String? = null,
)
