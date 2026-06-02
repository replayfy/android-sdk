#!/usr/bin/env bash
#
# Android end-to-end test for the mobile FRAMES pipeline.
#
# The reference implementation has no automated frames test — it validates
# mobile capture by running the sample app and watching the session play in
# the dashboard. This scripts exactly that, driving the tic-tac-toe example
# on an emulator and asserting our disk-append → pack-once → serve-direct
# pipeline, across BOTH pack triggers:
#
#   Pass A — /late pack:   let the demo run to its simulated crash. The crash
#            handler flushes blocking via /v1/mobile/late, which packs the
#            frames archive immediately (a crashed session stays replayable).
#   Pass B — sweep backstop: force-stop mid-game (no /late) so packing can
#            only come from the backend's idle-sweep "ender".
#
# Both passes assert GET /frames → a DIRECT object-store .gz, fetched with a
# browser Origin → 200 + ACAO, gunzipping to a positive frame count.
#
# Requires: a host API (ingest-api) reachable at 10.0.2.2:4000 from the
# emulator, Docker Postgres for the session lookup, JDK 17, Android SDK.
# If REPLAY_API_DIR is set (default ~/Documents/New project) the script
# (re)starts the API with short pack windows so Pass B is fast, and restores
# the defaults on exit.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# ── config ────────────────────────────────────────────────────────────────
API="${REPLAY_API:-http://localhost:4000}"
API_HOST="${REPLAY_API_HOST:-localhost:4000}"
PKG="com.replayfy.example"
LAUNCH="$PKG/.MenuActivity"
WID="${REPLAY_WID:-1}"
JWT_SECRET="${JWT_SECRET:-dev-only-jwt-secret-change-me}"
PG_CONTAINER="${PG_CONTAINER:-newproject-postgres-1}"
REPLAY_API_DIR="${REPLAY_API_DIR:-$HOME/Documents/New project}"
TEST_IDLE_MS="${TEST_IDLE_MS:-8000}"      # Pass B: idle ⇒ ended
TEST_SWEEP_MS="${TEST_SWEEP_MS:-8000}"    # Pass B: sweep cadence
NODE="$(command -v node)"

# Locate Android SDK tools.
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
[ -x "$ADB" ] || ADB="$(command -v adb)"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
step()  { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
fail()  { red "✗ $*"; exit 1; }

API_PID=""
restart_api() { # $1=idleMs $2=sweepMs ; only when REPLAY_API_DIR exists
  [ -d "$REPLAY_API_DIR/apps/ingest-api/dist" ] || { echo "  (REPLAY_API_DIR not a built api — leaving API as-is)"; return 0; }
  lsof -nP -iTCP:4000 -sTCP:LISTEN -t 2>/dev/null | xargs -r kill 2>/dev/null; sleep 2
  lsof -nP -iTCP:4000 -sTCP:LISTEN -t 2>/dev/null | xargs -r kill -9 2>/dev/null; sleep 1
  ( cd "$REPLAY_API_DIR" && set -a && . ./.env && set +a \
    && FRAMES_IDLE_MS="$1" FRAMES_SWEEP_MS="$2" nohup "$NODE" apps/ingest-api/dist/bootstrap.js >/tmp/replay-api-e2e.log 2>&1 & )
  for _ in $(seq 1 30); do
    curl -s -o /dev/null -w '%{http_code}' "$API/v1/mobile/start" 2>/dev/null | grep -qE '40|20' && return 0
    sleep 1
  done
  fail "API did not come up"
}
restore_api() { [ -d "$REPLAY_API_DIR/apps/ingest-api/dist" ] && restart_api 300000 60000 >/dev/null 2>&1 || true; }
trap restore_api EXIT

newest_session() { # echoes the newest android session publicId
  docker exec "$PG_CONTAINER" psql -U replay -d replay_platform -t -c \
    "select \"publicId\" from \"Session\" where platform='android' order by \"startedAt\" desc limit 1;" 2>/dev/null | tr -d ' \n'
}
assert_frames() { # $1=sid $2=expectMin  → 0 packed+valid, 2 not packed, 1 invalid
  API="$API" SID="$1" WID="$WID" JWT_SECRET="$JWT_SECRET" EXPECT_MIN="$2" \
    API_HOST="$API_HOST" "$NODE" "$ROOT/scripts/assert-frames.mjs"
}
poll_packed() { # $1=sid $2=expectMin $3=timeoutS  → waits until packed
  local t=0
  while [ "$t" -lt "$3" ]; do
    assert_frames "$1" "$2" && return 0
    [ $? -eq 1 ] && return 1   # invalid (proxied / bad archive) — hard fail
    sleep 2; t=$((t+2))
  done
  return 2
}

# ── preconditions ──────────────────────────────────────────────────────────
[ -n "$NODE" ] || fail "node not found (need Node 20 for the assertions)"
[ -x "$ADB" ] || fail "adb not found — set ANDROID_SDK_ROOT"
docker exec "$PG_CONTAINER" true 2>/dev/null || fail "Postgres container '$PG_CONTAINER' not running"

step "Boot emulator + API"
if ! "$ADB" devices | grep -qw device; then
  AVD="$("$EMU" -list-avds 2>/dev/null | head -1)"
  [ -n "$AVD" ] || fail "no AVD found — create one in Android Studio"
  echo "  booting AVD: $AVD"
  nohup "$EMU" -avd "$AVD" -no-snapshot-save -no-boot-anim >/tmp/emulator-e2e.log 2>&1 &
fi
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
green "  emulator ready"

step "Build + install the example app"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}" \
  ./gradlew :example-app:installDebug -q || fail "gradle install failed"
green "  installed $PKG"

# ════════════════════════════════════════════════════════════════════════════
# PASS A — /late pack (run to the simulated crash)
# Long sweep window so packing can ONLY be the crash's /late flush.
# ════════════════════════════════════════════════════════════════════════════
step "PASS A — /late pack (full demo → crash flush)"
restart_api 300000 600000   # idle 5m / sweep 10m ⇒ no sweep during the test
"$ADB" shell am force-stop "$PKG" 2>/dev/null || true
"$ADB" shell am start -n "$LAUNCH" >/dev/null
echo "  recording… (menu → 3 rounds → simulated crash ~35s)"
sleep 42                     # let it play through and crash (crash → /late)
SID_A="$(newest_session)"
[ -n "$SID_A" ] || fail "no android session created"
echo "  session: $SID_A"
# The crash's /late must have packed it; with the 10m sweep, a pack here can
# only be /late. Allow a few seconds for the blocking flush to land.
if poll_packed "$SID_A" 5 12; then
  green "PASS A ✓ — crashed session packed via /late, served direct from object store"
else
  fail "PASS A — session $SID_A not packed via /late (got code $?)"
fi

# ════════════════════════════════════════════════════════════════════════════
# PASS B — sweep backstop (hard kill mid-session, no /late)
# Short windows so the idle sweep packs quickly.
# ════════════════════════════════════════════════════════════════════════════
step "PASS B — sweep backstop (force-stop mid-game)"
restart_api "$TEST_IDLE_MS" "$TEST_SWEEP_MS"
"$ADB" shell am start -n "$LAUNCH" >/dev/null
echo "  recording ~15s then HARD KILL (am force-stop, no /late)…"
sleep 15
SID_B="$(newest_session)"
[ -n "$SID_B" ] || fail "no android session for pass B"
[ "$SID_B" != "$SID_A" ] || fail "pass B reused pass A's session ($SID_B)"
echo "  session: $SID_B"
"$ADB" shell am force-stop "$PKG"          # kill — no terminate beacon
# Immediately after the kill the pure read path must report NOT packed.
assert_frames "$SID_B" 1; rc=$?
[ "$rc" -eq 2 ] && green "  read path is pure: /frames null right after kill (no /late, not yet swept)" \
                || { [ "$rc" -eq 0 ] && echo "  (already packed — sweep beat the check; fine)"; }
echo "  waiting for the idle-sweep backstop (idle ${TEST_IDLE_MS}ms + sweep ${TEST_SWEEP_MS}ms)…"
if poll_packed "$SID_B" 1 45; then
  green "PASS B ✓ — hard-killed session packed by the idle-sweep backstop, served direct"
else
  fail "PASS B — session $SID_B never packed by the sweep (code $?)"
fi

"$ADB" shell am force-stop "$PKG" 2>/dev/null || true
green "\n✅ Android frames e2e PASSED — both /late and sweep triggers verified."
