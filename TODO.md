# TODO

## #119 — Consolidate `LegacyCore` into `ReplayCore` (remove the duplicate engine)

**Status:** in progress · **Priority:** high (includes a correctness gap)

> **Done:**
> 1. GDPR opt-out gap closed — the live `ReplayCore` refuses to start while
>    opted out and stops on `optOutOverall(true)`.
> 2. Duplicate-handler dedup — `LegacyCore.autoBootstrap` no longer installs
>    its own lifecycle / tap / snapshot / perf / crash / NDK / console
>    handlers (they duplicated the live engine + chained a second
>    uncaught-exception handler; the legacy-only paths dropped to the inert
>    runtime anyway).
>
> **Remaining:**
> 1. Previous-process **JVM** crash recovery — the live handler
>    (`MobileCrashHandler`) catches new uncaught exceptions but doesn't drain
>    a disk record left by a prior process. (Native console + NDK-crash
>    capture, including NDK previous-process recovery, are now re-homed onto
>    the live `ReplayCore`.)
> 2. Re-home the ~25 no-op facade methods (industry-standard; our own design, not
>    a reference port).

### Problem
The SDK currently runs two engines in parallel:

- **`internal.mobile.ReplayCore`** — the LIVE engine (periodic frames +
  binary `/v1/mobile` protocol). Started by `Replay.init` →
  `ReplayCore.shared.start(...)`. Owns its own touch / screenshot / perf /
  crash capture.
- **`internal.LegacyCore`** — the legacy engine. Auto-bootstrapped by
  `ReplayContentProvider.onCreate` → `LegacyCore.autoBootstrap(...)`, but its
  `init()` is never called, so `config` stays null, no session ever starts,
  and every `push()` drops (`runtime == null`). It records nothing.

Two consequences:

1. **Duplicate handlers.** `LegacyCore.autoBootstrap` still installs its OWN
   `TapTracker`, `SnapshotCapture`, `PerfMetricsManager`, `CrashHandler` +
   `NativeCrashHandler`, and `ConsoleCapture` — running *alongside* the live
   `ReplayCore`'s collectors (e.g. two uncaught-exception handlers chained,
   two stdout/stderr interceptors, two tap trackers). Wasted work + a
   latent conflict.
2. **No-op public API.** ~25 public `Replay.*` methods still delegate to
   `LegacyCore` and are therefore inert today (see README "Coming soon").
   Notably **`optOutOverall` / opt-out is not enforced** on the live engine
   — a GDPR-compliance gap.

### Fix
- Stop `LegacyCore.autoBootstrap` from installing collectors the live
  `ReplayCore` already owns (dedupe crash / console / tap / perf).
- Re-home the ~25 facade methods onto `ReplayCore` (or drop / keep a thin
  `LegacyCore` piece per method — e.g. opt-out SharedPreferences
  persistence). Then delete the dead recording machinery in `LegacyCore`.

### Constraint (project standing rule #3)
Follow the reference open-source mobile SDK approach EXACTLY — do **not**
invent the re-homing. Ask the owner for the reference Android SDK source
before implementing.

### Verify
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :sdk:compileDebugKotlin :sdk:compileDebugAndroidTestKotlin
```
