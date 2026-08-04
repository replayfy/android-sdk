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
- **Privacy first** — per-view, per-class, and whole-screen masking; password
  fields and sensitive inputs are redacted on-device before anything is sent.
- **Zero-config start** — one `init` call wires recording, tap capture, screen
  detection, crash handling, and performance monitoring. No manifest changes.

## Install

Add the JitPack repository to your project, then add the dependency.

**`settings.gradle.kts`** (Gradle 7+, `dependencyResolutionManagement`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

<details>
<summary>Older Gradle (root <code>build.gradle</code> with <code>allprojects</code>)</summary>

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```
</details>

**App module `build.gradle.kts`:**

```kotlin
dependencies {
    implementation("com.github.replayfy:android-sdk:0.0.3")
}
```

Requires `minSdk 21` or higher.

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
                apiHost = "https://us.replayfy.app",
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
| `apiHost` | `String` | **required** | Ingest base URL — `https://us.replayfy.app`. |
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

### Screen tracking

Activities and androidx Fragments are tracked automatically. For Compose
screens or custom views those hooks can't see:

```kotlin
Replay.addObservedView(checkoutComposeView, "Checkout")  // emits on attach + detach
Replay.tagScreenName("Checkout")                         // or set the current screen directly
Replay.setAutomaticScreenNameTagging(false)              // disable auto-naming; drive it yourself
```

| Method | Description |
|---|---|
| `tagScreenName(name)` | Manually set the current screen name. |
| `addObservedView(view, screenName, viewName?)` | Track a Compose/custom view as a screen (emits on attach/detach). |
| `setAutomaticScreenNameTagging(enabled)` | Toggle Activity-based auto screen naming at runtime. |

### Text input tracking

Input capture is opt-in per field — never global. Values are recorded on
focus-loss (not per keystroke), and password-type fields report `"***"`.

```kotlin
Replay.addObservedInput(emailEditText)
Replay.addObservedInput(passwordEditText)                     // password inputType → "***"
Replay.trackInput(label = "Coupon", value = code, masked = false)  // for non-EditText values
```

| Method | Description |
|---|---|
| `addObservedInput(editText)` | Record an `EditText`'s value on focus-loss (passwords auto-masked). |
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
```

| Method | Description |
|---|---|
| `isRecording()` | Whether the SDK is currently recording. |
| `pauseRecording()` / `resumeRecording()` | Pause/resume screen capture while keeping the interaction timeline. |
| `startNewSession()` | Force-end the current session and start a new one (logout/login, re-bucketing). |
| `stop()` | End the current session and flush the buffered batch. The next foregrounding starts a fresh session. |
| `stopApplicationAndUploadData(timeoutMs?)` | Stop and block (up to `timeoutMs`, default 5000) until the batch is uploaded or persisted — for sign-out / shutdown flows. |
| `enableAdvancedGestureRecognizer(enabled)` | Capture long-press, swipe, and pinch in addition to taps (off by default). |
| `addVerificationListener(listener)` / `removeVerificationListener(listener)` | Fire a callback after the first successful upload — for onboarding "Verify integration" flows. |

## Privacy & masking

All masking happens on-device before anything is uploaded. Password fields are
masked automatically; everything else is opt-in.

```kotlin
Replay.addPrivacyView(cardNumberField)             // mask one view (and its children)
Replay.removePrivacyView(cardNumberField)

Replay.occludeAllTextFields(true)                  // mask every EditText
Replay.occludeAllTextView(true)                    // mask every TextView / Button
Replay.applyOcclusion(CreditCardView::class.java)  // mask all instances of a view class
Replay.removeOcclusion(CreditCardView::class.java)
Replay.occludeSensitiveScreen(true)                // mask the entire screen

Replay.setMaskStyle(ReplayMaskStyle.BLUR)          // BLUR (default) or OVERLAY
Replay.setBlurDownscale(12)                        // stronger blur = larger factor
```

| Method | Description |
|---|---|
| `addPrivacyView(view)` / `removePrivacyView(view)` | Mask a single view (marks propagate to descendants). |
| `occludeAllTextFields(occlude)` | Mask every `EditText` in the app. |
| `occludeAllTextView(occlude)` | Mask every `TextView` / `Button` (anything showing text). |
| `applyOcclusion(viewClass)` / `removeOcclusion(viewClass)` | Mask every instance of a custom view class. |
| `occludeSensitiveScreen(occlude)` | Mask the entire captured screen. |
| `setMaskStyle(style)` | Default masking style — `ReplayMaskStyle.BLUR` or `ReplayMaskStyle.OVERLAY`. |
| `setBlurDownscale(factor)` | Blur strength for `BLUR` regions (default 12; higher = stronger). |

## Links

- Docs: https://replayfy.app
- Dashboard: https://replayfy.app

---

_Some forward-compatibility methods are present on the public surface but not
yet active in this release — calling them today is safe but has no effect:
`cancelSession()`, `optOutSchematicRecordings()` / `isOptedOutSchematicRecordings()`,
`urlForCurrentSession()` / `urlForCurrentUser()`, `allowShortBreakForAnotherApp()`,
and `setMultiSessionRecord()`._

Licensed under the [BSD-3-Clause License](./LICENSE).
</content>
</invoke>
