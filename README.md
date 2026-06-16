# Replay Android SDK

Session replay + analytics for Android. Captures screen recordings,
taps & gestures, screen navigation, network, console logs, crashes, and
performance vitals, and streams them to your Replayfy dashboard.

- **Min SDK:** 21
- **Coordinates:** `com.replayfy:android-sdk:0.0.1`

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.replayfy:android-sdk:0.0.1")
}
```

No manifest changes are required — a bundled `ContentProvider` boots the
SDK before `Application.onCreate`, so lifecycle/crash hooks attach as
early as possible.

## Quick start

A single call starts everything (recording, tap capture, screen
detection, crash + performance capture):

```kotlin
import com.replayfy.android.Replay
import com.replayfy.android.ReplayConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Replay.init(
            this,
            ReplayConfig(
                apiKey = "rpl_pk_xxxxxxxx",
                apiHost = "https://ingest.replayfy.io",
            ),
        )
    }
}
```

Attach a known user and fire custom events:

```kotlin
Replay.identify("user_123", mapOf("email" to "a@b.com", "plan" to "pro"))
Replay.track("purchase", mapOf("amount" to 4200, "currency" to "USD"))
```

## Configuration — `ReplayConfig`

Only `apiKey` and `apiHost` are required; everything else has a sensible
default. With `useRemoteConfig = true` (default), your values act as
cold-start fallbacks — after the first fetch, dashboard settings win
(15-minute refresh), so flags can change without an app update.

| Property | Type | Default | Description |
|---|---|---|---|
| `apiKey` | `String` | **required** | Project API key from the dashboard |
| `apiHost` | `String` | **required** | Ingest base URL, e.g. `https://ingest.replayfy.io` |
| `projectId` | `String?` | `null` | Only when one key spans multiple projects |
| `distinctId` | `String?` | `null` | Known user id at init; else an install-stable anonymous id |
| `flushIntervalMs` | `Long` | `5000` | Auto-flush cadence for the in-memory batch |
| `maxBufferSize` | `Int` | `500` | Events buffered before a flush is forced |
| `liveMode` | `Boolean` | `true` | Live-presence socket (shows the session "online" on the dashboard) |
| `captureConsole` | `Boolean` | `true` | Capture `Log.x` / stdout / stderr |
| `captureNetwork` | `Boolean` | `false` | Capture HTTP requests (off by default — PII risk) |
| `captureErrors` | `Boolean` | `true` | Capture uncaught exceptions / crashes |
| `captureHeaders` | `Boolean` | `false` | Include request/response headers in network capture (PII risk) |
| `maxBodyBytes` | `Int` | `4096` | Max captured body bytes per network event (truncated above) |
| `captureSnapshotPixels` | `Boolean` | `true` | Pixel-accurate playback vs tree-only "wireframe" |
| `snapshotIntervalMs` | `Long` | `500` | Snapshot cadence (~2 FPS); floored at 200 ms |
| `autoScreenName` | `Boolean` | `true` | Auto-detect screen from each Activity's class name |
| `useRemoteConfig` | `Boolean` | `true` | Let dashboard config override these at runtime |

## Privacy & masking

```kotlin
Replay.addPrivacyView(cardNumberField)            // mask one view
Replay.removePrivacyView(cardNumberField)

Replay.occludeAllTextFields(true)                 // mask every EditText
Replay.occludeAllTextView(true)                   // mask every TextView/Button
Replay.applyOcclusion(CreditCardView::class.java) // mask a view class everywhere
Replay.occludeSensitiveScreen(true)               // mask the whole screen
Replay.occludeRectsOnNextFrame(rects)             // one-shot rect mask (RN/Flutter)
```

Password fields are masked automatically.

## Text input tracking

Record what a user types in a field — opt in per field. It reports on
focus-loss (not per keystroke); password-type fields send `"***"`, never
the text, and the field's hint is used as the label (the field's existing
focus listener is preserved):

```kotlin
Replay.addObservedInput(emailEditText)
Replay.addObservedInput(passwordEditText)   // password inputType → "***"
```

For a value not backed by an `EditText` (Compose, custom), report it:

```kotlin
Replay.trackInput(label = "Coupon", value = code, masked = false)
```

## Screen tracking

Activities are tracked automatically — and so are **Fragments**: the SDK
registers Fragment lifecycle callbacks, so single-Activity / Jetpack
Navigation apps report a screen per destination with no wiring. For
Compose screens or custom views the Activity/Fragment hooks can't see,
mark them manually:

```kotlin
Replay.addObservedView(checkoutComposeView, "Checkout")  // emits on attach + detach
Replay.tagScreenName("Checkout")                         // or set it directly (e.g. in a Compose LaunchedEffect)
```

## Network capture

Capture is opt-in (`captureNetwork = true`) and wired by adding the
interceptor to your OkHttp client:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(Replay.networkInterceptor())
    .build()
```

## API reference

### Active

| Method | Purpose |
|---|---|
| `init(context, config)` | Boot + start recording |
| `identify(distinctId, properties?)` | Attach a known user (`email` / `name` / `plan` are promoted) |
| `track(name, properties?)` | Custom timeline / funnel event |
| `tagScreenName(name)` | Manually set the current screen name |
| `addObservedView(view, screenName, viewName?)` | Mark a Compose/custom view as a screen (emits on attach/detach) |
| `addObservedInput(editText)` | Record a field's value on focus-loss (password → `"***"`) |
| `trackInput(label, value, masked)` | Record an input value explicitly (masked → `"***"`) |
| `log(level, message, stack?)` | Bridge a custom logger into the console tab |
| `addPrivacyView` / `removePrivacyView` | Per-view masking |
| `occludeAllTextFields` / `occludeAllTextView` | Bulk input / label masking |
| `applyOcclusion(Class)` / `removeOcclusion(Class)` | Mask all instances of a view class |
| `occludeSensitiveScreen(Boolean)` | Full-screen mask |
| `occludeRectsOnNextFrame(rects)` | One-shot rect masking |
| `networkInterceptor()` | OkHttp interceptor for network capture |

The engine additionally captures automatically: periodic screenshots,
taps & gestures, screen navigation (Activities + Fragments), device info
(model, RAM, OS, timezone, network type), performance vitals (cold start,
frame drops, ANR, memory, thermal), and crashes.

### Coming soon — currently no-ops

These methods exist on the public surface but are **not yet active** —
they are pending the engine consolidation (see `TODO.md`). Calling them
today is safe but has no effect:

`stop()` · `isRecording()` · `pauseRecording()` / `resumeRecording()` ·
`cancelSession()` · `optOutOverall(Boolean)` / `isOptedOutOverall()` ·
`optOutSchematicRecordings(Boolean)` / `isOptedOutSchematicRecordings()` ·
`setUserProperty` · `setSessionProperty` · `markSessionAsFavorite()` ·
`addTagWithProperties` · `reportBugEvent` · `urlForCurrentSession()` /
`urlForCurrentUser()` · `setAutomaticScreenNameTagging` ·
`setPushNotificationToken` · `startNewSession()` · `setAppVersion` ·
`allowShortBreakForAnotherApp` · `setMultiSessionRecord` ·
`enableAdvancedGestureRecognizer` · `addVerificationListener` /
`removeVerificationListener` · `stopApplicationAndUploadData`.

> Until these land, do not rely on `optOutOverall` for GDPR compliance.

## Building locally

```bash
./gradlew :sdk:assembleRelease   # builds the .aar
./gradlew :sdk:compileDebugKotlin
```

JDK 17 is required.

## ProGuard / R8

Consumer rules ship with the AAR — no host-app configuration needed.

## License

Commercial. Contact help@replayfy.io for terms.
