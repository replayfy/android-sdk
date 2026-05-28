# Replay Android SDK

Session replay + analytics for Android apps.

## Status

**Foundation only — not production-ready.** The public API surface is
in place but the recording engine ships in follow-up commits:

- ✅ Zero-config auto-bootstrap (ContentProvider)
- ✅ ProcessLifecycleOwner-based session boundaries
- ✅ OkHttp batch sender (POST `/v1/replay/batch`)
- ✅ Custom event tracking (`Replay.track`)
- ✅ Identify (`Replay.identify`)
- ✅ Tap tracker — per-View OnTouchListener wrap, WindowManagerGlobal
  walk catches dialogs/popups, widget classification + value extraction
- ✅ Manual screen tagging (`Replay.tagScreenName`)
- ✅ Snapshot pipeline (tree-only) — view tree serializer emits
  `native_snapshot` events on screen change + 500ms after each tap.
  Player can render a wireframe today.
- ⏳ Snapshot pipeline (bitmap) — PixelCopy + Legacy Canvas, image
  bytes uploaded to `/v1/replay/assets/:hash` for high-fidelity playback
- ⏳ Persistent upload queue — WorkManager when app is dead
- ⏳ Network capture — OkHttp Interceptor
- ⏳ Crash handler — Thread.UncaughtExceptionHandler
- ⏳ Occlusion / privacy views
- ⏳ Native performance metrics (cold_start_ms, frame drops, ANR, etc.)

Each ⏳ corresponds to a follow-up commit; see `/docs` in the
`replay-web-sdk` repo for the design specs (`mobile-vitals-matrix.md`,
`native-snapshot-format.md`).

## Quick start

Add the SDK to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.replayfy:android-sdk:0.0.1")
}
```

Then in your `Application.onCreate`:

```kotlin
import com.replayfy.android.Replay
import com.replayfy.android.ReplayConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Replay.init(this, ReplayConfig(
            apiKey = "rpl_pk_…",
            apiHost = "https://ingest.replayfy.io",
        ))
    }
}
```

That's it. The SDK auto-starts on first activity, ends sessions on
background, retries failed uploads.

## API surface

```kotlin
Replay.init(context, config)        // start the SDK
Replay.identify(userId, props)      // attach user identity
Replay.track("checkout_started",    // custom event
             mapOf("amount" to 99))
Replay.stop()                       // manual session end
Replay.isRecording()                // status
Replay.tagScreenName("Login")       // (stub) override auto-tag
Replay.addPrivacyView(view)         // (stub) mark view sensitive
Replay.pauseRecording()             // (stub) pause schematic
Replay.resumeRecording()            // (stub) resume schematic
```

## Building locally

The repo uses Gradle but doesn't ship the wrapper jar (binary). Either:

1. Install Gradle (`brew install gradle`) and run `gradle wrapper`
   once, or
2. Open the project in Android Studio — it'll handle the wrapper +
   sync.

Then:

```bash
./gradlew :sdk:assembleRelease   # builds the .aar
./gradlew :sdk:test               # JVM unit tests (none yet)
```

## Architecture

The SDK borrows its architecture from UXCam's Android SDK
(decompiled + studied during the design phase) and pairs it with
our existing web SDK's batch envelope so the backend treats web and
mobile sessions uniformly.

Layers (mirrors UXCam's 3-AAR split, currently in one module):

- `com.replayfy.android` — public API (`Replay`, `ReplayConfig`)
- `com.replayfy.android.internal` — orchestration + transport
- `com.replayfy.android.capture` — snapshot pipelines (TBD)
- `com.replayfy.android.tracker` — gesture tracker (TBD)

Bootstrap chain:

1. App process starts
2. Android instantiates `ReplayContentProvider` (declared in our merged
   manifest)
3. `ReplayContentProvider.onCreate` calls `ReplayCore.autoBootstrap`
4. ProcessLifecycleOwner observer registered
5. Host app's `Application.onCreate` fires; customer calls `Replay.init`
6. ReplayCore wires up the BatchSender + flush loop + emits
   `session_start`
7. App enters foreground/background → session rotates

## License

Commercial. Contact help@replayfy.io for terms.
