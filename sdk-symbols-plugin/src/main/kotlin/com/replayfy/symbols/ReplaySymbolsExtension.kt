package com.replayfy.symbols

/**
 * DSL extension surface for `replaySymbols { ... }`. Plain class so
 * Gradle's Groovy DSL + Kotlin DSL both work without annotation
 * gymnastics.
 *
 * ```kotlin
 * replaySymbols {
 *     apiKey = "rpl_sk_..."   // a SERVER key, from your CI secrets
 *     apiHost = "https://us.replayfy.app"   // optional; your own URL if self-hosted
 *     uploadOnAssemble = true                // optional, default true
 * }
 * ```
 */
open class ReplaySymbolsExtension {

    /**
     * A Replayfy SERVER key (`rpl_sk_...`). Required. Sent in the
     * `x-replay-api-key` header on every symbol upload. The publishable
     * key (`rpl_pk_...`) that the SDK uses is rejected with a 403: it
     * ships inside your app, so it must not be able to overwrite your
     * symbols. Create a server key in Settings > Install > Generate key.
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
    var apiHost: String = "https://us.replayfy.app"

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
