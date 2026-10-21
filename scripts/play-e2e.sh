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
# Needs: a running emulator (ANDROID_SERIAL, default emulator-5554, API 37), wrangler dev from backend/ on :8787 started
# with ALLOW_DEV_AUTH=true and DEV_CLOCK_OFFSET_MS (plan § 3; this script starts it unless BACKEND=external).
# Positions are simulated on public roads; nothing here reads real tracks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
PKG=com.bainbridgetrails.roads.dev
ACT=com.bainbridgetrails.app.MainActivity
SHOTS="$ROOT/docs/play/screens"
WORK="${WORK:-$(mktemp -d -t play-e2e)}"
STEP="${STEP:-all}"
DIST="$ROOT/data/roads/dist"
# shellcheck disable=SC1091
source "$ROOT/docs/play/e2e-ids.env"

log() { printf '\n== %s\n' "$*"; }
shot() { adb exec-out screencap -p > "$SHOTS/$1-android.png"; echo "   shot $1-android.png"; }

# ui_find <text|desc> [index]: centre of the first node whose text or content-desc matches (exact matches first).
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
    if q in t.lower() or q in d.lower():
        a = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]*)"', n).group(1))))
        hits.append((0 if q in (t.lower(), d.lower()) else 1, (a[0] + a[2]) // 2, (a[1] + a[3]) // 2))
hits.sort(key=lambda h: h[0])
print(f"{hits[k][1]} {hits[k][2]}" if len(hits) > k else "")
PY
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
  adb shell am start -W -n "$PKG/$ACT" --es playClock 2026-10-29T14:00 --es playTide low >/dev/null
}
finish_walk() {
  tap "Finish walk"; wait_for "Keep walking" 10
  tap "Finish"            # the dialog's confirm button (exact match wins over "Finish this walk?")
  sleep 6
}
replay() { # replay <feature-id>: Start walk is already showing
  tap "Start walk"; sleep 2
  [[ -n "$(ui_find 'Physical activity')" ]] && { tap "Continue"; sleep 1; tap "Allow"; sleep 2; }
  DIST="$DIST" "$ROOT/scripts/emu-route.sh" "$1" 2
}
sync_done() { # Me › sync status shows the walk backed up
  tap "Me"; sleep 2; wait_for "Progress backed up" 60 || true
}

# --- 1. backend ----------------------------------------------------------------------------------------------------
if [[ "$STEP" == all && "${BACKEND:-local}" != external ]]; then
  log "backend: wrangler dev with the e2e dev clock"
  (cd "$ROOT/backend" && npm install --silent && cp .dev.vars.example .dev.vars &&
    node -e 'console.log("DEV_CLOCK_OFFSET_MS="+(Date.parse("2026-10-29T21:00:00Z")-Date.now()))' >> .dev.vars)
  (cd "$ROOT/backend" && npm run dev > "$WORK/wrangler.log" 2>&1 &)
  for _ in $(seq 1 60); do curl -sf localhost:8787/v1/health >/dev/null && break; sleep 1; done
  curl -sf localhost:8787/v1/health; echo
  curl -sf -o /dev/null -w 'play/content %{http_code} %{size_download} B\n' localhost:8787/v1/play/content
  echo "   wrangler log: $WORK/wrangler.log"
fi

# --- 2. build, fresh install, pure baseline ------------------------------------------------------------------------
if [[ "$STEP" == all ]]; then
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

# --- 3. the three replays --------------------------------------------------------------------------------------------
if [[ "$STEP" == all || "$STEP" == walks ]]; then
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

# --- 4. challenge, Logbook, Me, Play off -----------------------------------------------------------------------------
if [[ "$STEP" == all || "$STEP" == after ]]; then
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

log "done; wrangler log (if started here): $WORK/wrangler.log — check § 3.1–3.8 by hand (docs/play/screens/README.md)"
