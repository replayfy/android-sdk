# Changelog

All notable changes to the Replayfy Android SDK are documented here. This
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
</content>
