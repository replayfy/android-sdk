# Replayfy for Android

> Session replay, product analytics & error monitoring for Android apps.

Replayfy records what your users actually do — screen-by-screen replays, taps
and gestures, screen navigation, network activity, console logs, crashes and
performance vitals — and streams it to your Replayfy dashboard so you can watch
real sessions, build funnels, and debug crashes from the exact moment they
happened.

## Features

- **Session replay** — pixel-accurate screen recording with automatic tap,
  gesture, and screen-navigation capture.
- **Product analytics** — identify users and fire custom events that power
  funnels, retention, and filterable session search.
- **Error monitoring** — automatic crash capture plus a first-class API for
  reporting handled exceptions.
- **Performance vitals** — cold start, frame drops, ANR, memory, and thermal
  state, captured automatically.
- **Network capture** — opt-in HTTP request/response logging via a drop-in
  OkHttp interceptor.
- **Privacy first** — per-view, per-class, and whole-screen masking; password,
  card and one-time-code fields and `FLAG_SECURE` screens are redacted
  on-device before anything is sent.
- **Zero-config start** — one `init` call wires recording, tap capture, screen
  detection, crash handling, and performance monitoring. No manifest changes.

## Install

The SDK is published to Maven Central, which new Android projects already list
in their repositories (`google()` + `mavenCentral()`). Add the dependency to
your app module's **`build.gradle.kts`**:

```kotlin
dependencies {
    implementation("app.replayfy:android-sdk:0.0.7")
}
```

Requires `minSdk 21` or higher.

<details>
<summary>JitPack (fallback, if your network can't reach Maven Central)</summary>

Add the JitPack repository in **`settings.gradle.kts`** (Gradle 7+,
`dependencyResolutionManagement`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

or, on older Gradle, in the root `build.gradle`:

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Then, in the app module's **`build.gradle.kts`**, the same version under
JitPack's group:

```kotlin
dependencies {
    implementation("com.github.replayfy:android-sdk:0.0.7")
}
```
</details>

## Quick start

Initialize once from your `Application.onCreate`. That single call starts
recording and wires tap capture, screen detection, crash handling, and
performance monitoring.

```kotlin
import android.app.Application
import com.replayfy.android.Replay
import com.replayfy.android.ReplayConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Replay.init(
            this,
            ReplayConfig(
                apiKey = "rpl_pk_xxxxxxxx",
                apiHost = "https://us.replayfy.app",  // or your self-hosted API URL
            ),
        )
    }
}
```

Attach a known user and record events:

```kotlin
Replay.identify("user_123", mapOf("email" to "a@b.com", "plan" to "pro"))
Replay.track("purchase", mapOf("amount" to 4200, "currency" to "USD"))
```

## Configuration

Only `apiKey` and `apiHost` are required; every other option has a sensible
default. Pass overrides through `ReplayConfig`:

| Option | Type | Default | Description |
|---|---|---|---|
| `apiKey` | `String` | **required** | Project API key from your dashboard. |
| `apiHost` | `String` | **required** | Ingest base URL — `https://us.replayfy.app` (or your self-hosted API URL). |
| `projectId` | `String?` | `null` | Only needed when one key spans multiple projects. |
| `distinctId` | `String?` | `null` | Known user id at init time; otherwise an install-stable anonymous id is generated. |
| `flushIntervalMs` | `Long` | `5000` | How often the buffered batch is uploaded (ms). |
| `maxBufferSize` | `Int` | `500` | Events buffered in memory before a flush is forced. |
| `captureConsole` | `Boolean` | `true` | Capture `Log.x` / stdout / stderr output. |
| `captureNetwork` | `Boolean` | `false` | Capture HTTP requests (off by default to avoid PII). |
| `captureErrors` | `Boolean` | `true` | Capture uncaught exceptions and crashes. |
| `captureTouch` | `Boolean` | `true` | Capture taps and gestures from the view tree. |
| `captureHeaders` | `Boolean` | `false` | Include request/response headers in network capture (PII risk). |
| `captureBodies` | `Boolean` | `true` | Include request/response bodies in network capture (capped, PII risk). |
| `maxBodyBytes` | `Int` | `4096` | Max captured body bytes per network event; larger bodies are truncated. |
| `captureSnapshotPixels` | `Boolean` | `true` | Pixel-accurate playback vs. lighter tree-only "wireframe" playback. |
| `snapshotIntervalMs` | `Long` | `500` | Screen-capture cadence (~2 FPS); floored at 200 ms. |
| `autoScreenName` | `Boolean` | `true` | Auto-name the current screen from each Activity's class name. |
| `useRemoteConfig` | `Boolean` | `true` | Let dashboard settings override these values at runtime (your values act as cold-start fallbacks; ~15-minute refresh). |
| `excludedScreens` | `List<String>` | `[]` | Screens excluded from frame capture by name (case-insensitive); other events keep flowing. Mutable at runtime via `excludeScreen` / `setExcludedScreens`. |

## API

The SDK is exposed as the `Replay` singleton. All methods are static-callable
from Kotlin and Java.

### Identity & events

```kotlin
// Attach a known user to the session. email / name / plan are promoted to
// filterable user fields; other properties are kept as custom traits.
Replay.identify("user_123", mapOf("email" to "a@b.com", "plan" to "pro"))

// Fire a custom event (drives funnels and event filters).
Replay.track("checkout_completed", mapOf("amount" to 4200))

// Sticky properties that persist across sessions (user) or this session only.
Replay.setUserProperty("plan", "pro")
Replay.setSessionProperty("ab_variant", "B")
```

| Method | Description |
|---|---|
| `identify(distinctId, properties?)` | Attach a known-user identity; earlier anonymous sessions link retroactively. |
| `track(name, properties?)` | Record a custom timeline / funnel event. |
| `setUserProperty(key, value)` | Attach a property that sticks to the user across sessions. |
| `setSessionProperty(key, value)` | Attach a property to the current session only. |
| `addTagWithProperties(name, properties?)` | Add a session-level tag for filtering the session list. |
| `markSessionAsFavorite()` | Star the current session so it surfaces in the "starred" filter. |
| `setAppVersion(version, build?)` | Override the auto-detected app version/build (useful for white-label or wrapper builds). |
| `setPushNotificationToken(token, platform?)` | Attach the device push token (`platform` defaults to `"fcm"`). |

### Error monitoring

Uncaught crashes are captured automatically when `captureErrors = true`. To
report a handled exception onto the timeline:

```kotlin
try {
    riskyWork()
} catch (e: Exception) {
    Replay.captureException(e, handled = true, properties = mapOf("screen" to "checkout"))
}
```

| Method | Description |
|---|---|
| `captureException(error, handled?, properties?)` | Report a caught throwable. `handled = false` marks it fatal (crash) vs. exception. |
| `reportBugEvent(name, description?, properties?)` | Flag a user-submitted bug report and trigger a fresh screen capture. |
| `log(level, message, stack?)` | Bridge your logging framework (Timber, etc.) into the console tab. `level` ∈ `log`/`info`/`warn`/`error`/`debug`. |

<details>
<summary>Bridging Timber into the console stream</summary>

```kotlin
Timber.plant(object : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.ERROR, Log.ASSERT -> "error"
            Log.WARN              -> "warn"
            Log.INFO              -> "info"
            Log.DEBUG             -> "debug"
            else                  -> "log"
        }
        Replay.log(level, message, t?.stackTraceToString())
    }
})
```
</details>

#### Readable crash stacks (symbol upload)

Release builds are obfuscated by R8, so crash stacks arrive as `a.b.c(SourceFile:1)`. The
`app.replayfy.symbols` Gradle plugin uploads each build's `mapping.txt` (and native `.so` debug
symbols) after `assembleRelease`, so the dashboard shows real class and method names. It needs a
**server key** (`rpl_sk_…`, Settings → Install → Generate key), not the publishable key:

```kotlin
// settings.gradle.kts: pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
plugins {
    id("com.android.application")
    id("app.replayfy.symbols") version "0.1.0"
}

replaySymbols {
    apiKey = providers.environmentVariable("REPLAYFY_SERVER_KEY").getOrElse("")
    // apiHost = "https://replay.example.com" // only if you self-host
}
```

### Screen tracking

Activities and androidx Fragments are tracked automatically. For Compose
screens or custom views those hooks can't see:

```kotlin
Replay.addObservedView(checkoutComposeView, "Checkout")  // emits on attach + detach
Replay.tagScreenName("Checkout")                         // or set the current screen directly
Replay.setAutomaticScreenNameTagging(false)              // disable auto-naming; drive it yourself
```

You can also exclude a screen from recording by name. While an excluded screen
is in the foreground the periodic frame capture pauses (taps, network, console,
and performance events keep flowing); it resumes on the next non-excluded
screen. Matching is case-insensitive against the auto-tagged / `tagScreenName`
name. A `tagScreenName` name stays the name of its Activity's screen when that
Activity comes back (from the background, from another Activity, or after a
rotation), so a screen excluded by its tagged name stays excluded. This is
distinct from `occludeSensitiveScreen` (which keeps recording but blanks the
frame) — an excluded screen captures no frames at all. Seed the
initial denylist at start via `ReplayConfig(excludedScreens = ...)`, or change it
at runtime:

```kotlin
Replay.excludeScreen("PinEntry")                          // stop capturing frames of this screen
Replay.unexcludeScreen("PinEntry")                        // start capturing it again
Replay.setExcludedScreens(listOf("PinEntry", "Paywall"))  // replace the whole denylist
```

| Method | Description |
|---|---|
| `tagScreenName(name)` | Manually set the current screen name. |
| `addObservedView(view, screenName, viewName?)` | Track a Compose/custom view as a screen (emits on attach/detach). |
| `setAutomaticScreenNameTagging(enabled)` | Toggle Activity-based auto screen naming at runtime. |
| `excludeScreen(name)` | Exclude a screen from frame capture by name (case-insensitive); other events keep flowing. |
| `unexcludeScreen(name)` | Stop excluding a previously-excluded screen. |
| `setExcludedScreens(names)` | Replace the entire excluded-screens denylist. |

### Text input tracking

Input capture is opt-in per field — never global. Values are recorded on
focus-loss (not per keystroke), and every field masked in the recording
(password, card and one-time-code fields, `occludeAllTextFields`, privacy
views, secure windows) reports `"***"`, never its text.

```kotlin
Replay.addObservedInput(emailEditText)
Replay.addObservedInput(passwordEditText)                     // masked field → "***"
Replay.trackInput(label = "Coupon", value = code, masked = false)  // for non-EditText values
```

| Method | Description |
|---|---|
| `addObservedInput(editText)` | Record an `EditText`'s value on focus-loss (masked fields send `"***"`). |
| `trackInput(label, value, masked)` | Record a value not backed by an `EditText` (Compose/custom); `masked = true` sends `"***"`. |

### Network capture

Capture is opt-in (`captureNetwork = true`) and wired by adding the interceptor
to your OkHttp client. When disabled, the interceptor passes through with zero
overhead.

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(Replay.networkInterceptor())
    .build()
```

| Method | Description |
|---|---|
| `networkInterceptor()` | Returns an OkHttp `Interceptor` that records requests when `captureNetwork` is on. |

### Session control

```kotlin
Replay.pauseRecording()      // pause screen capture; events keep flowing
Replay.resumeRecording()
Replay.startNewSession()     // end the current session and begin a fresh one
Replay.stop()                // end the session and upload the buffered batch
Replay.cancelSession()       // discard the current session without uploading

Replay.allowShortBreakForAnotherApp(true)   // a brief app-switch keeps the same session
Replay.setMultiSessionRecord(false)         // record only one session per app launch
```

| Method | Description |
|---|---|
| `isRecording()` | Whether the SDK is currently recording. |
| `pauseRecording()` / `resumeRecording()` | Pause/resume screen capture while keeping the interaction timeline. The pause holds across background/foreground and session rotation until you resume. |
| `optOutSchematicRecordings(optOut)` / `isOptedOutSchematicRecordings()` | Durable (persisted) frame opt-out: no frames are captured or uploaded while opted out; every other event keeps flowing. Independent of `pauseRecording()`: opting back in doesn't lift a pause still in effect. |
| `startNewSession()` | Force-end the current session and start a new one (logout/login, re-bucketing). |
| `stop()` | End the current session and flush the buffered batch. The next foregrounding starts a fresh session. |
| `cancelSession()` | Discard the current session without uploading — the server drops whatever already streamed. A fresh session can start afterward. |
| `stopApplicationAndUploadData(timeoutMs?)` | Stop and block (up to `timeoutMs`, default 5000) until the batch is uploaded or persisted — for sign-out / shutdown flows. |
| `allowShortBreakForAnotherApp(allow, breakWindowMs?)` | A brief switch to another app (a gap up to `breakWindowMs`, default 30000) keeps the same session; a longer gap starts a fresh session on return. |
| `setMultiSessionRecord(enabled)` | Record multiple sessions per app launch (default `true`) or, when `false`, only one — after the current/next session ends no further sessions open this process. |
| `enableAdvancedGestureRecognizer(enabled)` | Capture pinch and rotate gestures in addition to the always-on tap / swipe / long-press (off by default). |
| `addVerificationListener(listener)` / `removeVerificationListener(listener)` | Fire a callback after the first successful upload — for onboarding "Verify integration" flows. |

## Privacy & masking

All masking happens on-device before anything is uploaded. These are masked
automatically, always as a solid box, with no setup:

- password fields (any password input type, or a password transformation),
  including while "show password" is on;
- card fields, by autofill hint: `creditCardNumber`, `creditCardSecurityCode`,
  `creditCardExpirationDate`, `creditCardExpirationMonth`,
  `creditCardExpirationYear`;
- one-time-code fields: any autofill hint containing `otp` (e.g. `smsOTPCode`);
- every screen of a `FLAG_SECURE` window, recorded as a black frame (the window
  is never copied or drawn).

Everything else is opt-in. The dashboard's "Always mask input values" setting
masks every `EditText` from the next session start, on top of what the app
sets: `occludeAllTextFields(false)` doesn't undo it. Taps on a masked view are
labelled by resource id or class name, never by the text it shows.

Jetpack Compose text fields are not detected automatically. Mark secure
Compose fields (passwords, card numbers, codes) with `Modifier.replayOcclude()`
from the `sdk-compose` module.

```kotlin
Replay.addPrivacyView(cardNumberField)             // mask one view (and its children)
Replay.removePrivacyView(cardNumberField)

Replay.occludeAllTextFields(true)                  // mask every EditText
Replay.occludeAllTextView(true)                    // mask every TextView / Button
Replay.applyOcclusion(CreditCardView::class.java)  // mask all instances of a view class
Replay.removeOcclusion(CreditCardView::class.java)
Replay.occludeSensitiveScreen(true)                // cover the entire screen (always solid)

Replay.setMaskStyle(ReplayMaskStyle.BLUR)          // OVERLAY (default, solid), BLUR or PIXELATE
Replay.setBlurDownscale(12)                        // stronger blur = larger factor
```

| Method | Description |
|---|---|
| `addPrivacyView(view)` / `removePrivacyView(view)` | Mask a single view (marks propagate to descendants). |
| `occludeAllTextFields(occlude)` | Mask every `EditText` in the app (the dashboard can also force this on). |
| `occludeAllTextView(occlude)` | Mask every `TextView` / `Button` (anything showing text). |
| `applyOcclusion(viewClass)` / `removeOcclusion(viewClass)` | Mask every instance of a custom view class. |
| `occludeSensitiveScreen(occlude)` | Cover the entire captured screen with one solid box. |
| `setMaskStyle(style)` | Default masking style — `ReplayMaskStyle.OVERLAY` (default, a solid box), `BLUR` or `PIXELATE`. Automatically masked fields, the whole-screen cover and secure screens are always solid. |
| `setBlurDownscale(factor)` | Blur strength for `BLUR` regions (default 12; higher = stronger). |

## Links

- Docs: https://docs.replayfy.app/platforms/android
- Dashboard: https://app.replayfy.app

---

_Some forward-compatibility methods are present on the public surface but not
yet active in this release — calling them today is safe but has no effect:
`urlForCurrentSession()` / `urlForCurrentUser()`._

Licensed under the [MIT License](./LICENSE).
