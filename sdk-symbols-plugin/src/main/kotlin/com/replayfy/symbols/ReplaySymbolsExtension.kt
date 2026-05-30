package com.replayfy.symbols

/**
 * DSL extension surface for `replaySymbols { ... }`. Plain class so
 * Gradle's Groovy DSL + Kotlin DSL both work without annotation
 * gymnastics.
 *
 * ```kotlin
 * replaySymbols {
 *     apiKey = "rk_..."
 *     apiHost = "https://api.replayfy.io"   // optional
 *     uploadOnAssemble = true                // optional, default true
 * }
 * ```
 */
open class ReplaySymbolsExtension {

    /**
     * The project's Replayfy API key (`rk_...`). Required. Wired
     * into the `x-replay-api-key` header on every symbol upload —
     * same authentication scheme the SDK uses for batch ingest.
     *
     * **Don't commit secrets** — read from a Gradle property or
     * environment variable instead:
     *
     * ```kotlin
     * replaySymbols {
     *     apiKey = providers.gradleProperty("replayfyApiKey").get()
     * }
     * ```
     */
    var apiKey: String? = null

    /**
     * API host. Defaults to the canonical Replayfy ingest URL —
     * override for self-hosted deployments.
     */
    var apiHost: String = "https://api.replayfy.io"

    /**
     * When true (default), every `assemble<Variant>` finishes by
     * running `uploadReplaySymbols<Variant>`. CI gets free symbol
     * uploads with no extra task wiring.
     *
     * Set to false if symbol uploads should be EXPLICIT only (e.g.
     * customers running release builds locally who don't want to
     * spam the bucket with dev versions). In that case, CI calls
     * `./gradlew uploadReplaySymbolsRelease` directly.
     */
    var uploadOnAssemble: Boolean = true
}
