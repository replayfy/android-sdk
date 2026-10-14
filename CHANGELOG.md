# Changelog

All notable changes to the Replayfy Android SDK are documented here. This
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
