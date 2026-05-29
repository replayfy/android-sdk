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
