import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // Publishing to Maven Central via the Central Portal, with signing + POM +
    // auto-release handled by one plugin (replaces the manual maven-publish +
    // signing + OSSRH-staging wiring, and removes the "Close & Release" step).
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// One version for the published coordinate AND BuildConfig.SDK_VERSION (sent as
// the session's trackerVersion), so the two can't drift. CI's publish passes
// -PpublishVersion=<tag>. The default (local and JitPack builds, which pass
// nothing) is the release this tree is heading for, so its sessions aren't
// reported as the release before it: bump it right after cutting a release.
val sdkVersion = (project.findProperty("publishVersion") as String?) ?: "0.0.5"

android {
    namespace = "com.replayfy.android"
    // minSdk 21 matches the reference mobile SDK's floor and covers >99% of active devices.
    // Drops Android 4.x — no real-world replay value there.
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        // targetSdk only affects the dev androidTest APK (a library AAR has no
        // effective targetSdk — the host app's wins). Without it the test APK
        // inherits minSdk 21, and on Android 6+ a <23-target app triggers the
        // legacy runtime-permission ReviewPermissionsActivity, which launches
        // over the test activity so it never RESUMEs and ActivityScenario hangs.
        targetSdk = 35

        // Instrumented-test runner (development only — stripped from the
        // published AAR). Used by the privacy-masking on-device test.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // SDK version reported on every session (/start trackerVersion).
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
        buildConfigField("String", "SDK_NAME", "\"@replay/android-sdk\"")

        consumerProguardFiles("consumer-rules.pro")

        // ABIs we ship libreplay_crash.so for — 64-bit ARM + x86_64
        // for emulators. Cover ~99% of active devices; 32-bit ARM
        // intentionally omitted (Google Play has deprecated 32-bit-
        // only uploads since 2019). Host apps that need armeabi-v7a
        // can re-introduce it via their own ndk { abiFilters }.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Wire the native signal handler. The .so is loaded lazily
        // from Kotlin (NativeCrashHandler) — gracefully degrades
        // when stripped.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c11"
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // CMake project for libreplay_crash.so — sigaction handlers for
    // SIGSEGV / SIGBUS / SIGABRT / SIGFPE / SIGILL / SIGTRAP.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Treat warnings as warnings — we publish, so consumers get
        // legible diagnostics rather than a wall of red.
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    // Strip out the test/androidTest source sets in the published AAR;
    // they exist for development only.
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }

    // The release variant + mandatory sources/javadoc jars are configured by
    // the vanniktech plugin below (AndroidSingleVariantLibrary), so no manual
    // android.publishing.singleVariant block is needed here.
}

// -----------------------------------------------------------------------
// Publication (vanniktech maven-publish)
// -----------------------------------------------------------------------
// TWO distribution channels, ONE publication:
//   • Maven Central (PRIMARY): `./gradlew publishAndReleaseToMavenCentral` (the
//     CI workflow) uploads to the Central Portal and auto-releases — no manual
//     "Close & Release". Central is mirrored + cached worldwide, so consumers
//     don't hit the "No route to host" / jitpack-unreachable failures corporate
//     & CI networks throw at JitPack. Coordinate: app.replayfy:android-sdk:<ver>
//     (CI passes -PpublishGroupId=app.replayfy -PpublishVersion=<tag>).
//   • JitPack (FALLBACK): push a git tag; JitPack runs `:sdk:publishToMavenLocal`
//     (which the vanniktech plugin still wires) and serves
//     com.github.replayfy:android-sdk:<tag>. Used when no -P… are passed.
// Credentials/signing come from env (see PUBLISHING.md):
//   ORG_GRADLE_PROJECT_mavenCentralUsername / …Password  (Central Portal token)
//   ORG_GRADLE_PROJECT_signingInMemoryKey / …KeyPassword (ASCII-armored GPG key)
mavenPublishing {
    // Central Portal + auto-release; sources + javadoc jars are mandatory for
    // Central and are produced by AndroidSingleVariantLibrary below.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        ),
    )
    // Overridable coordinate — same build serves Central (app.replayfy via -P…)
    // and JitPack (com.github.replayfy default; JitPack overrides the version).
    coordinates(
        (project.findProperty("publishGroupId") as String?) ?: "com.github.replayfy",
        "android-sdk",
        sdkVersion,
    )
    pom {
        name.set("Replayfy Android SDK")
        description.set(
            "Replayfy — session replay, product analytics, and error " +
                "monitoring for Android. Captures session replays, taps and " +
                "gestures, screen navigation, network requests, crashes, " +
                "performance vitals (cold start, frame drops, ANR, memory, " +
                "thermal), and console output.",
        )
        url.set("https://replayfy.app")
        licenses {
            license {
                name.set("BSD-3-Clause")
                url.set("https://opensource.org/licenses/BSD-3-Clause")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("iamnasirudeen")
                name.set("Nasirudeen Olohundare")
                email.set("iamnasirudeen@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/replayfy/android-sdk.git")
            developerConnection.set("scm:git:ssh://github.com/replayfy/android-sdk.git")
            url.set("https://github.com/replayfy/android-sdk")
        }
    }
}

dependencies {
    // Activity / app lifecycle wiring. ProcessLifecycleOwner is the
    // canonical hook for whole-app foreground/background detection.
    implementation(libs.androidx.lifecycle.process)

    // OkHttp — both for our outbound /v1/replay/batch uploads AND for
    // intercepting the host app's network calls (later, when we wire
    // the OkHttp Interceptor for network logging).
    implementation(libs.okhttp)

    // Coroutines — every async path in the SDK uses these.
    // Dispatchers.Main for UI work, Default for CPU (image encoding),
    // IO for upload + persistent queue.
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager — backs the persistent upload queue that survives
    // app death. Not used in the foundation commit but the dep is
    // declared so AndroidManifest merging doesn't fail when we add
    // the worker.
    implementation(libs.androidx.work.runtime)

    // @Nullable / @NonNull annotations for the public Java-facing API.
    compileOnly(libs.androidx.annotation)
    // Auto screen-tracking for Fragment / Navigation / single-Activity apps.
    // compileOnly: apps that use Fragments already ship this; apps that don't
    // never load the class (the tracker guards every use), so we don't force it.
    compileOnly("androidx.fragment:fragment:1.6.2")

    // JVM unit tests (development only — never in the published AAR). The
    // engine's upload drains run against a fake transport; Robolectric gives
    // the lifecycle tests a real Application, Activities and main Looper.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.5.0")
    // compileOnly above; the Fragment screen-tracking tests need it at runtime.
    testImplementation("androidx.fragment:fragment:1.6.2")

    // Instrumented tests (development only — stripped from the published
    // AAR). Drive the privacy-masking on-device verification.
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
