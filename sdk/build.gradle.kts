plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
