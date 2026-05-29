pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "replay-android-sdk"
include(":sdk")
// Optional Compose integration — Modifier.replayOcclude +
// Modifier.replayTagScreenName. Customers building View-only Android
// apps don't need to depend on this module; it adds nothing at
// runtime when unused.
include(":sdk-compose")
// Smoke-test app — not published. Validates the SDK end-to-end on
// the emulator: taps, snapshots (incl. SurfaceView for PixelCopy
// parity), privacy occlusion, and the ANR watchdog.
include(":example-app")
