# Play trails edition v1.1 — AC 8 e2e run record (2026-10-21)

Run of plan § W4 ([`../../../superpowers/plans/2026-10-21-play-trails-v1-1.md`](../../../superpowers/plans/2026-10-21-play-trails-v1-1.md))
on branch `play/trails-v1-1`. Command sequence as a script: `MODE=trails scripts/play-e2e.sh`
([`../../../../scripts/play-e2e.sh`](../../../../scripts/play-e2e.sh)); the roads flavour (`docs/play/screens/README.md`)
is unchanged.

**Result: the trails half passes on Android and iOS.** Three replayed trails at island time Sat 2026-10-31 10:00 on a
fresh local D1 produced exactly the trails rows of the plan's table on both platforms (Hayano +100 / First steps
Moritani +50 / Every trail in a park +150; Peter's +100 / High ground +50 / First steps Gazzam +50 / Climb "328 ft"
+50 / Walking day +15; Grand Forest East +100 / High ground +50 / First steps Grand Forest +50 / Milestone "1 mi"
+50 / Uphill week +100 / Park hop +100), plus the agenda's find rows (`egg-strawberry` on Hayano, treasure
`#01` on Grand Forest East) and three more finds the plan did not list (`riddle-steep` +120 and `lore-gazzam` +15 on
Peter's, `riddle-bond` at 0 under the daily cap on Grand Forest East). Logbook total 1,878 = D1 `play_totals.total`
1,878 on Android; `trail_marks = 500`; `/v1/play/me` perKind and the 8 `paid` keys match the plan. Two deviations
from the plan's numbers, neither an app bug: `ch-sweep` (Weekend sweep) is also open Sat–Mon and was completed by the
same three walks, so `perKind.challenge` is 3, not 2 (F1); and the Grand Forest East replay also completed the
dataset's near-duplicate `trail-grand-foreste-east-trail` (F2), so the Me strip climb reads "534 / 820", not
"522 / 820". iOS (fresh D1): the same rows on all three walks, Logbook 1,855 = D1 1,855, strip "534 / 820 · 2.4 / 5 ·
1 / 19 · 2 / 10". Open UX notes: F3–F5; TalkBack/VoiceOver not run.

## Builds and setup

| | Android (trails) | Android (roads, UX-1 check) | iOS (trails) |
|---|---|---|---|
| Date (real clock) | 2026-10-21, 18:25–20:20 PDT | 20:18 PDT | 2026-10-21, 20:24–21:55 PDT |
| Island (Play) clock | Sat 2026-10-31 10:00 PT (`--es playClock 2026-10-31T10:00`) | Thu 2026-10-29 14:00 (roads script clock) | `-btPlayClock 2026-10-31T10:00` |
| Device | `emulator-5554`, API 37 | same | iPhone 17 Pro `DBA472DD-365C-42E1-9C3E-DCAFFCF47882`, iOS 26.3 |
| App | `com.bainbridgetrails.app.dev`, `:androidApp:assembleTrailsDebug` at `bfdce9c`, fresh install | `com.bainbridgetrails.roads.dev`, `assembleRoadsDebug` at `bfdce9c` | `BainbridgeTrails.app` (`linkDebugFrameworkIosSimulatorArm64` + `xcodebuild`) at `bfdce9c`, uninstalled first |
| Backend | local wrangler `4.151.2`, `.dev.vars.example` + `ALLOW_DEV_AUTH=true` + `DEV_CLOCK_OFFSET_MS=` (2026-10-31T17:00Z − now); previous `.wrangler/state` moved aside; fresh D1, `npm run dev` applied `0001, 0002, 0003, 0005, 0006, 0007` (0004 is `.pending`) | same wrangler (roads rows, same D1) | wrangler stopped, `.wrangler/state` moved aside again, restarted: fresh D1, same migrations |
| HEAD | binaries `bfdce9c`; `8d9c324` (final-review P2 fixes in composeApp, docs) landed on the branch during the run — not re-tested here | | |

Commands, in order (Android; `MODE=trails scripts/play-e2e.sh` is the reproducible form, `STEP=backend|setup|walks|after|a11y|assert|roads`):

```sh
cd backend && npm install && cp .dev.vars.example .dev.vars            # ALLOW_DEV_AUTH=true is in the example
node -e 'console.log("DEV_CLOCK_OFFSET_MS="+(Date.parse("2026-10-31T17:00:00Z")-Date.now()))' >> .dev.vars
npm run dev                                   # 0.0.0.0:8787; GET /v1/health 200; GET /v1/play/content 200 (51 KB, pack 2026.11.3)
cd app && ./gradlew :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64
export ANDROID_SERIAL=emulator-5554
adb uninstall com.bainbridgetrails.app.dev; adb install -r app/androidApp/build/outputs/apk/trails/debug/androidApp-trails-debug.apk
# drawer prefs → Local wrangler (run-as, shared_prefs/com.sloopworks.debugdrawer.xml backend_override=local; F3 of the roads run)
adb shell am start -n com.bainbridgetrails.app.dev/com.bainbridgetrails.app.MainActivity --es playClock 2026-10-31T10:00
# logcat: "debug tools installed; backend http://10.0.2.2:8787"; Allow ACCESS_LOCAL_NETWORK; Me › Sign in › Dev sign-in
# Settings › Play on › Start playing                                    (t-01)
# Trails › search "Hayano" › Hayano Trail › Start walk › Start:
scripts/emu-route.sh trail-hayano-trail 2              # 629 m chained (3 segments), 8.1 min  → Finish (t-02)
scripts/emu-route.sh trail-peters-trail 2              # 1,922 m chained (2 segments), 24.6 min → Finish (t-03)
scripts/emu-route.sh trail-grand-forest-east-trail 2   # 3,626 m chained (12 segments), 46.5 min → Finish (t-04)
# after each Finish: Continue › results › Done › Me (sync = PUT /v1/coverage 200 in the wrangler log)
# Map › Play chip › Logbook (t-05); Map › "4 challenges this week" (t-06); Me › Trail milestones (t-07) › All (t-08)
adb shell cmd uimode night yes   # relaunch, Me, All → t-07-me-strip-dark, t-08-me-sheet-dark; then night no
adb shell settings put system font_scale 2.0   # relaunch, Me → t-09-me-font2 (+ -sheet); then font_scale 1.0
cd backend && npx wrangler d1 execute bainbridge-trails --local --command "SELECT total, trail_marks, found_marks FROM play_totals WHERE dataset='trails'"
curl -H "Authorization: Bearer $TOKEN" 'localhost:8787/v1/play/me?dataset=trails'   # token = the app's no_backup/session.json (run-as)
curl -H "Authorization: Bearer $TOKEN" 'localhost:8787/v1/coverage?dataset=trails'
adb install -r app/androidApp/build/outputs/apk/roads/debug/androidApp-roads-debug.apk   # roads: same prefs, clock, dev sign-in, Play on → Me (t-10)
```

The script's trails additions found during the run (fixed in the script, not the app): the trails flavour opens a
trail from Trails › search (the map's "Find a trail" bar led to the leaderboard button under uiautomator); the Trails
tab restores its stack, so the script pops to the list first; TrailDetail › Start walk lands on Record › "Ready when
you are" › Start (roads starts at once); Me shows "Progress backed up" only briefly, so the sync wait also watches
the wrangler log for the walk's `PUT /v1/coverage`; `/v1/coverage` nests `coveredM` under `metrics`.

iOS: `xcrun simctl install <udid> /tmp/bt-dd/Build/Products/Debug-iphonesimulator/BainbridgeTrails.app`;
`xcrun simctl launch <udid> com.bainbridgetrails.app -btDevSignIn 1 -apiBaseUrl http://localhost:8787 -btPlayClock
2026-10-31T10:00 -acceptSimulatedLocations 1 -btScreen settings` → Play switch › Start playing; then per trail
`-btScreen trail:<id>` › Start walk (› Start on the first walk, plus the location and Motion & Fitness prompts) and
`SIM=<udid> scripts/sim-route.sh <id>` (same three ids, same chained lengths), Finish walk › Finish after the route's
duration; `xcrun simctl io <udid> screenshot …`. Two simulators were booted, so `booted` is ambiguous: always the UDID.

## Screenshots

| file | what it shows |
|---|---|
| `t-01-settings-play-android.png` | Settings › Play row, signed in, before the switch |
| `t-02-results-walk1-android.png` | walk 1 overlay: "Trail complete: Hayano Trail. Island progress 0.3%. 1 of 114 trails finished. +383 marks. New rank, Dock Walker" (the rows are in `t-05-logbook-3`) |
| `t-03-results-walk2-celebrate-android.png` / `-android.png` / `-2-android.png` | walk 2 overlay (+610, Beachcomber) and results sheet: Peter's Trail 0 → 97.1 %, Gazzam Lake Main Trail 0.5 %; 610 marks this walk: New ground +110, **Peter's Trail +100, High ground +50, First steps Gazzam Lake Preserve +50, Climb "328 feet climbed" +50**, Fog lifted +100, **Walking day +15**, "+2 more in your Logbook" (riddle-steep +120, Gazzam lore +15) |
| `t-04-results-walk3-celebrate-android.png` / `-android.png` / `-2-android.png` | walk 3 overlay ("2 trails complete: Grand Forest East Trail, Grand Foreste East Trail … +885 marks. New rank, Salal Scout") and results: GF East 0 → 98.8 %, "Grand Foreste East Trail" 0 → 100 % (F2), Main Loop 25.2 %, McRedmond 15.5 %, Hilltop 4.4 %; 885 marks: New ground +260, Trail complete ×2 +100, **High ground +50, First steps Grand Forest +50** (the rest in the Logbook) |
| `t-05-logbook-android.png` / `-2` / `-3` | Logbook "1,878 marks · 1,878 this week · Salal Scout · 1,122 to Madrona Wanderer"; every row under **Sat, Oct 31**, no times; walk 3: Uphill week +100, Park hop +100, Weekend sweep "Daily limit reached. Found, no marks" +0, Riddle +0 (same), Sea glass on Grand Forest East Trail +25, New ground +260, GF East +100, GF'e' East +100, High ground +50, First steps Grand Forest +50, Milestone "1 mile of trail walked" +50, Fog +50; walk 2 as above + Island story Gazzam Lake +15, Riddle walked to +120; walk 1: Strawberry capital +20, New ground +38, Hayano +100, First steps Moritani +50, Every trail in a park Moritani +150, Fog +25; "Your walks so far +0" |
| `t-06-challenges-android.png` | Just you: **Uphill week · Done · "492 of 492 ft climbed" · +100**; **Park hop · Done · "3 of 3 parks" · +100**; Weekend sweep · Done · "3 of 3" · +100 (F1); Whole island: Kraken week 0 of 2 |
| `t-07-me-strip-android.png` | Me › Trail milestones directly under the Play card: Climb 820 "534 / 820", Trail miles 5 "2.5 / 5", Parks 19 "1 / 19", High-ground 10 "2 / 10" (4th badge clipped at the right edge, F4); Leaderboard #1 of 1; "Progress backed up 4 min ago" |
| `t-08-me-sheet-light-android.png` / `-2` | the sheet: Climb "So far: 534 ft", 328 earned / 820 next 534/820 / 1,640 … 4,101 locked; Trail miles "So far: 2.5 mi", 1 earned / 5 next / 10, 15 locked; Parks "1 of 19 finished · 2 started": Gazzam Lake Preserve **Started**, Grand Forest **Started**, Moritani Preserve **Finished**, the rest Not yet |
| `t-07-me-strip-dark-android.png` / `t-08-me-sheet-dark-android.png` | the same under `cmd uimode night yes` (earned fill tertiaryContainer, arc seaGlass) |
| `t-09-me-font2-android.png` / `-sheet` | font scale 2.0: strip titles wrap, badges keep 96 dp, row text wraps; sheet badges FlowRow-wrap |
| `t-10-roads-me-android.png` | roads APK, Play on (chip "0 marks · Walk-on"): Me shows the roads Milestones strip and no "Trail milestones" (UX-1) |
| `t-02-results-celebrate-ios.png` / `t-02-results-ios.png` / `-2-ios.png` | iOS walk 1: "Walk saved 9:10 · 0.4 mi", Hayano 0 → 96 %, 383 marks this walk: New ground +38, Hayano Trail +100, Moritani Preserve first new ground +50, Moritani Preserve every trail +150, Fog lifted +25, Strawberry capital +20; New rank Dock Walker |
| `t-03-results-celebrate-ios.png` / `t-03-results-ios.png` / `-2-ios.png` | iOS walk 2: "Trail complete · Peter's Trail · 92 % · +590 marks · New rank Beachcomber"; rows per § iOS |
| `t-04-results-celebrate-ios.png` / `t-04-results-ios.png` / `-2-ios.png` | iOS walk 3: "2 trails complete … +882 marks · Salal Scout"; "Walk saved 48:13 · 2.2 mi", Island 1.3 → 3.7 %; rows per § iOS, "+5 more in your Logbook" |
| `t-05-logbook-ios.png` | iOS Logbook: 1,855 marks · 1,855 this week · Salal Scout · 1,145 to Madrona Wanderer; Sat, Oct 31; Uphill week +100, Park hop +100, Weekend sweep +0, Riddle +0, Sea glass on Grand Forest East Trail +25, New ground +257, GF East +100, GF'e' East +100, GF East steep +50 … |
| `t-07-me-top-ios.png` / `t-07-me-strip-ios.png` | iOS Me: Play card (1,855 · Salal Scout), Trail milestones "534 / 820", "2.4 / 5", "1 / 19", "2 / 10" (clipped), Leaderboard #1 of 1, "Progress backed up 1 min ago" |

## Assertions (plan § W4), Android

| # | assertion | result | evidence |
|---|---|---|---|
| 1 | Logbook total = D1 `play_totals.total`; `trail_marks = 500` | **PASS**: Logbook 1,878 = Σ walks 383 + 610 + 885 = D1 `total 1878`; `trail_marks 500` (= 50 + 150 + 50 + 50 + 50 + 50 + 50 + 50 ledger), `found_marks 380` | t-05; D1 below |
| 2 | `/v1/play/me?dataset=trails`: perKind climb 1, high_ground 2, milestone 1, park_first 3, park_complete 1, island 0; challenge 2; `paid` = 8 keys | **PASS** for the six trails kinds and the 8 keys (`climb:100`, `high:` ×2, `mile:1`, `park1:` ×3, `parkc:Moritani Preserve`); **challenge = 3, not 2** — `ch-sweep@2026-10-31` claimed at 0 marks (F1, plan expectation) | `/me` body below |
| 3 | Challenges both Done and clamped | **PASS**: "492 of 492 ft climbed · Done · +100", "3 of 3 parks · Done · +100"; D1 `ch-climb@2026-10-26` 100, `ch-parkhop@2026-10-31` 100 | t-06 |
| 4 | Me strip "522 / 820", "1 / 19", "2 / 10", Trail miles = floor₁(coveredM / 1609.344); sheet Moritani Finished, Gazzam + Grand Forest Started | **PASS with F2**: "534 / 820" (Σ climb 162.8 m incl. the duplicate trail's 3.6 m, not 159.2), "1 / 19", "2 / 10", "2.5 / 5" = floor₁(4081.6 m / 1609.344 = 2.536); sheet exactly as expected | t-07, t-08; `/v1/coverage` `metrics.coveredM 4081.6` |
| 5 | roads APK, Play on: no strip, no new card | **PASS**: roads Me = Play card + roads Milestones (1 mi / first road) + By road type + Leaderboard; no "Trail milestones" | t-10 |
| 6 | dark theme + font 2.0 on strip/sheet; TalkBack/VoiceOver spot checks | **PASS** (shots); TalkBack **not run** (uiautomator content-descriptions read instead: badge a11y "Next: 820 feet climbed, 534 of 820 feet", "Next: Parks finished, 1 of 19", park rows "Gazzam Lake Preserve / Started", card "Uphill week. Just you. Mon – Sun. 492 of 492 feet climbed. Done. +100"); VoiceOver not run | t-07/08 dark, t-09; `uiautomator dump` text |
| 7 | verify.full + verify.api | not run here (kmp-verifier lane) | — |

D1 after walk 3 (`npx wrangler d1 execute bainbridge-trails --local --command "SELECT total, trail_marks, found_marks FROM play_totals WHERE dataset='trails'"`):

```
[{"results":[{"total":1878,"trail_marks":500,"found_marks":380}],"success":true,"meta":{"duration":0}}]
```

`SELECT dataset, occ, kind, marks FROM play_claims ORDER BY occ`:

```
trails  ch-climb@2026-10-26                   challenge  100
trails  ch-parkhop@2026-10-31                 challenge  100
trails  ch-sweep@2026-10-31                   challenge    0
trails  egg-strawberry@2026-10-26             egg         20
trails  lore-gazzam@2026-10-26                lore        15
trails  riddle-bond@2026-10-26                riddle       0
trails  riddle-steep@2026-10-26               riddle     120
trails  treasure-weekly-trails@2026-10-26#01  treasure    25
```

`GET /v1/play/me?dataset=trails` (bearer = the emulator app's own session; no token or user id in the body):

```json
{"dataset":"trails","weekId":"2026-10-26","total":1878,"week":395,
 "perKind":{"treasure":1,"egg":1,"riddle":2,"ink":0,"lore":1,"challenge":3,
            "climb":1,"high_ground":2,"milestone":1,"park_first":3,"park_complete":1,"island":0},
 "occ":["ch-climb@2026-10-26","ch-parkhop@2026-10-31","ch-sweep@2026-10-31","egg-strawberry@2026-10-26",
        "lore-gazzam@2026-10-26","riddle-bond@2026-10-26","riddle-steep@2026-10-26","treasure-weekly-trails@2026-10-26#01"],
 "log":[],
 "paid":["climb:100","high:trail-grand-forest-east-trail","high:trail-peters-trail","mile:1",
         "park1:Gazzam Lake Preserve","park1:Grand Forest","park1:Moritani Preserve","parkc:Moritani Preserve"]}
```

`GET /v1/coverage?dataset=trails` → `completedTrailIds: [trail-grand-forest-east-trail, trail-grand-foreste-east-trail,
trail-hayano-trail, trail-peters-trail]`, `metrics: {islandPct 3.8, coveredM 4081.6, trailsCompleted 4, trailsTotal 114}`.
Wrangler request log (Android half): 3 × `PUT /v1/coverage 200` (one per walk), `GET /v1/play/me` ×9,
`GET /v1/play/challenges` ×3, `GET /v1/play/content` 200 ×3 / 304 ×7; no `/v1/play/*` before Start playing.

## iOS (iPhone 17 Pro, fresh D1, 20:24–21:55 PDT)

Same three ids via `scripts/sim-route.sh` (629 / 1,922 / 3,626 m chained; 8.1 / 24.6 / 46.5 min), Finish walk ›
Finish after each. First walk only: Record › "Ready when you are" › Start, "Location while you record" › Allow location
› Allow While Using App, Motion & Fitness › Allow (route restarted after the grant); walks 2 and 3 started recording
straight from Start walk.

| walk | iOS results (`t-0N-results[-2]-ios.png`) | Android |
|---|---|---|
| 1 Hayano | "Walk saved 9:10 · 0.4 mi", Hayano 0 → 96 %; **383 marks**: New ground +38, **Hayano Trail +100, Moritani Preserve first new ground +50, Moritani Preserve every trail +150**, Fog lifted +25, Strawberry capital +20; New rank Dock Walker | 383 |
| 2 Peter's | "+590 marks", Peter's 0 → 92 % (overlay) / rows: New ground +105, **Peter's Trail +100, Peter's Trail steep +50, Gazzam Lake Preserve first +50, "328 ft climbed" +50**, Fog +100, **Walking day +15**, Riddle walked to +120; New rank Beachcomber. No Gazzam lore hop on iOS (−15 vs Android) | 610 |
| 3 Grand Forest East | "2 trails complete … +882 marks", New rank Salal Scout; rows: New ground +257, **GF East +100**, GF'e' East +100 (F2), **GF East steep +50, Grand Forest first +50, "1 mi of trail walked" +50**, Fog +50, "+5 more in your Logbook" (Uphill week +100, Park hop +100, Weekend sweep +0, Riddle +0, Sea glass +25 — all in `t-05-logbook-ios`) | 885 |

| # | assertion | result | evidence |
|---|---|---|---|
| 1 | Logbook total = D1 total; `trail_marks = 500` | **PASS**: Logbook "1,855 marks · 1,855 this week · Salal Scout · 1,145 to Madrona Wanderer" = 383 + 590 + 882 = D1 `{"total": 1855, "trail_marks": 500, "found_marks": 365}` | `t-05-logbook-ios.png` |
| 2 | `/me` perKind + `paid` | **PASS** (same F1): `climb 1, high_ground 2, milestone 1, park_first 3, park_complete 1, island 0`, `challenge 3`, `lore 0`; `paid` = the same 8 keys | body below |
| 3 | Challenges Done + clamped | not screenshotted on iOS; D1 `ch-climb` 100, `ch-parkhop` 100 and the Logbook rows "Uphill week +100 / Park hop +100 / Challenge complete" | `t-05-logbook-ios.png` |
| 4 | Me strip + sheet | **PASS (strip)**: "534 / 820", "2.4 / 5" (= floor₁(4000.1 / 1609.344 = 2.485)), "1 / 19", "2 / 10" (4th badge clipped, F4); the sheet was not opened on iOS | `t-07-me-strip-ios.png`, `t-07-me-top-ios.png` |
| 6 | VoiceOver | not run | — |

D1 after walk 3 (iOS user): `play_totals` `[{"total": 1855, "trail_marks": 500, "found_marks": 365}]`; `play_claims`:

```
trails  ch-climb@2026-10-26                    challenge   100
trails  ch-parkhop@2026-10-31                  challenge   100
trails  ch-sweep@2026-10-31                    challenge     0
trails  egg-strawberry@2026-10-26              egg          20
trails  riddle-bond@2026-10-26                 riddle        0
trails  riddle-steep@2026-10-26                riddle      120
trails  treasure-weekly-trails@2026-10-26#01   treasure     25
```

`GET /v1/play/me?dataset=trails` (bearer = the simulator app's `Application Support/private/session.json`):

```json
{"dataset":"trails","weekId":"2026-10-26","total":1855,"week":380,
 "perKind":{"treasure":1,"egg":1,"riddle":2,"ink":0,"lore":0,"challenge":3,
            "climb":1,"high_ground":2,"milestone":1,"park_first":3,"park_complete":1,"island":0},
 "occ":["ch-climb@2026-10-26","ch-parkhop@2026-10-31","ch-sweep@2026-10-31","egg-strawberry@2026-10-26",
        "riddle-bond@2026-10-26","riddle-steep@2026-10-26","treasure-weekly-trails@2026-10-26#01"],
 "log":[],
 "paid":["climb:100","high:trail-grand-forest-east-trail","high:trail-peters-trail","mile:1",
         "park1:Gazzam Lake Preserve","park1:Grand Forest","park1:Moritani Preserve","parkc:Moritani Preserve"]}
```

`GET /v1/coverage?dataset=trails` → the same 4 `completedTrailIds`, `metrics: {islandPct 3.7, coveredM 4000.1,
trailsCompleted 4, trailsTotal 114}`. Wrangler: 3 × `PUT /v1/coverage 200`.

Cleanup: wrangler stopped; the pre-run `backend/.wrangler/state` was put back (the Android-run and iOS-run D1 states
are kept in the session scratchpad, not in the repo); emulator `font_scale` 1.0 and night mode off; emulator and
simulators left running.

## Findings

- **F1 (plan expectation, not a bug): `perKind.challenge` = 3.** `ch-sweep@2026-10-31` ("Weekend sweep", 3 trails
  Sat–Sun) shares the Park hop window and the same three walks complete it, so the server also claims it — at 0 marks
  because the walk's found marks had hit the daily cap ("Daily limit reached. Found, no marks" in the Logbook; the
  Riddle `riddle-bond` row on the same walk is capped the same way). The script now accepts 3 when `ch-sweep@` is in
  `occ`. The Challenges card still shows "+100" on the Done chip row while the Logbook row says +0 — worth a look
  (the card shows the reward, not what was paid).
- **F2 (dataset): `trail-grand-foreste-east-trail`.** The dataset has a second, near-identical trail named "Grand
  Foreste East Trail" (typo) overlapping Grand Forest East; the replay completed both ("2 trails complete" overlay,
  two Trail complete +100 rows, Σ climb 534 ft instead of the plan's 522 ft, `completedTrailIds` 4). Not an app bug;
  the plan's 522 assumes the duplicate does not exist. Fix belongs in `pipeline/` (dedupe or rename).
- **F3 (walk 3 replay length):** `emu-route.sh` chains Grand Forest East's 12 segments into 3,626 m (46.5 min),
  twice the trail's 1,778 m, with hops onto Main Loop (25 %), McRedmond (15 %) and Hilltop (4 %) — hence New ground
  +260 and Fog +50 beyond the plan's rows. The asserted rows are unaffected; the fallback trail was not needed.
- **F4 (UX, minor): the strip's fourth badge is clipped** at the right screen edge at font 1.0 (`High-ground trails`
  shows about two thirds; the LazyRow scrolls). ux.md § 5.1 says "always 4 badges"; on this 1280 px / 411 dp
  emulator 4 × 96 dp + 3 × 12 dp + padding does not fit without scrolling.
- **F5 (UX, minor): the results sheet shows at most ~8 Play rows** ("+2 more in your Logbook"); walk 3's Milestone,
  Uphill week, Park hop and treasure rows were only visible in the Logbook. By design (`../ux.md` § 3.9), noted for the
  screenshots.
- **Not verified here:** TalkBack and VoiceOver spot checks (content-descriptions read via uiautomator instead); the
  P2-1..P2-4 fixes of `8d9c324` (binaries are `bfdce9c`); AC 7 unit test (D8) and verify.full / verify.api
  (kmp-verifier lane).
