// Example app used for emulator smoke-testing — not published.
// Depends on :sdk + :sdk-compose via project() so we exercise the
// AAR build path, not an external maven artifact.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.replayfy.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.replayfy.example"
        minSdk = 24  // PixelCopy needs API 24+ to validate the hardware-surface path
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Required by AGP 8.x — even though we don't use BuildConfig
    // directly, the application target without this fails to merge
    // the manifest if any transitive lib generates BuildConfig.
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // The SDK under test — local project() so every build picks up
    // the latest code automatically.
    implementation(project(":sdk"))
    implementation(project(":sdk-compose"))

    // Plain AppCompat + ConstraintLayout — keeps the example app
    // small + Compose-free so we can also confirm the SDK works
    // with the legacy View system (which is still ~half of the
    // Android install base).
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
