// Standalone Gradle plugin published to Maven Central as
// `com.replayfy:symbols-plugin`. Customers apply it to their app
// module:
//
//   plugins {
//     id("com.android.application")
//     id("com.replayfy.symbols") version "0.0.1"
//   }
//
//   replaySymbols {
//     apiKey = "rk_..."
//     // apiHost defaults to https://api.replayfy.io — override for
//     // self-hosted deployments.
//     // uploadOnAssemble defaults to true.
//   }
//
// The plugin discovers `mapping.txt` + per-ABI `.so` debug binaries
// produced by R8 + the NDK build, then POSTs them to
// /v1/replay/symbols/<platform>/<version>/<build>/<filename>.

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    signing
}

group = "com.replayfy"
version = "0.0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // Required by Maven Central — sources + javadoc jars must be
    // published alongside the plugin artifact.
    withSourcesJar()
    withJavadocJar()
}

repositories {
    google()
    mavenCentral()
}

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
            id = "com.replayfy.symbols"
            implementationClass = "com.replayfy.symbols.ReplaySymbolsPlugin"
            displayName = "Replayfy Symbols Plugin"
            description =
                "Uploads R8 mapping.txt + NDK .so debug symbols to " +
                "Replayfy on every assembleRelease so crash + ANR " +
                "stacks deobfuscate on the dashboard."
        }
    }
}

publishing {
    publications {
        // Mirrors the SDK's publication block — Maven Central
        // requires the POM with developer + SCM info.
        afterEvaluate {
            named<MavenPublication>("pluginMaven") {
                pom {
                    name.set("Replayfy Symbols Plugin")
                    description.set(
                        "Gradle plugin that uploads R8 mapping.txt + " +
                            "NDK .so debug symbols to Replayfy on every " +
                            "assembleRelease.",
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
                    ?: System.getenv("OSSRH_USERNAME") ?: ""
                password = (project.findProperty("ossrhPassword") as String?)
                    ?: System.getenv("OSSRH_PASSWORD") ?: ""
            }
        }
    }
}

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
