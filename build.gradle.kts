// Root build — plugins are applied per-module via the version catalog.
// Nothing else lives here for now.

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
