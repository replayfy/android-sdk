# Changelog

All notable changes to the Replayfy Android SDK are documented here. This
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.7] - 2026-10-14

Masking that matches the reference SDKs' defaults, and fixes for text that
could reach a recording around a mask.

### Changed

- **Screens of a `FLAG_SECURE` window record as black frames.** The SDK no
  longer copies or draws a secure window at all; it encodes a solid black frame
  of the same size instead. Before, a secure window's content could reach the
  recording through PixelCopy or through the software-draw fallback, which
  ignores `FLAG_SECURE`. Frames that a host SDK reports (`Replay.reportFrame`,
  used by the Flutter SDK) are blacked out the same way while the Activity's
  window is secure. Apps that set `FLAG_SECURE` will see black frames for those
  screens in their replays.
- **Masks are solid by default.** The default mask style is now
  `ReplayMaskStyle.OVERLAY` (a solid box), as both reference SDKs paint masks
  opaque; it was `BLUR`. `Replay.setMaskStyle(ReplayMaskStyle.BLUR)` brings the
  blur back for `addPrivacyView`, `occludeAllTextFields`, `occludeAllTextView`,
  `applyOcclusion`, Compose `replayOcclude` and `occludeRectsOnNextFrame`.
  Automatically masked fields, the whole-screen cover and `FLAG_SECURE` frames
  are always solid.
- **More fields are masked automatically**, with no setup, always as a solid
  box:
  - password fields whatever extra input-type flags they carry (for example
    `textPassword|textNoSuggestions`), and fields that only use a password
    transformation. A password field with "show password" on stays masked;
  - card fields, by autofill hint: `creditCardNumber`,
    `creditCardSecurityCode`, `creditCardExpirationDate`,
    `creditCardExpirationMonth` and `creditCardExpirationYear`;
  - one-time-code fields: any autofill hint containing `otp` (for example
    `smsOTPCode`).
  There is no limit on how many fields one screen masks.
- **Password fields inside WebViews are masked automatically**, always as a
  solid box, as the reference SDK does by default. On every frame the SDK runs
  a small read-only script in each WebView on screen (it changes nothing on the
  page) that finds:
  - every `<input type="password">`;
  - every element with the class `rr-block`, the web SDK's block class, so one
    class hides an element on the web and in an app's WebView;
  - with `occludeAllTextFields` (the app's or the dashboard's): every text-like
    `<input>`, `<textarea>`, `contenteditable` and `.input-field` element.

  The masks follow scrolling and pinch zoom, and cover only the part of the
  WebView that shows. Only the page's top document is searched, as in the
  reference: fields inside an iframe or a shadow root are not found, so mask
  such a WebView with `addPrivacyView(webView)`. When a page doesn't answer in
  time, a frame reuses the previous frame's WebView masks while the same
  WebViews are on screen, and otherwise records without them. This covers
  React Native's `react-native-webview` too; Flutter apps capture their frames
  in Dart and are unchanged.
- **The dashboard's "Always mask input values" now applies to Android
  sessions.** When it is on, every `EditText` is masked, as with
  `occludeAllTextFields(true)`, from the next session start. It adds to the
  app's own setting: `occludeAllTextFields(false)` doesn't undo it, and turning
  it off on the dashboard never unmasks what the app masks.
- `occludeSensitiveScreen(true)` always covers the frame with one solid box,
  whatever `setMaskStyle` says, and now also covers frames a host SDK reports
  (Flutter), which it left untouched before.

### Fixed

- Masks follow what a view actually shows: its visible, clipped and transformed
  bounds. A view scaled up (an animation, a React Native transform) was masked
  at its smaller layout size, leaving its edges uncovered; a field partly
  scrolled out of view is now masked only where it shows.
- Jetpack Compose `replayOcclude` masks could land off target in split-screen
  and floating windows: they were offset by the window's position on screen.
- Mask edges are rounded outward when a frame is scaled down, so a blended edge
  pixel of masked content no longer survives at the border of a mask.
- `addObservedInput` reports `"***"` for every field masked in the recording:
  password, card and one-time-code fields, `occludeAllTextFields` (the app's or
  the dashboard's), privacy views and classes, the whole-screen cover and secure
  windows. Before, only a few exact password input types were masked, so a
  `textPassword|textNoSuggestions` field or a field under
  `occludeAllTextFields` sent its typed text.
- A tap on a masked view is no longer named after what the view shows. It used
  to send up to 50 characters of the view's text (a card number in a privacy
  view, text under `occludeAllTextView`, a React Native `<Text>` inside
  `<ReplayMask>`). Taps on masked views, under a Compose `replayOcclude` region
  or on a secure window are named by resource id or class name instead.

## [0.0.6] - 2026-10-14

### Fixed

- **No more crash in apps that use Jetpack Compose.** When PixelCopy could
  not copy the window (common on Android 8 and 9), the SDK fell back to
  drawing the view tree on its own background thread. Compose then crashed
  the app on its next frame with `IllegalArgumentException: Detected
  multithreaded access to SnapshotStateObserver`, and drawing any View off its
  thread is unsafe. The fallback now draws on the main thread and encodes the
  JPEG in the background.

## [0.0.5] - 2026-10-14

Session replay that holds up across background/resume cycles, and license
metadata that matches the MIT `LICENSE`.

### Fixed

- Going to the background is detected when the app's last visible Activity
  stops, not on every Activity pause (which also fired on in-app navigation and
  permission dialogs). On the way out the SDK uploads every buffered frame and
  every queued message batch. Anything that fails to send stays queued and goes
  out when the app returns, instead of being lost.
- Frames upload oldest first, in archives of at most 20, and a failed upload is
  retried instead of dropped. Pausing, resuming or ending a session sends every
  frame captured up to that moment instead of waiting for a full batch.
- Frames a host SDK reports (`Replay.reportFrame`, used by the Flutter SDK) are
  timestamped when reported, sent at the first background, and refused while
  the app is in the background, where Flutter's capture timer keeps running.
- Native screen capture only starts when `captureSnapshotPixels` is on. It could
  start on a return from the background, on `resumeRecording()` or on leaving an
  excluded screen, and record unmasked native screenshots in Flutter and React
  Native apps that had turned it off.
- Excluded screens, `pauseRecording()` and the schematic opt-out now hold across
  background/foreground, session rotation, device rotation and a Fragment
  coming back into view, and also stop frames a host SDK reports. A screen named
  with `tagScreenName` keeps that name when its Activity comes back.
- `optOutSchematicRecordings()` stops frame capture on the running session (it
  only stored the setting before). Opting back in doesn't lift a
  `pauseRecording()` that is still in effect.
- A message larger than the 500 KB batch cap is sent on its own instead of
  blocking the queue, and going to the background sends the whole queue, not
  one batch of it.
- `stop()` sends everything queued and ends with the session-end batch. A
  session end that never reached the server (offline, or the process died) is
  kept on disk and delivered to its own session on the next launch.
  `cancelSession()` also stops an upload already under way: nothing more is
  sent after the request in flight.
- `stopApplicationAndUploadData(timeoutMs)` waits for those uploads, up to the
  timeout, so they aren't cut off when the process exits.
- Sessions report the SDK's real version as `trackerVersion` (it was always
  `2.0.0`).
- Each session's upload, screenshot and frame-decode threads end with the
  session, once its last upload is out. Every session rotation (a long
  background, `startNewSession()`) used to leave about three idle threads
  behind for the life of the process.
- A session ended while its start request was still in flight (`stop()` or
  an overall opt-out just after `init`, or `startNewSession()` during the
  first round trip) no longer goes live once the request returns. It used to
  start capturing anyway and, on a rotation, keep capturing next to the new
  session with nothing left to stop it, ignoring `pauseRecording()`, the
  background and excluded screens.
- JitPack builds work again. Since 0.0.4 every one failed at signing (JitPack
  has no signing key), so the JitPack coordinate never resolved; JitPack now
  builds unsigned. Maven Central releases are still signed.

### Changed

- The README and the published POM now name the MIT license, matching the
  `LICENSE` file; they still said BSD-3-Clause.
- The README installs from Maven Central (`app.replayfy:android-sdk`), with
  JitPack (`com.github.replayfy:android-sdk`, same version) as the fallback.

## [0.0.1] - Unreleased

Initial public release.

### Added

- Session replay with automatic screen capture, tap/gesture capture, and
  screen-navigation tracking (Activities + androidx Fragments).
- Product analytics: `identify`, `track`, session/user properties, and session
  tags.
- Error monitoring: automatic crash capture plus `captureException` for handled
  errors, `reportBugEvent`, and a `log` bridge for third-party loggers.
- Performance vitals: cold start, frame drops, ANR, memory, and thermal state.
- Opt-in network capture via a drop-in OkHttp interceptor.
- Privacy & masking: per-view, per-class, and whole-screen occlusion, plus
  automatic password-field masking.
- One-call setup via `Replay.init(context, ReplayConfig(...))`; runtime
  configuration overrides through the dashboard.
