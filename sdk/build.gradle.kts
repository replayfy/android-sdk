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
    // minSdk 21 matches UXCam's floor and covers >99% of active devices.
    // Drops Android 4.x — no real-world replay value there.
    compileSdk = 35

    defaultConfig {
        minSdk = 21

        // SDK version baked into the user-agent the host app sends with
        // every batch. Bump in tandem with git tags.
        buildConfigField("String", "SDK_VERSION", "\"0.0.1\"")
        buildConfigField("String", "SDK_NAME", "\"@replay/android-sdk\"")

        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
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
// Maven Central publication
// -----------------------------------------------------------------------
// To publish a release:
//   1. Set OSSRH credentials in ~/.gradle/gradle.properties:
//        ossrhUsername=<sonatype username>
//        ossrhPassword=<sonatype token>
//        signing.keyId=<8-char hex>
//        signing.password=<gpg passphrase>
//        signing.secretKeyRingFile=/Users/you/.gnupg/secring.gpg
//   2. ./gradlew :sdk:publishReleasePublicationToOSSRHRepository
//   3. Log into https://s01.oss.sonatype.org → close + release the
//      staging repo. (CI can automate this via the nexus-publish
//      plugin in a follow-up.)
//
// Coordinates: com.replayfy:android-sdk:0.0.1
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId    = "com.replayfy"
                artifactId = "android-sdk"
                version    = "0.0.1"
                pom {
                    name.set("Replay Android SDK")
                    description.set(
                        "Replayfy session replay + analytics for Android. " +
                            "Captures session replays (view-tree + PixelCopy bitmaps), " +
                            "taps, network requests, crashes, performance metrics " +
                            "(cold start, frame drops, ANR, memory, thermal), and " +
                            "console output.",
                    )
                    url.set("https://replayfy.io")
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
}
