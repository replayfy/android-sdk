plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.replayfy.android.compose"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        // Enables the Compose compiler plugin for this module's
        // sources only. Customers who depend on this module already
        // have Compose set up in their own app; we just need the
        // compiler enabled here so our @Composable functions
        // typecheck.
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Depend on the core SDK so the modifiers can call into
    // PrivacyRegistry + Replay.tagScreenName directly.
    api(project(":sdk"))

    // Compose UI + runtime via BoM — BoM aligns transitives so
    // foundation/material3/etc resolve to one tested set when the
    // customer pulls them in alongside.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
}
