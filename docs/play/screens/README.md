# Play — AC 8 e2e walkthrough screenshots (2026-10-21)

Runs of [`../plan.md` § 3](../plan.md#3-ac-8-e2e-script-opt-in--treasure--kraken--riddle--challenge--logbook) on branch
`gamification/v1`. Android command sequence as a script: [`../../../scripts/play-e2e.sh`](../../../scripts/play-e2e.sh).

**Result, e2e run 2 (HEAD `9e954f9`, 2026-10-21 02:36–03:32 PDT): the claim steps pass.** With the run-1 blockers fixed,
every content item claims under the debug Play clock on a fresh local D1: Android (roads) claimed the weekly treasure
(+25), the Manitou kraken stretch (band 80, +70, "You sent it back") and the armchair-solved Frog Rock riddle (+40, plus
the Frog Rock lore +15 and the ladybug egg +20 on the same walk); iOS (trails) turned Play on without crashing and claimed
its weekly treasure (+25). Logbook totals equal D1 `play_totals` on both platforms (453 roads, 155 trails), and walks are
now filed under Thu, Oct 29. Open: the treasure line's title is the raw template `{tier} on {place}` (F11, both
platforms), the iOS record chips wrap one letter per line (F12), and `ch-krakenweek` shows "0 of 2" with no band line,
which is the server's design with one walker (F9, plan wording). Steps not re-run in run 2: the pure-* baseline,
Play off (e2e-18), the iOS kraken and riddle walks, Me (e2e-17); their run-1 screenshots stay.

Run 1 (2026-10-21 00:27–01:55, tree ≈ `5929e68`/`ee30cfb` + uncommitted work) did not pass: no item could be claimed
under the Play clock (F1) and iOS crashed on Start playing (F2). Its findings are below with their run-2 status.

## Builds and setup

| | run 2 | run 1 |
|---|---|---|
| Date (real clock) | 2026-10-21, 02:36–03:32 PDT | 2026-10-21, 00:27–01:55 PDT |
| Play clock | Thu 2026-10-29 14:00 island time, tide low | same |
| Android | emulator-5554, API 37, `com.bainbridgetrails.roads.dev`, old `.dev` uninstalled, `:androidApp:installRoadsDebug` at HEAD `9e954f9` (clean tree) | `assembleRoadsDebug` from `5929e68` + uncommitted work |
| iOS | iPhone 17 Pro (`DBA472DD-365C-42E1-9C3E-DCAFFCF47882`), app uninstalled first, `linkDebugFrameworkIosSimulatorArm64` + `xcodebuild` at `9e954f9`, `BainbridgeTrails.app` | Debug build, tree ≈ `ee30cfb` + uncommitted work |
| Backend | run 1's `backend/.dev.vars` with `DEV_CLOCK_OFFSET_MS` replaced by `732248581` (2026-10-29T21:00Z − now); previous `.wrangler/state` moved aside (not deleted); fresh local D1, migrations 0001–0005 applied by `npm run dev` | `.dev.vars.example` + `DEV_CLOCK_OFFSET_MS=739793320`, fresh local D1 |
| HEAD during the run | `980a48e` landed mid-run (docs only: `README.md`, `docs/play/README.md`); the binaries are `9e954f9` code | `a2edb45` at write-up |

Commands, in order (Android; `scripts/play-e2e.sh` is the reproducible form; run 2 followed the same sequence):

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
DIST=data/roads/dist scripts/emu-route.sh road-manitou-beach-drive-northeast 2 900   # walk 2 (run 2: first 900 s, 1170 m)
# run 2: Map › tap the riddle pin at Frog Rock › Pick the place on the map › Frog Rock → solved (run 1: via What's on)
DIST=data/roads/dist scripts/emu-route.sh road-northeast-spargur-loop-road 2     # walk 3 (1630 m)
# Map › Challenges › Kraken week; Play chip › Logbook; Me; Settings › Play off › Turn off → Map
```

iOS: `xcrun simctl launch <udid> com.bainbridgetrails.app -btDevSignIn 1 -apiBaseUrl http://localhost:8787
-btPlayClock 2026-10-29T14:00 -btPlayTide low -acceptSimulatedLocations 1 -btScreen settings` (run 2: no crash; run 1
relaunched after F2), then `-btScreen trail:<id>|whats-on|logbook|challenges|me`;
`SIM=<udid> scripts/sim-route.sh trail-farm-trail-north` ($E2E_TREASURE_TRAIL), `trail-fairy-dell-trail`,
`trail-grand-forest-west`. Two simulators were booted, so `booted` is ambiguous: always pass the UDID.

D1 checks: `cd backend && npx wrangler d1 execute bainbridge-trails --local --command "select occ, marks from play_claims"`
(and `play_totals`, `play_attack_stretches`, `play_progress`).

## Screenshots

Rows marked **(run 2)** were re-taken at `9e954f9`; the rest are run 1.

| file | what it shows |
|---|---|
| `pure-map-android.png` / `-ios.png` | Map with Play off: no chips, pins, fog or labels |
| `pure-list-android.png` / `-ios.png` | Roads / Trails list, Play off |
| `pure-me-android.png` / `-ios.png` | Me, Play off: no Play card |
| `pure-settings-android.png` / `-ios.png` | Settings with the Play switch off |
| `e2e-01-settings-play-android.png` / `-ios.png` | Settings › Play row (signed in, before the switch) |
| `e2e-02-intro-android.png` / `-ios.png` | "Play on the island" intro sheet |
| `e2e-03-wake-android.png` | **(run 2)** the wake, +3.8 s after Start: "The island is awake", subtitle, island outline and pins (burst of 8 frames at ~0.6 s) |
| `e2e-03-wake-ios.png` | **(run 2)** the wake mid-way: "The island is awake" over the outline; no crash (F2 fixed) |
| `e2e-04-map-play-android.png` | **(run 2)** Map with Play zoomed to Murden Cove: `0 · Walk-on` + challenge chips, the inked Manitou stretch, kraken ripple ring and "The kraken is at Manitou" on the water just off the stretch (F6 fixed) |
| `e2e-04-map-play-ios.png` | **(run 2)** Map with Play after the wake: `0 · Walk-on`, pins, fog squares, "The kraken is at the west shore" (zoomed in, the ring sits on the shoreline at Lynwood Center) |
| `e2e-05-treasure-sheet-android.png` | **(run 2)** Sea glass · Northeast Morgan Road · 25 marks · "Washes away Sunday night", opened by tapping the pin |
| `e2e-05-treasure-sheet-ios.png` | Sea glass · Farm Trail North · 25 marks — run 1, still unfound after walking the trail 99 % (F1) |
| `e2e-06-record-find-android.png` | **(run 2)** recording Morgan Road 0 → 97 %, chip "1 find" (the find moment fired at the start point, before the first frame) |
| `e2e-06-record-find-ios.png` | **(run 2)** recording Farm Trail North 0 → 58 %, treasure pin on the trail, chip "1 find" wrapped one letter per line (F12) |
| `e2e-07-results-treasure-android.png` | **(run 2)** walk 1 results: 82 marks (New ground +7, Road complete +25, Fog lifted +25, **treasure +25** titled `{tier} on {place}` (F11)) |
| `e2e-07-results-treasure-ios.png` | **(run 2)** Farm Trail North results: 155 marks (+5, +100, Fog +25, **treasure +25**, same F11 title) |
| `e2e-08-kraken-sheet-android.png` | **(run 2)** "The kraken is ashore · Until Sunday, 8 pm", 60–80 marks a stretch, Manitou + Gertie Johnson stretches (before walk 2) |
| `e2e-08-kraken-sheet-ios.png` | same for trails: "inking the west shore", Fairy Dell + Schel Chelb (run 1) |
| `e2e-09-record-kraken-android.png` | **(run 2)** walk 2 at 4:47, 0.2 mi, Manitou Beach Dr 0 → 13 % |
| `e2e-09-record-kraken-ios.png` | Fairy Dell replay mid-way (run 1; first attempt: 0.0 mi, every fix dropped as mock, F4) |
| `e2e-10-results-kraken-android.png` | **(run 2)** walk 2 results: 181 marks (New ground +46, Fog +50, Walking day +15, **Kraken sent back +70**), Manitou 0 → 52 %, new rank Dock Walker |
| `e2e-10-results-kraken-ios.png` | Fairy Dell 0 → 85 %: 196 marks, no kraken line (run 1, F1) |
| `e2e-11-riddle-sheet-android.png` | **(run 2)** riddle-frog sheet opened by tapping its pin on the map (F5 fixed): Riddle ★, **40 marks** (F7 fixed), Pick the place on the map / Choose from a list |
| `e2e-11-riddle-sheet-ios.png` | riddle-bond ★★ sheet, chip "0 marks" (run 1, F7) |
| `e2e-12-riddle-solved-android.png` | **(run 2)** "Solved. Walk there to claim it." after picking Frog Rock on the map |
| `e2e-12-riddle-solved-ios.png` | same after Grand Forest West from the list (run 1) |
| `e2e-13-results-riddle-android.png` | **(run 2)** walk 3 (Spargur Loop, 1630 m) results: 190 marks (New ground +40, Road complete +25, Fog +50, Frog Rock's ladybug egg +20, **Riddle walked to +40**, Frog Rock island story +15) |
| `e2e-13-results-riddle-ios.png` | Grand Forest West walk: 0.0 mi recorded (run 1, harness F8) — no claim |
| `e2e-14-challenges-android.png` / `-ios.png` | Challenges: Weekend sweep (just you), Kraken week (whole island) (run 1) |
| `e2e-15-challenge-krakenweek-android.png` | **(run 2)** Kraken week after the repel: "0 of 2 stretches sent back by islanders", 75 marks, no names, no countdown, no band line (F9) |
| `e2e-15-challenge-krakenweek-ios.png` | Kraken week sheet, "0 of 2" (run 1) |
| `e2e-16-logbook-android.png` | **(run 2)** Logbook after walk 3: 453 marks · 453 this week · Dock Walker · 297 to Beachcomber; every row under **Thu, Oct 29**; riddle stamp "Stamped" with no number |
| `e2e-16-logbook-ios.png` | **(run 2)** Logbook 155 marks · 155 this week; Thu, Oct 29: treasure +25 (F11 title), Farm Trail North +100, New ground +5, Fog +25, start balance +0 |
| `e2e-17-me-playcard-android.png` / `-ios.png` | Me › Play card: 267 / 326 marks, Dock Walker (run 1) |
| `e2e-18-play-off-map-ios.png` | Map after Turn off Play: pure again (run 1) |
| `e2e-18-play-off-map-android.png` | Map after Turn off Play: no chips/pins/fog; walked roads violet as in the pure build (run 1) |

Not taken in run 2: the pure-* set, e2e-01/02/14/17/18, the iOS kraken/riddle walks (e2e-05/08–13 `-ios`).

## Assertions (§ 3), run 2 (HEAD `9e954f9`)

| # | assertion | Android (roads) | iOS (trails) | evidence |
|---|---|---|---|---|
| 1 | no `/v1/play/*` while Play is off | **PASS**: signed in with Play off the app made `POST /v1/auth/dev`, `GET /v1/coverage`, `GET /v1/me`, `GET /v1/leaderboard` only; the first `/v1/play/*` (`GET /v1/play/me`) came after Start playing. Pure-* / e2e-18 not re-taken | **PASS**: `POST /v1/auth/dev`, `GET /v1/coverage` only until Start playing | wrangler request log (no bodies) |
| 2 | walk 1: treasure `+25/40/60/150` by tier; `treasure-weekly-<goal>@2026-10-26#NN` accepted | **PASS**: results "+25" (Sea glass tier); D1 `treasure-weekly-roads@2026-10-26#00` marks 25; Logbook row not local-only. Title text wrong (F11) | **PASS**: "+25"; D1 `treasure-weekly-trails@2026-10-26#00` marks 25; same F11 title | e2e-07 both; D1 `play_claims` |
| 3 | walk 2: `kraken-roads@2026-10-29#0` band ≥ 60 with matching marks; "You sent it back" | **PASS**: D1 `kraken-roads@2026-10-29#0` kind `ink` marks 70 = band 80 of the pack's bands (60→60, 80→70, 95→80); `play_attack_stretches` repelled_by 1; kraken sheet row "Manitou Beach Drive Northeast, You sent it back". (900 s covered the stretch to band 80, not § 3's band 95/+80) | not run | e2e-10; D1 |
| 4 | walk 3: riddle claimed +40 (roads); armchair solve = stamp, no number | **PASS**: pin tap opened the sheet (F5), solve on the map → "Solved"; walk 3 "Riddle walked to +40"; D1 `riddle-frog@2026-10-26` marks 40; Logbook "Riddle solved · Stamped" without a number. Bonus seen: `lore-frog@2026-10-26` +15, `egg-ladybug@2026-10-26` +20 | not run | e2e-11/12/13/16 |
| 5 | `ch-krakenweek`: shared progress + `{band, count}`, no names, no countdown | **PARTIAL (by design)**: "0 of 2 stretches sent back by islanders", no names, no countdown; no band line. Server: a stretch counts as sent back at ≥ 3 distinct walkers (`minPlayersPerStretch`), and the client shows the band only when its count is known (≥ 5); D1 `play_progress` `ch-krakenweek@2026-10-29` = 1 (band `middle`, count hidden). See F9 | not run | e2e-15; D1 `play_progress` |
| 6 | Logbook total = Σ rows = server total; no time of day | **PASS**: 453 = 82 + 181 + 190 = D1 `play_totals` roads 453 (cov 93 + derived 175 + found 170 + week 15); rows dated, no times | **PASS**: 155 = 25 + 100 + 5 + 25 + 0 = D1 trails 155 (5 + 125 + 25) | e2e-16 both; D1 `play_totals` |
| 7 | chip marks = Logbook total; rank matches ladder | **PASS**: chip "263 · Dock Walker" after walk 2, Play card "453 marks. Dock Walker. 297 to Beachcomber" (750) | **PASS**: chip "155 · Walk-on", Logbook "95 to Dock Walker" (250) | map / Logbook shots |
| 8 | verify.full + verify.api green | not run here (kmp-verifier owns it) | — | — |

D1 `play_claims` after run 2 (`select dataset, occ, kind, marks`):

```
roads   egg-ladybug@2026-10-26               egg       20
roads   kraken-roads@2026-10-29#0            ink       70
roads   lore-frog@2026-10-26                 lore      15
roads   riddle-frog@2026-10-26               riddle    40
roads   treasure-weekly-roads@2026-10-26#00  treasure  25
trails  treasure-weekly-trails@2026-10-26#00 treasure  25
```

`play.accepted` keys still cannot be read from the wrangler log (request lines only); the D1 rows above are the
accepted claims.

## Findings

Status after run 2 in brackets.

- **F1 [fixed] (blocker, both platforms): claims use the fix's real timestamp, not the Play clock.** Run 1: `Fix.toGated()`
  (`PlaySensing.kt:94`) passed `timeMs` (system clock) and `ClaimEvaluator.Walk.add` checked activity/daylight at
  `f.instant` (`ClaimEvaluator.kt:257`); nothing was active at any fix, `play_claims` stayed empty and the Logbook filed
  walks under Wed, Oct 21. Run 2: treasure, kraken, riddle, lore and egg all claimed on Android and the treasure on iOS;
  every Logbook row is under Thu, Oct 29.
- **F2 [fixed] (blocker, iOS): crash on Start playing and on launch with Play on into the island map**
  (`IllegalArgumentException: Can't represent a width of 89917 and height of 89917 in Constraints` under
  `org.maplibre.compose.overlay.GeographicLayout`). Run 2: Start playing ran the wake into the map and later launches
  into the map worked; no new `BainbridgeTrails-*.ips` in `~/Library/Logs/DiagnosticReports`.
- **F3 [still open] debug drawer Apply & Restart did not stick** (known race in the external drawer). Run 2 used the
  prefs-file workaround directly (`debug tools installed; backend http://10.0.2.2:8787`).
- **F4 [fixed in plan] iOS § 3 launch args lacked `-acceptSimulatedLocations 1`; product is `BainbridgeTrails.app`.**
  § 3 now has both; run 2 used them and the Farm Trail North replay recorded normally.
- **F5 [fixed] map pins lost the tap to roads/trails.** Run 2: tapping the Frog Rock riddle pin on the map opened the
  riddle sheet; the treasure pin opened its sheet.
- **F6 [fixed] the roads kraken label sat inland** (Falk Road NE). Run 2: the ripple ring and "The kraken is at Manitou"
  sit on the water of Murden Cove just off the inked stretch (`e2e-04-map-play-android.png`). Trails, zoomed in: the
  ring sits on the shoreline at Lynwood Center (scratch evidence only; at island zoom the label overlaps land).
- **F7 [fixed] riddle sheets showed "0 marks".** Run 2: riddle-frog shows "40 marks" and claimed +40.
- **F8 [not re-tested] (harness) `sim-route.sh trail-grand-forest-west` never moved the simulator dot**; the iOS riddle
  walk was not part of run 2. `emu-route.sh … 2 900` (plan § 3) bounds walk 2 to 1170 m: band 80 (+70), not 95.
- **F9 [still open, plan wording] Kraken week "of N" and the band line.** The server counts a stretch as sent back only
  when ≥ `minPlayersPerStretch` (3) distinct walkers repelled it (`backend/src/play/challenges.ts`), and the band line
  needs a known count (≥ 5, `ChallengesLogic.kt`); a single-walker local e2e therefore shows "0 of 2" and no band line
  even after a repel (D1 `play_attack_stretches` repelled_by 1, `play_progress` 1). § 3's "1 of 2/3 + your band" cannot
  appear without 3 (stretch) and 5 (band) seeded walkers; trails still reads "of 2" vs § 3's "1 of 3".
- **F10 [fixed] Android wake not observed.** Run 2 caught it with a ~0.6 s screencap burst: title at +2.6 s, subtitle and
  island outline at +3.2–3.8 s, pins filling in at +4.4 s (`e2e-03-wake-android.png`).
- **F11 [new] (both platforms): the treasure Logbook/results line shows the raw template `{tier} on {place}`.** The
  walk results and Logbook rows read "{tier} on {place} · Weekly treasure · +25" (Android and iOS; the a11y text is
  "Treasure. {tier} on {place}. Weekly treasure."). `PlayRepository.kt:394` passes `items[it]?.copy?.title` (the pack's
  unfilled `"{tier} on {place}"`) as the Logbook title into `PlayClaims.record` (`PlayClaimQueue.kt:296`); the find
  moment fills it (`PlayNearbyStrip.kt:71`, `PlayFormat.fill`), the claim path does not. Entries are stored as written
  (`LogCopy` contract), so already-written rows keep the template.
- **F12 [new] (iOS): record-screen chips wrap one letter per line.** With "GPS ok" + "Unverified" + the Play find chip,
  the "1 find" chip renders as `1 / fi / n / d` and "Unverified" wraps to `Unverifie / d` once "Waiting for GPS" shows
  (`e2e-06-record-find-ios.png`). Android lays out the same three chips on one line.
- Note (run 2): the treasure find on Android fired at the very first fix (the walk started on the treasure's point), so
  the find moment itself was not captured; the chip and results show it.
- Concurrency note (run 1): other agents committed and edited Play sources during run 1, so its two apps were built from
  different trees. Run 2 built both apps from the clean `9e954f9` tree; `980a48e` (docs only) landed mid-run.
- Operator slip (run 1): one stray `adb emu geo fix -122.5 47.6` during walk 2; absorbed by the pipeline.

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
