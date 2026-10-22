#!/usr/bin/env bash
# AC 8 Play e2e walkthrough, Android emulator half (docs/play/plan.md § 3; roads flavour, roads dataset).
# Reproduces the command sequence of the 2026-10-21 run (docs/play/screens/README.md). Non-interactive where the app
# allows it: taps go through `uiautomator dump` + text/content-description lookup, so they survive layout shifts but
# not copy changes. Map pins have no accessibility node; the script opens the kraken sheet through Challenges ›
# Kraken week › Show on map and the treasure sheet through a coordinate tap (TREASURE_TAP), which needs a look.
#
#   scripts/play-e2e.sh                # everything, with screenshots into docs/play/screens/*-android.png
#   STEP=walks scripts/play-e2e.sh     # only the three replays (backend running, app set up, Play on)
#
# MODE=trails (Play trails edition v1.1, docs/superpowers/plans/2026-10-21-play-trails-v1-1.md § W4): the trails
# flavour (com.bainbridgetrails.app.dev, data/dist) at island time Sat 2026-10-31 10:00, three replayed trails
# (Hayano, Peter's, Grand Forest East; fallback walk 3 Main Loop) with a sync between walks, then Logbook /
# Challenges / Me strip + sheet shots (light, dark, font 2.0), the roads APK with Play on (no strip), and the
# assertions of plan § W4 printed from D1 and `GET /v1/play/me`. Screenshots and the run record:
# docs/play/trails/screens/. Steps: all | backend | setup | walks | after | a11y | roads | assert.
#
#   MODE=trails scripts/play-e2e.sh
#   MODE=trails STEP=walks scripts/play-e2e.sh
#   MODE=trails STEP=walks WALK3=trail-main-loop-trail scripts/play-e2e.sh   # fallback trail for walk 3
#
# Needs: a running emulator (ANDROID_SERIAL, default emulator-5554, API 37), wrangler dev from backend/ on :8787 started
# with ALLOW_DEV_AUTH=true and DEV_CLOCK_OFFSET_MS (plan § 3; this script starts it unless BACKEND=external).
# Positions are simulated on public roads/trails from the dataset geometry; nothing here reads real tracks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
MODE="${MODE:-roads}"
ACT=com.bainbridgetrails.app.MainActivity
WORK="${WORK:-$(mktemp -d -t play-e2e)}"
STEP="${STEP:-all}"
case "$MODE" in
  roads)
    PKG=com.bainbridgetrails.roads.dev
    SHOTS="$ROOT/docs/play/screens"
    DIST="$ROOT/data/roads/dist"
    APK="$ROOT/app/androidApp/build/outputs/apk/roads/debug/androidApp-roads-debug.apk"
    GRADLE_TASK=:androidApp:assembleRoadsDebug
    PLAY_CLOCK=2026-10-29T14:00                  # island time (Thu)
    DEV_CLOCK_UTC=2026-10-29T21:00:00Z
    ;;
  trails)
    PKG=com.bainbridgetrails.app.dev
    SHOTS="$ROOT/docs/play/trails/screens"
    DIST="$ROOT/data/dist"
    APK="$ROOT/app/androidApp/build/outputs/apk/trails/debug/androidApp-trails-debug.apk"
    GRADLE_TASK=:androidApp:assembleTrailsDebug
    PLAY_CLOCK=2026-10-31T10:00                  # island time (Sat; week 2026-10-26; ch-climb + ch-parkhop open)
    DEV_CLOCK_UTC=2026-10-31T17:00:00Z
    WALK1="${WALK1:-trail-hayano-trail}"          # Moritani Preserve, one-trail park (402 m)
    WALK2="${WALK2:-trail-peters-trail}"          # Gazzam Lake Preserve, high ground (1,110 m)
    WALK3="${WALK3:-trail-grand-forest-east-trail}"  # Grand Forest, high ground (1,778 m); fallback trail-main-loop-trail
    ;;
  *) echo "MODE must be roads or trails" >&2; exit 2 ;;
esac
ROADS_APK="$ROOT/app/androidApp/build/outputs/apk/roads/debug/androidApp-roads-debug.apk"
# shellcheck disable=SC1091
source "$ROOT/docs/play/e2e-ids.env"
mkdir -p "$SHOTS"

log() { printf '\n== %s\n' "$*"; }
shot() { adb exec-out screencap -p > "$SHOTS/$1-android.png"; echo "   shot $1-android.png"; }

# ui_find <text|desc|class:EditText> [index]: centre of the first node whose text or content-desc matches (exact
# matches first); `class:<name>` matches the widget class instead.
ui_find() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$WORK/ui.xml"
  python3 -I - "$WORK/ui.xml" "$1" "${2:-0}" <<'PY'
import re, sys
x = open(sys.argv[1]).read(); q = sys.argv[2].lower(); k = int(sys.argv[3]); hits = []
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n).group(1).replace('&amp;', '&')
    d = re.search(r'content-desc="([^"]*)"', n).group(1).replace('&amp;', '&')
    c = re.search(r' class="([^"]*)"', n).group(1).lower()
    if q in t.lower() or q in d.lower() or (q.startswith("class:") and q[6:] in c):
        a = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]*)"', n).group(1))))
        hits.append((0 if q in (t.lower(), d.lower()) else 1, (a[0] + a[2]) // 2, (a[1] + a[3]) // 2))
hits.sort(key=lambda h: h[0])
print(f"{hits[k][1]} {hits[k][2]}" if len(hits) > k else "")
PY
}
# ui_text: every text/content-desc on screen, one per line (for the trails assertions and the run record).
ui_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | python3 -I -c '
import re, sys
for n in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    for a in ("text", "content-desc"):
        v = re.search(rf" {a}=\"([^\"]*)\"", n.group(0)).group(1).replace("&amp;", "&").replace("&#10;", " / ")
        if v: print(v)'
}
tap() {
  local xy; xy="$(ui_find "$@")"
  [[ -n "$xy" ]] || { echo "not on screen: $1" >&2; return 1; }
  # shellcheck disable=SC2086
  adb shell input tap $xy
}
wait_for() { # wait_for <text> [seconds]
  for _ in $(seq 1 "${2:-30}"); do [[ -n "$(ui_find "$1")" ]] && return 0; sleep 1; done
  echo "timed out waiting for: $1" >&2; return 1
}
launch_play() {
  adb shell am force-stop "$PKG"
  if [[ "$MODE" == roads ]]; then
    adb shell am start -W -n "$PKG/$ACT" --es playClock 2026-10-29T14:00 --es playTide low >/dev/null
  else
    adb shell am start -W -n "$PKG/$ACT" --es playClock "$PLAY_CLOCK" >/dev/null
  fi
}
finish_walk() {
  tap "Finish walk"; wait_for "Keep walking" 10
  tap "Finish"            # the dialog's confirm button (exact match wins over "Finish this walk?")
  sleep 6
}
replay() { # replay <feature-id>: Start walk is already showing
  tap "Start walk"; sleep 2
  [[ "$MODE" == trails && -n "$(ui_find 'Ready when you are')" ]] && { tap "Start"; sleep 2; }   # trails: Record › Start
  [[ -n "$(ui_find 'Physical activity')" ]] && { tap "Continue"; sleep 1; tap "Allow"; sleep 2; }
  DIST="$DIST" "$ROOT/scripts/emu-route.sh" "$1" 2
}
sync_done() { # Me › sync status shows the walk backed up
  tap "Me"; sleep 2; wait_for "Progress backed up" 60 || true
  # trails: also wait for the walk's PUT /v1/coverage in the wrangler log (Me shows "Progress backed up" only briefly)
  if [[ "$MODE" == trails && -n "${1:-}" && -f "$WORK/wrangler.log" ]]; then
    for _ in $(seq 1 90); do [[ "$(grep -c 'PUT /v1/coverage 200' "$WORK/wrangler.log")" -gt "$1" ]] && { echo "   synced (PUT /v1/coverage)"; return 0; }; sleep 1; done
    echo "   WARN: no PUT /v1/coverage seen after this walk" >&2
  fi
}
# --- trails-mode helpers -----------------------------------------------------------------------------------------
backend_local() { # write the debug drawer's backend choice (its Apply & Restart races the async save, F3)
  adb shell am force-stop "$PKG"
  printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' \
    '    <string name="backend_override">local</string>' '</map>' |
    adb shell "run-as $PKG sh -c 'cat > shared_prefs/com.sloopworks.debugdrawer.xml'"
}
play_switch() { # Settings is open: toggle the Play switch (right of its description)
  adb shell input swipe 640 2000 640 1200 300; sleep 1
  local xy y; xy="$(ui_find 'Add treasures, riddles')"; y="${xy#* }"
  adb shell input tap 1150 "$y"
}
dev_signin() { tap "Me"; sleep 2; tap "Sign in"; sleep 2; tap "Dev sign-in"; wait_for "Progress backed up" 20 || true; adb shell input keyevent BACK; sleep 1; }
open_trail() { # open_trail <search text> <row text>: Trails › search › the trail's row → TrailDetail with Start walk
  tap "Trails"; sleep 2
  for _ in 1 2 3; do [[ -z "$(ui_find 'class:EditText')" && -n "$(ui_find 'Back')" ]] && { tap "Back"; sleep 1.5; } || break; done   # the tab restores its stack
  tap "Got it" || true; sleep 1
  tap "class:EditText"; sleep 1
  adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A; adb shell input keyevent KEYCODE_DEL   # clear the last query
  adb shell input text "$1"; sleep 2
  tap "$2"; sleep 3
  wait_for "Start walk" 10
}
walk_trail() { # walk_trail <trail-id> <search text> <row text> <shot name>: open, replay, Finish, shots, sync
  local before; before="$(grep -c 'PUT /v1/coverage 200' "$WORK/wrangler.log" 2>/dev/null || echo 0)"
  open_trail "$2" "$3"; replay "$1"
  finish_walk
  shot "$4-celebrate"; ui_text > "$WORK/$4-celebrate.txt"           # "Trail complete …" / new rank overlay (when any)
  tap "Continue" || true; sleep 3
  shot "$4"; ui_text > "$WORK/$4.txt"; echo "   results text: $WORK/$4.txt"
  adb shell input swipe 640 2300 640 900 400; sleep 1.5; shot "$4-2"; ui_text >> "$WORK/$4.txt"
  tap "Done" || adb shell input keyevent BACK; sleep 2
  sync_done "$before"
}
challenges_open() { tap "challenges this week" || tap "challenge this week"; sleep 2; }
me_strip() { # Me scrolled so the Trail milestones strip is in view
  tap "Me"; sleep 2; adb shell input swipe 640 2000 640 900 300; sleep 1; wait_for "Trail milestones" 10 || true
}
dev_token() { # the app's own session (no_backup/session.json) → bearer for the same user as the emulator
  adb shell "run-as $PKG cat no_backup/session.json" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

# `MODE=trails source scripts/play-e2e.sh` (bash) only defines the helpers above for ad-hoc taps and shots.
[[ "${BASH_SOURCE[0]}" != "$0" ]] && return 0

# --- 1. backend ----------------------------------------------------------------------------------------------------
if [[ "$STEP" == all && "${BACKEND:-local}" != external && "$MODE" == roads ]]; then
  log "backend: wrangler dev with the e2e dev clock"
  (cd "$ROOT/backend" && npm install --silent && cp .dev.vars.example .dev.vars &&
    node -e 'console.log("DEV_CLOCK_OFFSET_MS="+(Date.parse("2026-10-29T21:00:00Z")-Date.now()))' >> .dev.vars)
  (cd "$ROOT/backend" && npm run dev > "$WORK/wrangler.log" 2>&1 &)
  for _ in $(seq 1 60); do curl -sf localhost:8787/v1/health >/dev/null && break; sleep 1; done
  curl -sf localhost:8787/v1/health; echo
  curl -sf -o /dev/null -w 'play/content %{http_code} %{size_download} B\n' localhost:8787/v1/play/content
  echo "   wrangler log: $WORK/wrangler.log"
fi
if [[ ( "$STEP" == all || "$STEP" == backend ) && "${BACKEND:-local}" != external && "$MODE" == trails ]]; then
  log "backend (trails): fresh local D1, wrangler dev with the e2e dev clock $DEV_CLOCK_UTC"
  pkill -f 'wrangler dev' >/dev/null 2>&1 || true; sleep 2
  [[ -d "$ROOT/backend/.wrangler/state" ]] && mv "$ROOT/backend/.wrangler/state" "$WORK/wrangler-state-before"
  (cd "$ROOT/backend" && npm install --silent && cp .dev.vars.example .dev.vars &&
    { grep -q '^ALLOW_DEV_AUTH=true' .dev.vars || echo 'ALLOW_DEV_AUTH=true' >> .dev.vars; } &&
    node -e 'console.log("DEV_CLOCK_OFFSET_MS="+(Date.parse(process.argv[1])-Date.now()))' "$DEV_CLOCK_UTC" >> .dev.vars)
  (cd "$ROOT/backend" && npm run dev > "$WORK/wrangler.log" 2>&1 &)
  for _ in $(seq 1 90); do curl -sf localhost:8787/v1/health >/dev/null && break; sleep 1; done
  curl -sf localhost:8787/v1/health; echo
  curl -sf -o /dev/null -w 'play/content %{http_code} %{size_download} B\n' localhost:8787/v1/play/content
  (cd "$ROOT/backend" && npx wrangler d1 execute bainbridge-trails --local --command \
    "SELECT name FROM d1_migrations ORDER BY id" 2>/dev/null | grep -E '000[1-7]' || true)
  echo "   wrangler log: $WORK/wrangler.log"
fi

# --- 2. build, fresh install, pure baseline ------------------------------------------------------------------------
if [[ "$STEP" == all && "$MODE" == roads ]]; then
  log "build + fresh install (roads debug)"
  (cd "$ROOT/app" && ./gradlew -q :androidApp:assembleRoadsDebug)
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install -r "$ROOT/app/androidApp/build/outputs/apk/roads/debug/androidApp-roads-debug.apk"
  adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

  log "pure baseline (Play off)"
  adb emu geo fix -122.5200 47.6260 >/dev/null
  adb shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 5
  tap "Skip" || true; sleep 4
  shot pure-map
  tap "Roads"; sleep 3; shot pure-list
  tap "Me"; sleep 3; shot pure-me
  tap "Settings"; sleep 2; shot pure-settings

  log "backend switch → local wrangler (drawer's Apply & Restart races its async save; write the drawer prefs instead)"
  adb shell am force-stop "$PKG"
  printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' \
    '    <string name="backend_override">local</string>' '</map>' |
    adb shell "run-as $PKG sh -c 'cat > shared_prefs/com.sloopworks.debugdrawer.xml'"
  adb logcat -c
  launch_play; sleep 4
  adb logcat -d | grep -m1 'debug tools installed' || true     # expect: backend http://10.0.2.2:8787
  [[ -n "$(ui_find 'Allow')" ]] && tap "Allow"                  # ACCESS_LOCAL_NETWORK (Android 17)
  sleep 2

  log "dev sign-in"
  tap "Me"; sleep 2; tap "Sign in"; sleep 2; tap "Dev sign-in"; wait_for "Progress backed up" 20 || true
  adb shell input keyevent BACK; sleep 1

  log "Settings › Play on › Start playing"
  tap "Settings"; sleep 2
  adb shell input swipe 640 2000 640 1200 300; sleep 1
  shot e2e-01-settings-play
  xy="$(ui_find 'Add treasures, riddles')"; y="${xy#* }"
  adb shell input tap 1150 "$y"                                  # the Play switch, right of its description
  wait_for "Start playing" 10; sleep 1; shot e2e-02-intro
  tap "Start playing"; sleep 1.2; shot e2e-03-wake
  sleep 6; shot e2e-04-map-play
fi
if [[ ( "$STEP" == all || "$STEP" == setup ) && "$MODE" == trails ]]; then
  log "fresh install (trails debug: $APK)"
  [[ -f "$APK" && -z "${BUILD:-}" ]] || (cd "$ROOT/app" && ./gradlew -q "$GRADLE_TASK")
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install -r "$APK"
  adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  adb emu geo fix -122.5200 47.6260 >/dev/null
  adb shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 5
  tap "Skip" || true; sleep 3

  log "backend switch → local wrangler (drawer prefs), Play clock $PLAY_CLOCK"
  backend_local
  adb logcat -c
  launch_play; sleep 4
  adb logcat -d | grep -m1 'debug tools installed' || true     # expect: backend http://10.0.2.2:8787
  [[ -n "$(ui_find 'Allow')" ]] && tap "Allow"                  # ACCESS_LOCAL_NETWORK (Android 17)
  sleep 2

  log "dev sign-in"
  dev_signin

  log "Settings › Play on › Start playing"
  tap "Settings"; sleep 2
  adb shell input swipe 640 2000 640 1200 300; sleep 1
  shot t-01-settings-play
  xy="$(ui_find 'Add treasures, riddles')"; y="${xy#* }"
  adb shell input tap 1150 "$y"                                  # the Play switch, right of its description
  wait_for "Start playing" 10; sleep 1
  tap "Start playing"; sleep 8
fi

# --- 3. the three replays --------------------------------------------------------------------------------------------
if [[ ( "$STEP" == all || "$STEP" == walks ) && "$MODE" == roads ]]; then
  log "walk 1: treasure ($E2E_TREASURE_ROAD, expect $E2E_TREASURE_ROAD_KEY)"
  tap "Map"; sleep 2
  # The treasure sheet: centre on the road and tap its pin (no a11y node for map pins). TREASURE_TAP="x y" after a look.
  coords="$(python3 -I - "$DIST" "$E2E_TREASURE_ROAD" <<'PY'
import json, sys
d = sys.argv[1]; r = {t["id"]: t for t in json.load(open(f"{d}/roads.json"))["roads"]}
s = {x["id"]: x["coords"] for x in json.load(open(f"{d}/coverage_segments.json"))["segments"]}
c = s[r[sys.argv[2]]["segment_ids"][0]][0]; print(c[0], c[1])
PY
)"
  # shellcheck disable=SC2086
  adb emu geo fix $coords >/dev/null; sleep 2; tap "Show my location"; sleep 4
  if [[ -n "${TREASURE_TAP:-}" ]]; then
    # shellcheck disable=SC2086
    adb shell input tap $TREASURE_TAP; sleep 2; shot e2e-05-treasure-sheet; tap "Walk there"; sleep 3
  else
    tap "Find a road"; sleep 1; adb shell input text "Morgan"; sleep 2; adb shell input keyevent ENTER; sleep 2
  fi
  replay "$E2E_TREASURE_ROAD"; shot e2e-06-record-find
  finish_walk; shot e2e-07-results-treasure
  tap "Continue" || true; sleep 2; tap "Done" || true; sync_done

  log "walk 2: kraken (road-manitou-beach-drive-northeast)"
  tap "Map"; sleep 2; tap "challenge this week"; sleep 2; tap "Kraken week"; sleep 2; tap "Show on map"; sleep 3
  shot e2e-08-kraken-sheet
  tap "Walk there" 0; sleep 3
  DIST="$DIST" "$ROOT/scripts/emu-route.sh" road-manitou-beach-drive-northeast 2 900 &   # first 15 min: mid-walk shot
  tap "Start walk"; sleep 120; shot e2e-09-record-kraken; wait
  finish_walk; shot e2e-10-results-kraken
  tap "Continue" || true; sleep 2; tap "Done" || true; sync_done

  log "walk 3: riddle-frog (armchair solve on the map, then road-northeast-spargur-loop-road)"
  # Armchair solve: Map › riddle pin › Pick on the map › Frog Rock. No a11y node for the riddle pin: done by hand.
  echo "   MANUAL: open the riddle pin, Pick on the map, choose Frog Rock; then press Enter"
  [[ -t 0 ]] && read -r _ || true
  shot e2e-12-riddle-solved
  tap "Find a road"; sleep 1; adb shell input text "Spargur"; sleep 2; adb shell input keyevent ENTER; sleep 2
  replay road-northeast-spargur-loop-road
  finish_walk; shot e2e-13-results-riddle
  tap "Continue" || true; sleep 2; tap "Done" || true; sync_done
fi
if [[ ( "$STEP" == all || "$STEP" == walks ) && "$MODE" == trails ]]; then
  # Walk 1 (Hayano, Moritani Preserve: trail +100, First steps +50, Every trail in a park +150; + egg-strawberry per
  # the agenda), walk 2 (Peter's: +100, High ground +50, First steps +50, Climb "328 ft" +50, Walking day +15),
  # walk 3 (Grand Forest East: +100, High ground +50, First steps +50, Milestone "1 mi" +50, Uphill week +100,
  # Park hop +100; + treasure #01 per the agenda). Expected rows: plan § W4 table.
  WALKS="${WALKS:-1 2 3}"                                           # WALKS="2 3" resumes after walk 1
  [[ " $WALKS " == *" 1 "* ]] && { log "walk 1: $WALK1"; walk_trail "$WALK1" "Hayano" "Hayano Trail" t-02-results-walk1; }
  [[ " $WALKS " == *" 2 "* ]] && { log "walk 2: $WALK2"; walk_trail "$WALK2" "Peter" "Peter's Trail" t-03-results-walk2; }
  case "$WALK3" in
    trail-grand-forest-east-trail) q='Grand%sForest%sEast'; row="Grand Forest East Trail" ;;
    trail-main-loop-trail) q='Main%sLoop'; row="Main Loop Trail" ;;
    *) q="${WALK3#trail-}"; q="${q%-trail}"; q="${q//-/%s}"; row="${q//%s/ }" ;;
  esac
  [[ " $WALKS " == *" 3 "* ]] && { log "walk 3: $WALK3"; walk_trail "$WALK3" "$q" "$row" t-04-results-walk3; }
  true
fi

# --- 4. challenge, Logbook, Me, Play off -----------------------------------------------------------------------------
if [[ ( "$STEP" == all || "$STEP" == after ) && "$MODE" == roads ]]; then
  log "Challenges, Logbook, Me, Play off"
  tap "Map"; sleep 2; tap "challenge this week"; sleep 2; shot e2e-14-challenges
  tap "Kraken week"; sleep 2; shot e2e-15-challenge-krakenweek
  adb shell input keyevent BACK; sleep 1; adb shell input keyevent BACK; sleep 1
  tap "Opens your logbook"; sleep 2; shot e2e-16-logbook; adb shell input keyevent BACK; sleep 1
  tap "Me"; sleep 2; adb shell input swipe 640 2000 640 1000 300; sleep 1; shot e2e-17-me-playcard
  tap "Settings"; sleep 2; adb shell input swipe 640 2000 640 1200 300; sleep 1
  xy="$(ui_find 'Add treasures, riddles')"; y="${xy#* }"; adb shell input tap 1150 "$y"; sleep 2
  [[ -n "$(ui_find 'Turn off')" ]] && tap "Turn off"
  adb shell input keyevent BACK; sleep 1; tap "Map"; sleep 3; shot e2e-18-play-off-map
fi
if [[ ( "$STEP" == all || "$STEP" == after ) && "$MODE" == trails ]]; then
  log "Logbook, Challenges, Me strip + sheet (light)"
  tap "Map"; sleep 2; tap "Opens your logbook"; sleep 3; shot t-05-logbook; ui_text > "$WORK/t-05-logbook.txt"
  adb shell input keyevent BACK; sleep 1
  challenges_open; shot t-06-challenges; ui_text > "$WORK/t-06-challenges.txt"
  adb shell input keyevent BACK; sleep 1
  me_strip; shot t-07-me-strip; ui_text > "$WORK/t-07-me-strip.txt"
  tap "All"; sleep 2; shot t-08-me-sheet-light; ui_text > "$WORK/t-08-me-sheet.txt"
  adb shell input swipe 640 2200 640 800 300; sleep 1; shot t-08-me-sheet-light-2; ui_text >> "$WORK/t-08-me-sheet.txt"
  adb shell input keyevent BACK; sleep 1
fi
if [[ ( "$STEP" == all || "$STEP" == a11y ) && "$MODE" == trails ]]; then
  log "dark theme (strip + sheet), font scale 2.0 (strip); restored after"
  adb shell cmd uimode night yes; sleep 2; launch_play; sleep 5
  me_strip; shot t-07-me-strip-dark; tap "All"; sleep 2; shot t-08-me-sheet-dark
  adb shell input keyevent BACK; sleep 1
  adb shell cmd uimode night no; sleep 2
  adb shell settings put system font_scale 2.0; sleep 2; launch_play; sleep 5
  tap "Me"; sleep 2; adb shell input swipe 640 2200 640 600 300; sleep 1; wait_for "Trail milestones" 10 || true
  shot t-09-me-font2; ui_text > "$WORK/t-09-me-font2.txt"
  tap "All" && { sleep 2; shot t-09-me-font2-sheet; adb shell input keyevent BACK; } || true
  adb shell settings put system font_scale 1.0; sleep 2; launch_play; sleep 3
fi
if [[ ( "$STEP" == all || "$STEP" == roads ) && "$MODE" == trails ]]; then
  log "roads APK, Play on: Me has no strip (UX-1) → t-10-roads-me"
  [[ -f "$ROADS_APK" && -z "${BUILD:-}" ]] || (cd "$ROOT/app" && ./gradlew -q :androidApp:assembleRoadsDebug)
  PKG=com.bainbridgetrails.roads.dev
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install -r "$ROADS_APK"
  adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  adb shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 5; tap "Skip" || true; sleep 3
  backend_local; launch_play; sleep 4
  [[ -n "$(ui_find 'Allow')" ]] && tap "Allow"; sleep 2
  dev_signin
  tap "Settings"; sleep 2; play_switch; wait_for "Start playing" 10; sleep 1; tap "Start playing"; sleep 12
  launch_play; sleep 5                                            # back on the map with Play on, then Me
  tap "Me"; sleep 2; adb shell input swipe 640 2000 640 900 300; sleep 1; shot t-10-roads-me; ui_text > "$WORK/t-10-roads-me.txt"
  [[ -n "$(ui_find 'marks')" ]] && echo "   Play card present on roads Me" || echo "   WARN: no Play card on roads Me (Play off?)"
  [[ -z "$(ui_find 'Trail milestones')" ]] && echo "   PASS: no 'Trail milestones' on roads Me" || echo "   FAIL: 'Trail milestones' on roads Me"
  PKG=com.bainbridgetrails.app.dev
fi
if [[ ( "$STEP" == all || "$STEP" == assert ) && "$MODE" == trails ]]; then
  log "assertions (plan § W4): D1 play_totals, GET /v1/play/me, GET /v1/coverage"
  TOKEN="${TOKEN:-$(dev_token)}"
  (cd "$ROOT/backend" && npx wrangler d1 execute bainbridge-trails --local --command \
    "SELECT total, trail_marks, found_marks FROM play_totals WHERE dataset='trails'" 2>/dev/null | tee "$WORK/play_totals.txt")
  (cd "$ROOT/backend" && npx wrangler d1 execute bainbridge-trails --local --command \
    "SELECT dataset, occ, kind, marks FROM play_claims ORDER BY occ" 2>/dev/null | tee "$WORK/play_claims.txt")
  curl -sf -H "Authorization: Bearer $TOKEN" 'localhost:8787/v1/play/me?dataset=trails' | tee "$WORK/play-me.json"; echo
  curl -sf -H "Authorization: Bearer $TOKEN" 'localhost:8787/v1/play/challenges?dataset=trails' > "$WORK/play-challenges.json" || true
  curl -sf -H "Authorization: Bearer $TOKEN" 'localhost:8787/v1/coverage?dataset=trails' | tee "$WORK/coverage.json"; echo
  python3 -I - "$WORK/play-me.json" "$WORK/coverage.json" <<'PY'
import json, sys
me = json.load(open(sys.argv[1])); cov = json.load(open(sys.argv[2]))
want = {"climb": 1, "high_ground": 2, "milestone": 1, "park_first": 3, "park_complete": 1, "island": 0, "challenge": 2}
pk = me["perKind"]
for k, v in want.items():
    ok = pk.get(k) == v
    note = ""
    if k == "challenge" and pk.get(k) == 3 and any(o.startswith("ch-sweep@") for o in me["occ"]):
        ok, note = True, " (plan's 2 + ch-sweep claimed at 0 marks under the daily cap; trails/screens/README.md F1)"
    print(f"   {'PASS' if ok else 'FAIL'}: perKind.{k} = {pk.get(k)} (want {v}){note}")
paid = me.get("paid", [])
print(f"   {'PASS' if len(paid) == 8 else 'FAIL'}: paid has {len(paid)} keys (want 8): {paid}")
print(f"   occ: {me['occ']}")
m = cov.get("metrics", cov)
mi = int(m["coveredM"] / 1609.344 * 10) / 10
print(f"   coveredM {m['coveredM']} → Me strip Trail miles so-far {mi} mi (floor one decimal); server total {me['total']}")
PY
  echo "   evidence dir: $WORK"
fi

log "done; wrangler log (if started here): $WORK/wrangler.log — check § 3.1–3.8 by hand (docs/play/screens/README.md)"
