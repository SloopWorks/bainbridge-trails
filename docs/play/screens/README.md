# Play — AC 8 e2e walkthrough screenshots (2026-10-21)

Run of [`../plan.md` § 3](../plan.md#3-ac-8-e2e-script-opt-in--treasure--kraken--riddle--challenge--logbook) on branch
`gamification/v1`. **Result: the walkthrough does not pass.** Opt-in, sheets, the armchair riddle solve, walk-derived
marks, sync and the Logbook/server totals work, but **no content item can be claimed under the debug Play clock**
(finding F1), so the treasure, kraken and riddle steps (§ 3.2–3.4) are blocked on both platforms, and iOS crashes when
Play turns on (F2). Android command sequence as a script: [`../../../scripts/play-e2e.sh`](../../../scripts/play-e2e.sh).

## Builds and setup

| | |
|---|---|
| Date | 2026-10-21, 00:27–01:55 PDT (real clock); Play clock Thu 2026-10-29 14:00 island time |
| Android | emulator-5554, API 37, `com.bainbridgetrails.roads.dev`, `assembleRoadsDebug` built 00:27 from `5929e68` + the working tree of that moment (uncommitted L2 work present) |
| iOS | iPhone 17 Pro simulator (`DBA472DD-…`), `com.bainbridgetrails.app` Debug, `linkDebugFrameworkIosSimulatorArm64` + `xcodebuild` at 00:51 (tree ≈ `ee30cfb` + uncommitted work) |
| HEAD at write-up | `a2edb45` (the F1 code path is unchanged there: `PlaySensing.kt:94`, `ClaimEvaluator.kt:257`) |
| Backend | `backend/` `npm install`, `cp .dev.vars.example .dev.vars`, `DEV_CLOCK_OFFSET_MS=739793320` appended (node one-liner of § 3), `npm run dev` (fresh local D1; the previous `.wrangler/state` was moved aside, not deleted) |

Commands, in order (Android; `scripts/play-e2e.sh` is the reproducible form):

```sh
cd backend && npm install && cp .dev.vars.example .dev.vars
node -e 'console.log("DEV_CLOCK_OFFSET_MS="+(Date.parse("2026-10-29T21:00:00Z")-Date.now()))' >> .dev.vars
npm run dev                                   # GET /v1/health 200, GET /v1/play/content 200 (32 KB, pack 2026.11.0)
cd app && ./gradlew :androidApp:assembleRoadsDebug
export ANDROID_SERIAL=emulator-5554; source docs/play/e2e-ids.env
adb uninstall com.bainbridgetrails.roads.dev; adb install -r app/androidApp/build/outputs/apk/roads/debug/androidApp-roads-debug.apk
adb shell am start -n com.bainbridgetrails.roads.dev/com.bainbridgetrails.app.MainActivity            # pure-* (Play off)
adb shell am start -n …/com.bainbridgetrails.app.MainActivity --es playClock 2026-10-29T14:00 --es playTide low
# drawer › Backend › Local wrangler dev › Apply & Restart  → did not stick (F3); wrote the drawer prefs instead:
#   shared_prefs/com.sloopworks.debugdrawer.xml  <string name="backend_override">local</string>  (run-as), relaunch
# allow ACCESS_LOCAL_NETWORK; Me › Sign in › Dev sign-in; Settings › Play on › Start playing
DIST=data/roads/dist scripts/emu-route.sh "$E2E_TREASURE_ROAD" 2            # walk 1 (road-northeast-morgan-road-2, 242 m)
DIST=data/roads/dist scripts/emu-route.sh road-manitou-beach-drive-northeast 2   # walk 2 (whole road, 3580 m)
# What's on › Riddle ★ › Pick the place on the map › Frog Rock → solved
DIST=data/roads/dist scripts/emu-route.sh road-northeast-spargur-loop-road 2     # walk 3 (1630 m)
# Map › Challenges › Kraken week; Play chip › Logbook; Me; Settings › Play off › Turn off → Map
```

iOS: `xcrun simctl launch <udid> com.bainbridgetrails.app -btDevSignIn 1 -apiBaseUrl http://localhost:8787
-btPlayClock 2026-10-29T14:00 -btPlayTide low -btScreen settings`, then (after F2) relaunches with
`-acceptSimulatedLocations 1` (F4) and `-btScreen trail:<id>|whats-on|logbook|challenges|me`;
`SIM=<udid> scripts/sim-route.sh trail-farm-trail-north` ($E2E_TREASURE_TRAIL), `trail-fairy-dell-trail`,
`trail-grand-forest-west`. Two simulators were booted, so `booted` is ambiguous: always pass the UDID.

## Screenshots

| file | what it shows |
|---|---|
| `pure-map-android.png` / `-ios.png` | Map with Play off: no chips, pins, fog or labels |
| `pure-list-android.png` / `-ios.png` | Roads / Trails list, Play off |
| `pure-me-android.png` / `-ios.png` | Me, Play off: no Play card |
| `pure-settings-android.png` / `-ios.png` | Settings with the Play switch off |
| `e2e-01-settings-play-android.png` / `-ios.png` | Settings › Play row (signed in, before the switch) |
| `e2e-02-intro-android.png` / `-ios.png` | "Play on the island" intro sheet |
| `e2e-03-wake-android.png` | Start playing pressed: button spinner, map fading in behind (the wake itself was not caught) |
| `e2e-03-wake-ios.png` | first wake frame, ~0.3 s before the crash (F2) |
| `e2e-04-map-play-android.png` | Map with Play: `0 · Walk-on` + challenge chips, pins, kraken label, start-balance snackbar |
| `e2e-04-map-play-ios.png` | Map with Play after relaunch (130 · Walk-on), pins, fog squares, "The kraken is at the west shore" |
| `e2e-05-treasure-sheet-android.png` | Sea glass · Northeast Morgan Road · 25 marks · "Washes away Sunday night" |
| `e2e-05-treasure-sheet-ios.png` | Sea glass · Farm Trail North · 25 marks — still unfound after walking the trail 99 % (F1) |
| `e2e-06-record-find-ios.png` | recording on Farm Trail North (0 → 99 %), **no find moment** (F1). No Android counterpart: no find happened |
| `e2e-07-results-treasure-android.png` | walk 1 results: 58 marks (New ground +8, Road complete +25, Fog lifted +25), **no treasure line** |
| `e2e-07-results-treasure-ios.png` | iOS walk results: 130 marks (+5, Farm Trail North +100, Fog +25), no treasure line |
| `e2e-08-kraken-sheet-android.png` | "The kraken is ashore · Until Sunday, 8 pm", 60–80 marks a stretch, Manitou + Gertie Johnson stretches |
| `e2e-08-kraken-sheet-ios.png` | same for trails: "inking the west shore", Fairy Dell + Schel Chelb |
| `e2e-09-record-kraken-android.png` | walk 2 mid-way: 25:38, 1.2 mi, Manitou Beach Dr 0 → 69 % |
| `e2e-09-record-kraken-ios.png` | Fairy Dell replay mid-way (first attempt: 0.0 mi, every fix dropped as mock, F4) |
| `e2e-10-results-kraken-android.png` | walk 2 results: 209 marks (New ground +94, Road complete +25, Fog +75, Walking day +15), Manitou 0 → 98 %, **no kraken line** |
| `e2e-10-results-kraken-ios.png` | Fairy Dell 0 → 85 %: 196 marks, rank Dock Walker, **no kraken line** |
| `e2e-11-riddle-sheet-android.png` / `-ios.png` | riddle sheet (riddle-frog ★ / riddle-bond ★★) — chip reads **"0 marks"** (F7) |
| `e2e-12-riddle-solved-android.png` / `-ios.png` | "Solved. Walk there to claim it." after the armchair pick (Frog Rock on the map / Grand Forest West from the list) |
| `e2e-13-results-riddle-ios.png` | Grand Forest West walk: 0.0 mi recorded (replay never moved the dot; harness, F8) — no claim |
| `e2e-14-challenges-android.png` / `-ios.png` | Challenges: Weekend sweep (just you), Kraken week (whole island) |
| `e2e-15-challenge-krakenweek-android.png` / `-ios.png` | Kraken week sheet: "0 of 2 stretches sent back by islanders", 75 marks, no names, no countdown; no band line (nothing claimed) |
| `e2e-16-logbook-android.png` | Logbook after walk 3: 382 marks · 0 this week · Dock Walker · 368 to Beachcomber; walks filed under **Wed, Oct 21**, start balance under Thu, Oct 29 (F1) |
| `e2e-16-logbook-ios.png` | Logbook 326 marks; "Riddle · Riddle solved · Stamped" (no number) under Thu Oct 29; walks under Wed Oct 21 |
| `e2e-17-me-playcard-android.png` / `-ios.png` | Me › Play card: 267 / 326 marks, Dock Walker, "483 / 424 to Beachcomber" |
| `e2e-18-play-off-map-ios.png` | Map after Turn off Play: pure again |
| `e2e-13-results-riddle-android.png` | walk 3 (Spargur Loop, 1630 m) results: 115 marks (New ground +40, Road complete +25, Fog +50), **no riddle +40, no Frog Rock lore** (F1) |
| `e2e-18-play-off-map-android.png` | Map after Turn off Play: no chips/pins/fog; walked roads violet as in the pure build; logcat `fx: host stop`; wrangler only `GET /v1/leaderboard` afterwards |

Not taken: `e2e-06-record-find-android` (no find happened), the § 2 `fx-*` set (unchanged from earlier commits).

## Assertions (§ 3)

| # | assertion | Android | iOS | evidence |
|---|---|---|---|---|
| 1 | pure-* and e2e-18 show no Play chrome; no `/v1/play/*` while off | PASS (visual, pure-* and e2e-18); wrangler PASS while signed in with Play off before opt-in (`POST /v1/auth/dev`, `GET /v1/coverage`, `GET /v1/me` only) and after Turn off (`GET /v1/leaderboard` only); logcat `fx: host stop`. Pure shots were on the Prod backend (drawer default) so they say nothing about wrangler | PASS: pure launches on local backend made no request at all | wrangler log |
| 2 | walk 1: treasure `+25…`, `play.accepted` has `treasure-weekly-roads@2026-10-26#00` | **FAIL/BLOCKED** (F1): no find, no claim; `play_claims` empty | **FAIL/BLOCKED** (F1) | results shots; D1 `select count(*) from play_claims` = 0 |
| 3 | walk 2: `kraken-roads@2026-10-29#0`, band 95, +80, "You sent it back" | **FAIL/BLOCKED** (F1): Manitou 98 % covered, no kraken line, `play_attack_stretches` empty | **FAIL/BLOCKED** (F1) | e2e-10 shots |
| 4 | riddle claimed +40/+80; armchair solve = stamp, no number | solve PASS; claim FAIL/BLOCKED (F1): walk 3 Spargur Loop 0 → ≥ 85 %, no riddle line | solve stamp PASS (e2e-16-ios); claim BLOCKED | e2e-12, e2e-16-ios |
| 5 | `ch-krakenweek` shared progress + `{band,count}`, no names, no countdown | PARTIAL: "0 of 2", no names, no countdown; band line absent (nothing claimed) | PARTIAL: "0 of 2" (plan expects trails "of 3", F9) | e2e-15 |
| 6 | Logbook total = Σ rows = `GET /v1/play/me`; no time of day | PASS: after walk 2 0+15+25+94+75+25+8+25 = 267 = D1 `play_totals` roads 267; after walk 3 382 = 267+115 = D1 382 | PASS: 326 = 100+46+50+100+5+25 = D1 trails 326 | e2e-16; D1 `play_totals`; `GET /v1/play/me` (curl, dev sign-in) answers `{"weekId":"2026-10-26","total":0,…}` for a fresh user |
| 7 | chip marks = Logbook total; rank matches ladder | PASS: chip "267 · Dock Walker"; 483 to Beachcomber (750) | PASS: "326 · Dock Walker", 424 to Beachcomber | e2e-16/17 |
| 8 | verify.full + verify.api green | not run here (kmp-verifier owns it) | — | — |

`play.accepted` keys could not be read from the wrangler log: wrangler logs request lines only (no bodies) and the
Worker logs nothing per upload; with no claims the accepted list would be empty anyway. Logbook rows have no time of
day; What's on shows schedule times ("Daylight 7:17 am – 6:28 pm · Next ferry arrives 3:10 pm", "Thu 6 am – Sun 8 pm"),
which are content times, not the walker's.

## Findings

- **F1 (blocker, both platforms): claims use the fix's real timestamp, not the Play clock.** `Fix.toGated()`
  (`PlaySensing.kt:94`) passes `timeMs` (system clock) and `ClaimEvaluator.Walk.add` checks activity/daylight at
  `f.instant` (`ClaimEvaluator.kt:257`); `PlayRepository.claimWalk` builds the day plans from the walk's real start/end.
  With the debug clock at Thu 2026-10-29 14:00 and the device at Wed 2026-10-21 ~01:00 PDT (night, before the week),
  nothing is active at any fix: walking Morgan Road (treasure), Manitou 98 % (kraken stretch) and Farm Trail North 99 %
  (iOS treasure) claimed nothing; `play_claims`, `play_progress`, `play_attack_stretches` stay empty; the Logbook files
  every walk under **Wed, Oct 21** while the start balance and the riddle stamp sit under Thu, Oct 29. § 3's
  `playClock` launch argument therefore cannot drive the e2e. Fix: shift fix times by the debug clock's offset before
  sensing/claiming (debug only), or run the e2e at a real Thursday afternoon / with the device clock set.
- **F2 (blocker, iOS): crash on Start playing and on launch with Play on into the island map.**
  `Uncaught Kotlin exception: kotlin.IllegalArgumentException: Can't represent a width of 89917 and height of 89917 in
  Constraints` from `SizeNode.measure` under `org.maplibre.compose.overlay.GeographicLayout…measure` (a map-overlay
  composable sized in geographic units, i.e. one of the FX overlays). Reports
  `~/Library/Logs/DiagnosticReports/BainbridgeTrails-2026-10-21-005352.ips` and `-005415.ips`. Map with Play works when
  reached through a sheet or after the wake (zoomed in), and the non-map screens (`-btScreen logbook|challenges|me`) work.
- **F3: debug drawer Apply & Restart did not stick** (known race): after the confirm dialog the app restarted on Prod
  (`debug tools installed; backend https://bainbridge-trails-api.patjackson52.workers.dev`, no
  `com.sloopworks.debugdrawer.xml`). Writing `backend_override=local` into that prefs file via `run-as` and relaunching
  works (`backend http://10.0.2.2:8787`); then the ACCESS_LOCAL_NETWORK prompt appears as documented.
- **F4 (plan doc): iOS § 3 launch args lack `-acceptSimulatedLocations 1`.** Without it every `simctl location` fix is
  `isSimulatedBySoftware` and dropped as mock (`IosRecorder.kt:44`): the first Fairy Dell walk recorded 0.0 mi. Also the
  built product is `BainbridgeTrails.app`, not `iosApp.app` as § 3's `simctl install` line says.
- **F5: map pins lose the tap to roads/trails.** Android: the kraken label/marker ("The kraken is at Manitou", drawn
  inland over Falk Road NE) and the Frog Rock riddle pin open "Roads on this section" / the road sheet instead; iOS: the
  Grand Forest pins open "Trails on this section". The treasure pin at street zoom did open its sheet. Workaround used:
  Challenges › Kraken week › Show on map, and Me › What's on › Riddle.
- **F6: the roads kraken label sits inland** (Falk Road NE, ~1 km from the Manitou shore stretch) — screenshot
  `e2e-04-map-play-android.png`.
- **F7: riddle sheets show "0 marks"** (riddle-frog ★ roads, riddle-bond ★★ trails); § 3 expects +40 / +80 on claim.
- **F8 (harness): `sim-route.sh trail-grand-forest-west` (4.3 km) never moved the simulator dot** after a
  `location clear` + `set` + relaunch; the walk saved 0.0 mi. Fairy Dell and Farm Trail North replays in the same session
  worked. Also `emu-route.sh` replays a whole road (Manitou 3580 m = 46 min), not the 1158 m stretch § 3 names.
- **F9 (plan/content): trails Kraken week reads "0 of 2 stretches"**; § 3 expects "1 of 3" for trails (after one repel).
- **F10: Android wake not observed.** `e2e-03-wake-android.png` at +1.2 s shows the intro button spinner; at +7 s the
  map is already up with the "Your walks so far: 0 marks" snackbar.
- Concurrency note: other agents committed `ee30cfb`, `1b99fa7`, `a2edb45` and edited `PlayRepository.kt`,
  `Finalizer.kt`, `RecordingController.kt`, `MapTab.kt` during the run (wrangler hot-reloaded twice at ~00:46), so the two
  apps were built from different trees; F1's code path is unchanged at `a2edb45`.
- Operator slip: one stray `adb emu geo fix -122.5 47.6` (a 6 km jump) was sent during walk 2; the coverage/plausibility
  pipeline absorbed it (walk 2 synced, Manitou 98 %).

## Showcase captures (run 2)

Captured from the `FxShowcaseScreen` (`screen=fx`) at commit `f2e10cc` plus the working tree at build time (HEAD has since moved to `79c7784`; the
concurrent edits were not in these binaries). Android: Pixel 9 Pro emulator, trails app, roads app for the three `kraken_*` effects (`--es dataset roads`); iOS:
iPhone 17 Pro simulator, trails app (`-btDataset roads` for kraken effects). Android dark = `cmd uimode night yes` + `--es theme dark` (night mode restored to `no`).
Android reduced = `--es reduced 1`, light, final frame. iOS = light, `t=` at the plan § 2 hero timing (t0 where none is named). Each launch waited 22–35 s on Android
(cold start + style load) and 6 s on iOS; blank-map Android shots were re-taken. Widths 600 px. The debug HUD (top right) and control sheet (bottom) are part of the
showcase screen and cover the map edges: on iOS the HUD overlaps the `treasure_found` burst pin and the `play_wake` title, and the sheet cuts the lower tentacles of
`kraken_attack`/`kraken_recede` (no launch option hides the HUD or moves the camera; the Android shots, whose HUD is narrower, show these in full).

| file | effect | variant | platform | shows |
|---|---|---|---|---|
| `fx-treasure_glint-dark-t0-android.png` | treasure_glint | dark, t0 | Android | five Fay Island treasure pins, glint at the start frame |
| `fx-treasure_glint-reduced-android.png` | treasure_glint | reduced | Android | five shell-in-ring pins on the Fay map |
| `fx-treasure_glint-light-t0-ios.png` | treasure_glint | light, t0 | iOS | same effect on iOS at the hero timing |
| `fx-exploration_fog-dark-t0-android.png` | exploration_fog | dark, t0 | Android | fog squares over the island, lifted 1 in 3 |
| `fx-exploration_fog-reduced-android.png` | exploration_fog | reduced | Android | fog squares over the island, 20 lifted |
| `fx-exploration_fog-light-t0-ios.png` | exploration_fog | light, t0 | iOS | same effect on iOS at the hero timing |
| `fx-riddle_marker-dark-t0-android.png` | riddle_marker | dark, t0 | Android | unlit riddle lantern pin on Winslow |
| `fx-riddle_marker-reduced-android.png` | riddle_marker | reduced | Android | unlit riddle lantern pin on Winslow |
| `fx-riddle_marker-light-t0-ios.png` | riddle_marker | light, t0 | iOS | same effect on iOS at the hero timing |
| `fx-kraken_lurk-dark-t0-android.png` | kraken_lurk | dark, t0 | Android | ripple ring off Manitou, eye not yet surfaced (t0) |
| `fx-kraken_lurk-reduced-android.png` | kraken_lurk | reduced | Android | ripple ring and surfaced eye off Manitou |
| `fx-kraken_lurk-light-t0-ios.png` | kraken_lurk | light, t0 | iOS | same effect on iOS at the hero timing |
| `fx-ferry_crossing-dark-t0-android.png` | ferry_crossing | dark, t0 | Android | ferry run preset on the Eagle Harbor crossing, boat at the start frame |
| `fx-ferry_crossing-reduced-android.png` | ferry_crossing | reduced | Android | Eagle Harbor crossing map, ferry lane dashed |
| `fx-ferry_crossing-light-t0-ios.png` | ferry_crossing | light, t0 | iOS | same effect on iOS at the hero timing |
| `fx-frog_rock-dark-t700-android.png` | frog_rock | dark, t700 | Android | frog on its rock mid-blink (700 ms) |
| `fx-frog_rock-reduced-android.png` | frog_rock | reduced | Android | frog on its rock, final frame |
| `fx-frog_rock-light-t700-ios.png` | frog_rock | light, t700 | iOS | same effect on iOS at the hero timing |
| `fx-kraken_attack-dark-t1100-android.png` | kraken_attack | dark, t1100 | Android | Manitou stretch inked, tentacles and splash rising at 1100 ms |
| `fx-kraken_attack-reduced-android.png` | kraken_attack | reduced | Android | Manitou stretch inked, final frame |
| `fx-kraken_attack-light-t1100-ios.png` | kraken_attack | light, t1100 | iOS | same effect on iOS at the hero timing |
| `fx-kraken_recede-dark-t0-android.png` | kraken_recede | dark, t0 | Android | tentacles and pool with the repelled stretch un-drawn (t0) |
| `fx-kraken_recede-reduced-android.png` | kraken_recede | reduced | Android | repelled stretch drawn plain, final frame |
| `fx-kraken_recede-light-t0-ios.png` | kraken_recede | light, t0 | iOS | same effect on iOS at the hero timing |
| `fx-treasure_found-dark-t400-android.png` | treasure_found | dark, t400 | Android | found treasure burst with sparkles and 4 other pins (400 ms) |
| `fx-treasure_found-reduced-android.png` | treasure_found | reduced | Android | found-shell state, final frame |
| `fx-treasure_found-light-t400-ios.png` | treasure_found | light, t400 | iOS | same effect on iOS at the hero timing |
| `fx-riddle_light-dark-t400-android.png` | riddle_light | dark, t400 | Android | lit riddle pin on Winslow, lighting at 400 ms |
| `fx-riddle_light-reduced-android.png` | riddle_light | reduced | Android | lit riddle pin on Winslow, final frame |
| `fx-riddle_light-light-t400-ios.png` | riddle_light | light, t400 | iOS | same effect on iOS at the hero timing |
| `fx-claim_pop-dark-t500-android.png` | claim_pop | dark, t500 | Android | score chip "1,190 · Salal Scout" and +25 pop on the map (500 ms) |
| `fx-claim_pop-reduced-android.png` | claim_pop | reduced | Android | score chip and pop, final frame |
| `fx-claim_pop-light-t500-ios.png` | claim_pop | light, t500 | iOS | same effect on iOS at the hero timing |
| `fx-play_wake-dark-t1900-android.png` | play_wake | dark, t1900 | Android | "The island is awake" moment over a dotted constellation (1900 ms) |
| `fx-play_wake-reduced-android.png` | play_wake | reduced | Android | wake moment, final frame |
| `fx-play_wake-light-t1900-ios.png` | play_wake | light, t1900 | iOS | same effect on iOS at the hero timing |

Not captured: none; all 12 effects x 3 variants (36 images). Caveats: `kraken_lurk` at t0 shows only the ripple ring (the eye surfaces later; see the reduced shot);
`exploration_fog`, `treasure_glint` and `ferry_crossing` at t0 are the start frame of an ambient loop, so a still cannot show motion.
