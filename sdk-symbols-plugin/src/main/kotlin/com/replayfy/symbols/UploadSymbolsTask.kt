package com.replayfy.symbols

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Per-variant upload task. Discovers R8 `mapping.txt` + unstripped
 * `.so` files for the bound variant, base64-encodes them, and
 * POSTs to `<apiHost>/v1/replay/symbols/...`.
 *
 * Configured by ``ReplaySymbolsPlugin``; not meant to be
 * instantiated directly by customer build scripts.
 *
 * ## Task inputs / outputs
 *
 * Treated as a non-incremental task — symbol uploads are cheap and
 * idempotent on the backend side, and tracking input mtimes
 * incrementally adds complexity (`.so` debug binaries change every
 * NDK build). Customers running CI typically rebuild from clean
 * anyway.
 */
abstract class UploadSymbolsTask : DefaultTask() {

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val apiHost: Property<String>

    @get:Input
    @get:Optional
    abstract val apiKey: Property<String>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Internal
    abstract val projectDir: Property<File>

    @get:Internal
    abstract val buildDir: DirectoryProperty

    @TaskAction
    fun upload() {
        val key = apiKey.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "[replaySymbols] apiKey not configured. Set it via:\n" +
                    "  replaySymbols { apiKey = providers.gradleProperty(\"replayfyApiKey\").get() }\n" +
                    "or skip this variant by setting uploadOnAssemble = false.",
            )
        val host = apiHost.get().trimEnd('/')
        val variant = variantName.get()
        val build = buildDir.get().asFile

        // Read versionName + versionCode from the merged manifest.
        // AGP writes the merged manifest to a predictable location
        // per variant; we parse the AndroidManifest.xml for the
        // attributes we need.
        val manifest = findMergedManifest(build, variant)
        val version = parseManifestAttr(manifest, "versionName")
            ?: "unknown"
        val versionCode = parseManifestAttr(manifest, "versionCode")
            ?: "0"

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS) // .so binaries can be large
            .build()

        var uploaded = 0

        // R8 mapping.txt — present when minifyEnabled=true in the
        // build type. Absent for debug variants or release variants
        // with R8 disabled; we skip silently.
        val mapping = mappingFileFor(build, variant)
        if (mapping?.exists() == true) {
            uploadFile(
                client = client,
                host = host,
                apiKey = key,
                platform = "android",
                version = version,
                build = versionCode,
                filename = "mapping.txt",
                file = mapping,
                contentType = "text/plain",
            )
            uploaded += 1
            logger.lifecycle("[replaySymbols] uploaded mapping.txt for $variant ($version+$versionCode)")
        } else {
            logger.lifecycle("[replaySymbols] no mapping.txt for $variant — R8 disabled or not yet run")
        }

        // NDK .so debug binaries — one per ABI under
        // merged_native_libs. AGP intermediate location is stable
        // across AGP 7.0-8.x; future restructure would need a path
        // update.
        val nativeDir = nativeLibsDir(build, variant)
        if (nativeDir?.isDirectory == true) {
            nativeDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".so") }
                .forEach { soFile ->
                    val abi = soFile.parentFile.name
                    val libName = soFile.nameWithoutExtension
                    // Encode ABI into the filename per the backend's
                    // accepted shape: lib<name>.<abi>.so.
                    val packedName = "$libName.$abi.so"
                    uploadFile(
                        client = client,
                        host = host,
                        apiKey = key,
                        platform = "android",
                        version = version,
                        build = versionCode,
                        filename = packedName,
                        file = soFile,
                        contentType = "application/octet-stream",
                    )
                    uploaded += 1
                    logger.lifecycle("[replaySymbols] uploaded $packedName for $variant ($version+$versionCode)")
                }
        } else {
            logger.lifecycle("[replaySymbols] no native libs for $variant")
        }

        if (uploaded == 0) {
            logger.warn("[replaySymbols] no symbols found for variant '$variant' — nothing uploaded")
        }
    }

    private fun mappingFileFor(buildDir: File, variant: String): File? {
        // AGP 7.0+ writes mapping.txt to outputs/mapping/<variant>/.
        return File(buildDir, "outputs/mapping/$variant/mapping.txt")
            .takeIf { it.exists() }
    }

    private fun nativeLibsDir(buildDir: File, variant: String): File? {
        // Intermediate layout AGP 7.4+:
        //   build/intermediates/merged_native_libs/<variant>/out/lib/<abi>/lib*.so
        // We accept both this AND the older `out` -> direct layout
        // by walking from the variant root.
        val mergedRoot = File(buildDir, "intermediates/merged_native_libs/$variant")
        if (!mergedRoot.exists()) return null
        // Find the deepest `lib/` directory under mergedRoot — AGP
        // changes the intermediate suffix between versions; this is
        // resilient.
        return mergedRoot.walkTopDown()
            .filter { it.isDirectory && it.name == "lib" }
            .firstOrNull()
    }

    private fun findMergedManifest(buildDir: File, variant: String): File? {
        // AGP 7.4+ merged manifest path. Falls back to scanning
        // intermediates/merged_manifests recursively if the
        // expected path doesn't match (older AGP versions).
        val direct = File(
            buildDir,
            "intermediates/merged_manifests/$variant/AndroidManifest.xml",
        )
        if (direct.exists()) return direct
        val fallback = File(buildDir, "intermediates/merged_manifests/$variant")
        return fallback.walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .firstOrNull()
    }

    private fun parseManifestAttr(manifest: File?, attr: String): String? {
        if (manifest == null || !manifest.exists()) return null
        // Manifest attributes we care about appear in the root
        // <manifest> element. Quick regex parse keeps the plugin
        // dependency-free (no need to pull in an XML parser).
        return try {
            val text = manifest.readText()
            // Matches: android:versionName="2.4.1" — both quoted forms.
            val pattern = Regex(
                """android:$attr\s*=\s*"([^"]+)"""",
                RegexOption.IGNORE_CASE,
            )
            pattern.find(text)?.groupValues?.get(1)
        } catch (t: Throwable) {
            logger.warn("[replaySymbols] failed to parse $attr from manifest: ${t.message}")
            null
        }
    }

    private fun uploadFile(
        client: OkHttpClient,
        host: String,
        apiKey: String,
        platform: String,
        version: String,
        build: String,
        filename: String,
        file: File,
        contentType: String,
    ) {
        val bytes = file.readBytes()
        // Encode as data URL to match the backend's existing
        // /assets endpoint pattern — keeps the controller body
        // shape consistent across symbol + asset + thumbnail.
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val dataUrl = "data:$contentType;base64,$base64"
        val jsonBody = "{\"dataUrl\":\"$dataUrl\"}"
        val req = Request.Builder()
            .url("$host/v1/replay/symbols/$platform/$version/$build/$filename")
            .header("x-replay-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = try { resp.body?.string() } catch (_: Throwable) { null }
                throw GradleException(
                    "[replaySymbols] upload of $filename failed: " +
                        "HTTP ${resp.code} ${resp.message}${body?.let { " — $it" } ?: ""}",
                )
            }
        }
    }
}
