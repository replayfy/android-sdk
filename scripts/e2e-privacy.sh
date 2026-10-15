#!/usr/bin/env bash
#
# Android end-to-end test for privacy masking in recorded frames.
#
# Drives example-app's PrivacyActivity on an emulator: types into the
# masked fields, opens the keyboard for a field low on the screen (the
# window pans, adjustPan), scrolls the WebView page, turns FLAG_SECURE on,
# then crashes so the session is flushed through /late. The session's frames
# archive is then checked pixel by pixel (scripts/assert-privacy.mjs) against
# the rects the activity logged.
#
# Requires: the local stack and API already running (in ~/Documents/injest-api:
# `docker compose up -d` then `yarn dev:api`), reachable at 10.0.2.2:4000 from
# the emulator; Node 20.17.0 via nvm; JDK 17; the Android SDK; macOS `sips`.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# ── config ────────────────────────────────────────────────────────────────
API="${REPLAY_API:-http://localhost:4000}"
PKG="com.replayfy.example"
LAUNCH="$PKG/.PrivacyActivity"
WID="${REPLAY_WID:-1}"
JWT_SECRET="${JWT_SECRET:-dev-only-jwt-secret-change-me}"
PG_CONTAINER="${PG_CONTAINER:-newproject-postgres-1}"
SECURE_AFTER="${SECURE_AFTER:-22}"   # s: FLAG_SECURE on
CRASH_AFTER="${CRASH_AFTER:-30}"     # s: crash → /late flush
LOGCAT_OUT="${LOGCAT_OUT:-/tmp/replay-privacy-logcat.txt}"

export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
# shellcheck disable=SC1091
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use 20.17.0 >/dev/null
NODE="$(command -v node)"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
[ -x "$ADB" ] || ADB="$(command -v adb)"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
step()  { printf '\n\033[1m▶ %s\033[0m\n' "$*"; }
fail()  { red "✗ $*"; exit 1; }

newest_session() { # echoes the newest android session publicId
  docker exec "$PG_CONTAINER" psql -U replay -d replay_platform -t -c \
    "select \"publicId\" from \"Session\" where platform='android' order by \"startedAt\" desc limit 1;" 2>/dev/null | tr -d ' \n'
}

# The latest logged screen rect of a view: "l t r b".
screen_rect() {
  "$ADB" logcat -d -v raw -s ReplayE2E 2>/dev/null | grep REPLAY_E2E_RECTS | tail -1 \
    | sed 's/.*REPLAY_E2E_RECTS //' \
    | "$NODE" -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const b=JSON.parse(s).screen['$1'];if(!b)process.exit(1);console.log(b.join(' '))})"
}
tap_view() { # $1=name
  local r; r="$(screen_rect "$1")" || fail "no on-screen rect for $1"
  set -- $r
  "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# ── preconditions ──────────────────────────────────────────────────────────
[ -n "$NODE" ] || fail "node not found (need Node 20.17.0 via nvm)"
[ -x "$ADB" ] || fail "adb not found — set ANDROID_SDK_ROOT"
command -v sips >/dev/null || fail "sips not found (macOS only)"
docker exec "$PG_CONTAINER" true 2>/dev/null || fail "Postgres container '$PG_CONTAINER' not running (docker compose up -d in ~/Documents/injest-api)"
curl -s -o /dev/null "$API/v1/mobile/start" || fail "API not reachable at $API (yarn dev:api in ~/Documents/injest-api)"

step "Boot emulator"
if ! "$ADB" devices | grep -qw device; then
  AVD="$("$EMU" -list-avds 2>/dev/null | head -1)"
  [ -n "$AVD" ] || fail "no AVD found — create one in Android Studio"
  echo "  booting AVD: $AVD"
  nohup "$EMU" -avd "$AVD" -no-snapshot-save -no-boot-anim >/tmp/emulator-privacy-e2e.log 2>&1 &
fi
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
green "  emulator ready"

step "Build + install the example app"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}" \
  ./gradlew :example-app:installDebug -q || fail "gradle install failed"
green "  installed $PKG"

step "Record the privacy screen"
"$ADB" shell am force-stop "$PKG" 2>/dev/null || true
"$ADB" logcat -c
STARTED="$(date +%s)"
"$ADB" shell am start -n "$LAUNCH" \
  --ez REPLAY_MASK_OVERLAY true \
  --ei REPLAY_SECURE_AFTER_SECS "$SECURE_AFTER" \
  --es REPLAY_AUTOCRASH jvm --ei REPLAY_CRASH_AFTER_SECS "$CRASH_AFTER" >/dev/null
sleep 4
echo "  typing a password"
tap_view password; sleep 1
"$ADB" shell input text "hunter2"; sleep 1
"$ADB" shell input keyevent 4      # hide the keyboard
sleep 1
echo "  opening the keyboard for the bottom card field (the window pans)"
tap_view bottomCard; sleep 2
"$ADB" shell input text "4111111111111111"; sleep 3
"$ADB" shell input keyevent 4
sleep 2
echo "  scrolling the WebView page"
r="$(screen_rect web)" || fail "no on-screen rect for the WebView"
set -- $r
cx=$(( ($1 + $3) / 2 ))
"$ADB" shell input swipe "$cx" $(( $4 - 40 )) "$cx" $(( $2 + 40 )) 600
sleep 2
"$ADB" shell input swipe "$cx" $(( $2 + 40 )) "$cx" $(( $4 - 40 )) 600
echo "  waiting for FLAG_SECURE (${SECURE_AFTER}s) and the crash (${CRASH_AFTER}s)…"
left=$(( STARTED + CRASH_AFTER + 6 - $(date +%s) ))
[ "$left" -gt 0 ] && sleep "$left"
"$ADB" logcat -d -v epoch -s ReplayE2E >"$LOGCAT_OUT"
SID="$(newest_session)"
[ -n "$SID" ] || fail "no android session created"
echo "  session: $SID   logcat: $LOGCAT_OUT"

step "Check the frames"
t=0
while [ "$t" -lt 30 ]; do
  API="$API" SID="$SID" WID="$WID" JWT_SECRET="$JWT_SECRET" LOGCAT="$LOGCAT_OUT" \
    "$NODE" "$ROOT/scripts/assert-privacy.mjs"
  rc=$?
  [ "$rc" -eq 0 ] && { green "\n✅ Android privacy e2e PASSED"; exit 0; }
  [ "$rc" -eq 1 ] && fail "privacy e2e — masks wrong in session $SID"
  sleep 2; t=$((t+2))
done
fail "session $SID never packed"
