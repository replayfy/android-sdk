plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// Maven Central publication for the Compose integration — published
// as `com.replayfy:android-sdk-compose:0.0.1`. See :sdk's build file
// for the credential setup walkthrough; this module reuses the same
// gradle.properties / env-var keys.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId    = "com.replayfy"
                artifactId = "android-sdk-compose"
                version    = "0.0.1"
                pom {
                    name.set("Replay Android SDK — Jetpack Compose")
                    description.set(
                        "Modifier.replayOcclude + Modifier.replayTagScreenName for " +
                            "Compose-first Android apps. Depends on com.replayfy:android-sdk.",
                    )
                    url.set("https://replayfy.io")
                    licenses {
                        license {
                            name.set("BSD-3-Clause")
                            url.set("https://opensource.org/licenses/BSD-3-Clause")
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
