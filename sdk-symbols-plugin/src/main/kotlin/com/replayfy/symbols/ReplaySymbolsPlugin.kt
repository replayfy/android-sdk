package com.replayfy.symbols

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin that uploads R8 `mapping.txt` + NDK `.so` debug
 * symbols to the Replayfy backend on every `assembleRelease`. The
 * uploaded symbols let the dashboard deobfuscate crash + ANR stacks
 * shipped by the SDK (which only see post-R8 + post-strip frames in
 * release builds).
 *
 * ## Usage
 *
 * ```kotlin
 * plugins {
 *     id("com.android.application")
 *     id("com.replayfy.symbols") version "0.0.1"
 * }
 *
 * replaySymbols {
 *     apiKey = "rk_..."
 *     // apiHost defaults to https://api.replayfy.io — override
 *     // for self-hosted deployments.
 *     // uploadOnAssemble defaults to true; set false to keep the
 *     // plugin available but only run on explicit
 *     // ./gradlew uploadReplaySymbolsRelease invocations.
 * }
 * ```
 *
 * ## What it does
 *
 *   1. Registers `uploadReplaySymbols<Variant>` tasks for every
 *      Android variant (`uploadReplaySymbolsRelease` is the
 *      common one).
 *   2. When `uploadOnAssemble = true` (default), wires those
 *      tasks to run AFTER `assemble<Variant>` so customer CI
 *      pipelines get free symbol uploads with no extra task.
 *   3. The task discovers `mapping.txt` (at
 *      `app/build/outputs/mapping/<variant>/mapping.txt`) +
 *      unstripped `.so` files (at
 *      `app/build/intermediates/merged_native_libs/<variant>/out/lib/<abi>/lib*.so`),
 *      reads them, base64-encodes, POSTs to
 *      `<apiHost>/v1/replay/symbols/android/<versionName>/<versionCode>/<filename>`
 *      with the configured API key in `x-replay-api-key`.
 *
 * ## Design notes
 *
 *   - **Per-variant tasks** — the plugin doesn't assume one variant.
 *     Customers with flavor dimensions (free/paid × debug/release)
 *     get one task per variant; CI can wire whichever it cares
 *     about.
 *   - **App version source** — reads `versionName` + `versionCode`
 *     from the variant's merged Android manifest at upload time so
 *     hotfix builds with overridden versions upload to the right
 *     bucket key.
 *   - **Idempotent uploads** — re-running the task overwrites the
 *     same R2 keys. Safe for repeated CI invocations.
 *   - **Failure handling** — upload errors are logged + the task
 *     fails, but `assemble<Variant>` itself isn't broken (the
 *     finalizedBy wiring uses `mustRunAfter`-style ordering). A
 *     stuck upload won't block a release build.
 */
class ReplaySymbolsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Register the DSL extension immediately so customers can
        // configure it from the build script.
        val ext = project.extensions.create(
            "replaySymbols",
            ReplaySymbolsExtension::class.java,
        )

        // We must wait until AGP has been applied to access the
        // androidComponents extension. The afterEvaluate pattern
        // here is intentional — Gradle's plugin-ordering doc says
        // configuring on-AGP-presence inside the
        // `androidComponents` callback is fragile; afterEvaluate
        // is the boring + reliable hook.
        project.afterEvaluate { proj ->
            val components = proj.extensions.findByType(
                AndroidComponentsExtension::class.java,
            )
            if (components == null) {
                proj.logger.warn(
                    "[replaySymbols] Android Gradle Plugin not applied — " +
                        "skipping. Apply com.android.application or " +
                        "com.android.library before com.replayfy.symbols.",
                )
                return@afterEvaluate
            }

            components.onVariants { variant ->
                // One task per variant. Named uploadReplaySymbols<Variant>
                // (e.g. uploadReplaySymbolsRelease) so customer CI can
                // call exactly the one they care about.
                val taskName = "uploadReplaySymbols${variant.name.replaceFirstChar { it.uppercase() }}"
                val task = proj.tasks.register(
                    taskName,
                    UploadSymbolsTask::class.java,
                ) { t ->
                    t.group = "replay"
                    t.description =
                        "Upload R8 mapping.txt + NDK .so debug symbols for the " +
                            "'${variant.name}' variant to Replayfy."
                    t.apiKey.set(ext.apiKey)
                    t.apiHost.set(ext.apiHost)
                    t.variantName.set(variant.name)
                    t.applicationId.set(variant.applicationId)
                    // Project layout — every path the task derives
                    // (mapping.txt, merged_native_libs/...) is
                    // relative to projectDir, so we hand it down
                    // explicitly. Avoids hard-coding "app/build/...".
                    t.projectDir.set(proj.projectDir)
                    t.buildDir.set(proj.layout.buildDirectory)
                }

                // Wire as a finalizer on assemble<Variant> when
                // uploadOnAssemble is true. mustRunAfter ordering
                // means a failed upload won't fail the assemble
                // task itself — customers running release builds
                // locally don't want their build broken by a
                // transient network error.
                if (ext.uploadOnAssemble) {
                    proj.tasks.matching {
                        it.name == "assemble${variant.name.replaceFirstChar { c -> c.uppercase() }}"
                    }.configureEach { assembleTask ->
                        assembleTask.finalizedBy(task)
                    }
                }
            }
        }
    }
}
