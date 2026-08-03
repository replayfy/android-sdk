// Root build — plugins are applied per-module via the version catalog.
// Nothing else lives here for now.

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Declares the Kotlin JVM plugin version once (same KGP as kotlin.android);
    // :sdk-symbols-plugin applies it. Avoids "already on classpath with unknown
    // version" when a subproject applies it with a version of its own.
    alias(libs.plugins.kotlin.jvm) apply false
}
