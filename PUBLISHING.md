# Publishing the Replayfy Android SDK

The SDK ships on **two** channels. Central is primary; JitPack is a fallback.

| Channel | Coordinate | When it's used |
| --- | --- | --- |
| **Maven Central** (primary) | `app.replayfy:android-sdk:<version>` | production consumers; mirrored + cached, never blocked |
| **JitPack** (fallback) | `com.github.replayfy:android-sdk:<tag>` | on-demand from a git tag; no account needed |

## Why Central is primary

JitPack builds on demand and serves from `jitpack.io`, a single host that
corporate/CI networks frequently can't reach — the failure a customer hit was a
Gradle `No route to host` against `jitpack.io`. Maven Central is mirrored across
CDNs and internal proxies, so that class of failure disappears. We keep JitPack
wired as a zero-setup fallback.

Publishing uses the **`com.vanniktech.maven.publish`** plugin: it uploads to the
**Central Portal** and **auto-releases** — no manual "Close & Release".
(Validated locally — `publishToMavenLocal` produces the signed AAR + sources +
javadoc + POM at `app.replayfy:android-sdk`.)

## One-time owner setup

1. **Claim the `app.replayfy` namespace** on the Central Portal
   (`central.sonatype.com` ▸ Add Namespace) and verify ownership — either a DNS
   TXT record on `replayfy.app`, or the `github.com/replayfy` org.

2. **Generate a Central Portal user token** (Account ▸ Generate User Token) — a
   username + password pair (not your login).

3. **GPG signing key** (Central requires signed artifacts):
   - `gpg --armor --export-secret-keys <KEYID>` → the ASCII-armored private key.
   - Publish the public key: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`.

4. **Add repo secrets** (Settings ▸ Secrets ▸ Actions):
   - `CENTRAL_PORTAL_USERNAME`, `CENTRAL_PORTAL_PASSWORD` — the user-token pair.
   - `SIGNING_KEY` — the ASCII-armored private key.
   - `SIGNING_PASSWORD` — its passphrase (empty if the key is unprotected).

## Cutting a release

1. `git tag v0.0.4 && git push origin v0.0.4` — `publish-maven-central.yml` runs
   `./gradlew publishAndReleaseToMavenCentral -PpublishGroupId=app.replayfy
   -PpublishVersion=0.0.4`, which uploads **and releases** to Central.
2. It syncs to Maven Central in ~15–30 min. No UI step.
3. Once live, the Flutter plugin's `app.replayfy:android-sdk:0.0.4` resolves from
   `mavenCentral()`. JitPack still serves `com.github.replayfy:android-sdk:0.0.4`
   from the same tag for anyone pinned to it.

To publish from your Mac instead of CI, export the four env vars
(`ORG_GRADLE_PROJECT_mavenCentralUsername`/`…Password`,
`ORG_GRADLE_PROJECT_signingInMemoryKey`/`…KeyPassword`) and run the same command
with **JDK 17** (`JAVA_HOME=$(/usr/libexec/java_home -v 17)`) — AGP 8.5 rejects
the Mac's default JDK 26.

## Local / JitPack builds are unchanged

Running `./gradlew :sdk:publishToMavenLocal` (or a JitPack build) passes no
`-PpublishGroupId/-PpublishVersion`, so the publication keeps the JitPack
coordinate `com.github.replayfy:android-sdk` — nothing about the existing flow
breaks.

## Symbols Gradle plugin (`app.replayfy.symbols`)

The `:sdk-symbols-plugin` module publishes separately from the SDK, as
`app.replayfy:symbols-gradle-plugin` plus its plugin marker
`app.replayfy.symbols:app.replayfy.symbols.gradle.plugin`, using the same Central
Portal token and GPG secrets. It has its own version.

1. `git tag symbols-v0.1.0 && git push origin symbols-v0.1.0`:
   `publish-symbols-plugin.yml` runs `./gradlew :sdk-symbols-plugin:publishAndReleaseToMavenCentral
   -PsymbolsPluginVersion=0.1.0`, which uploads and releases it.
2. It syncs to Maven Central in ~15–30 min. Consumers need `mavenCentral()` in
   `pluginManagement.repositories`.

The SDK workflow publishes `:sdk` only, so an SDK `v*` tag never republishes the plugin.

