import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost

// Standalone Gradle plugin published to Maven Central as
// `app.replayfy:symbols-gradle-plugin` (plugin id `app.replayfy.symbols`).
// Customers apply it to their app module:
//
//   plugins {
//     id("com.android.application")
//     id("app.replayfy.symbols") version "0.1.0"
//   }
//
//   replaySymbols {
//     apiKey = "rpl_sk_..."   // a SERVER key, from your CI secrets
//     // apiHost defaults to https://us.replayfy.app — override for
//     // self-hosted deployments.
//     // uploadOnAssemble defaults to true.
//   }
//
// The plugin discovers `mapping.txt` + per-ABI `.so` debug binaries
// produced by R8 + the NDK build, then POSTs them to
// /v1/replay/symbols/<platform>/<version>/<build>/<filename>.
//
// Released on its own cadence by .github/workflows/publish-symbols-plugin.yml
// (tag `symbols-v<version>`), separate from the SDK's `v*` tags.

plugins {
    // This module is a normal Plugin<Project> class, NOT precompiled script
    // plugins — so it uses the Kotlin JVM plugin. The `kotlin-dsl` plugin's
    // precompiled-script-plugin machinery fails to configure here on this
    // Gradle/Kotlin combo (NoSuchMethodError registerKotlinJvmCompileTask).
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    // Same publisher as :sdk — uploads to the Central Portal and auto-releases.
    // It applies maven-publish + signing itself and also publishes the plugin
    // marker (app.replayfy.symbols:app.replayfy.symbols.gradle.plugin).
    id("com.vanniktech.maven.publish") version "0.30.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Dependency repositories come from settings.gradle.kts
// (dependencyResolutionManagement, FAIL_ON_PROJECT_REPOS) — declaring
// them here too is both redundant and rejected by that mode.

dependencies {
    // AGP compile-only — customers bring their own version. We
    // target the lowest AGP that exposes the modern
    // `androidComponents` extension (7.0+).
    compileOnly("com.android.tools.build:gradle:7.4.2")
    // OkHttp for the POSTs to /v1/replay/symbols. Picks up the
    // version already on the SDK's classpath in customer projects;
    // the plugin Jar bundles its own copy via implementation since
    // Gradle plugins run in their own classloader.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

gradlePlugin {
    plugins {
        register("replaySymbolsPlugin") {
            // Under the verified `app.replayfy` Central namespace: the plugin
            // marker's groupId IS the plugin id, so it must live there too.
            id = "app.replayfy.symbols"
            implementationClass = "com.replayfy.symbols.ReplaySymbolsPlugin"
            displayName = "Replayfy Symbols Plugin"
            description =
                "Uploads R8 mapping.txt + NDK .so debug symbols to " +
                "Replayfy on every assembleRelease so crash + ANR " +
                "stacks deobfuscate on the dashboard."
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    // Central requires a javadoc jar; this is a Kotlin plugin with KDoc in
    // the sources jar, so ship an empty javadoc jar.
    configure(GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    coordinates(
        "app.replayfy",
        "symbols-gradle-plugin",
        (project.findProperty("symbolsPluginVersion") as String?) ?: "0.1.0",
    )
    pom {
        name.set("Replayfy Symbols Plugin")
        description.set(
            "Gradle plugin that uploads R8 mapping.txt + NDK .so debug " +
                "symbols to Replayfy on every assembleRelease.",
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
