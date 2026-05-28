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
