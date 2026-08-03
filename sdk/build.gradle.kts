plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // Publishing — generates a Maven AAR + POM. Signing is added by the
    // signing plugin which only activates when the maintainer has GPG
    // keys configured (see gradle.properties templated keys).
    `maven-publish`
    signing
}

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

        // SDK version baked into the user-agent the host app sends with
        // every batch. Bump in tandem with git tags.
        buildConfigField("String", "SDK_VERSION", "\"0.0.2\"")
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

    // Publish a `release` AAR component. AGP 7.1+ enables this via
    // `singleVariant("release") { withSourcesJar(); withJavadocJar() }`
    // which is what Maven Central requires (sources jar + javadoc jar
    // are mandatory for OSSRH publication).
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// -----------------------------------------------------------------------
// Publication
// -----------------------------------------------------------------------
// Primary release path is JitPack (solo-friendly, no Sonatype account):
// push a git tag and JitPack builds the artifact on demand. Consumers add
// the JitPack repo + `com.github.replayfy:android-sdk:<tag>`.
// JitPack overrides the `version` below with the tag it builds.
//
// The OSSRH repository + signing blocks below stay wired so a future Maven
// Central move needs only credentials + a groupId flip (see io.replayfy note).
//
// Coordinates (JitPack): com.github.replayfy:android-sdk:0.0.1
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                // JitPack coordinates. `com.github.<org>:<repo>` is what the
                // JitPack maven repo resolves for github.com/replayfy/android-sdk.
                groupId    = "com.github.replayfy"
                artifactId = "android-sdk"
                version    = "0.0.2"
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
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
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
        }
        repositories {
            maven {
                name = "OSSRH"
                val releasesRepoUrl =
                    "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                val snapshotsRepoUrl =
                    "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                url = uri(
                    if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl
                    else releasesRepoUrl,
                )
                credentials {
                    username = (project.findProperty("ossrhUsername") as String?)
                        ?: System.getenv("OSSRH_USERNAME")
                                ?: ""
                    password = (project.findProperty("ossrhPassword") as String?)
                        ?: System.getenv("OSSRH_PASSWORD")
                                ?: ""
                }
            }
        }
    }

    // Sign only when keys are configured. CI without GPG can skip
    // signing (Maven Central won't accept the artifact, but local
    // builds + Sonatype snapshots still work).
    signing {
        val signingKey = (project.findProperty("signing.key") as String?)
            ?: System.getenv("SIGNING_KEY")
        val signingPassword = (project.findProperty("signing.password") as String?)
            ?: System.getenv("SIGNING_PASSWORD")
        if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications)
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

    // Instrumented tests (development only — stripped from the published
    // AAR). Drive the privacy-masking on-device verification.
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.7.0")
}
