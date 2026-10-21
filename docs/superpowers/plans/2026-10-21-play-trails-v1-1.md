# Play — Trails edition (v1.1): staged implementation plan

Date: 2026-10-21 · Role: plan-author · Branch `play/trails-v1-1` (cut from `origin/main` 2aee859).
Inputs: `docs/play/trails/brief.md` (rules § 2, ACs § 4, Q1–Q5 defaults accepted), `docs/play/trails/ux.md`
(ACCEPTED; § 10 build notes binding), `docs/play/trails/lore-facts.md`, `docs/play/plan.md` (stage format, § 3 e2e),
`docs/play/decisions.md` PD-1…PD-25, `product.md` § 3, `schedule.md` § 4–§ 7. Where a spec and this plan disagree the
spec wins, except the deviations stated in § 1 (each to be recorded in PD-26). Vocabulary: marks, Logbook, challenge.

```
PLAN: Play trails v1.1 — six trails rules, two challenges, trails content, Shipyard trails build
      (acceptance criteria satisfied: 1–10; AC 5 content by the concurrent lane C0, validated here)
PRECONDITIONS:
  - C0 (concurrent content lane) committed on this branch: play-content.json 2026.11.2 with ≥ 12 trails-only items,
    build-play-content.mjs writing trails.json `featurePark` / `featureGainM` / `parks`, regenerated copies,
    `node --test scripts/*.test.mjs` green. No stage below edits C0's files until C0 is committed.
  - ux.md ACCEPTED (gate design_before_build: met). No new ADR: everything rests on PD-1…PD-25 + PD-26 (D1 writes it).
  - Owner-only, NOT in this item: Worker deploy + `migrate:remote` of 0007 (OD-19 first), destructive D1 work.
  - Every implementer branch is cut after `git pull` of origin/main and merged back into play/trails-v1-1.
```

## 1. Decisions this plan makes (record in PD-26)

- **D1 Device ledger.** `PlayState` gains `trailPaid: Set<String> = emptySet()` and `trailsStarted: Boolean = false`
  (old files decode: `ignoreUnknownKeys`, defaults). Keys, by value, never re-derived: `climb:<m>` (100…1250),
  `mile:<mi>` (1, 5, 10 … 60), `high:<trailId>`, `park1:<park>`, `parkc:<park>`, `island`. The key is also the
  Logbook `contentId` (dedupe `(kind, contentId)`), so ux § 2.2's `climb:250` / `mile:10` hold. Paid = in the ledger;
  owed = `qualifying(snapshot) − trailPaid`. A dataset that drops a trail lowers nothing; one that adds a trail pays
  only keys not yet in the ledger (AC 2 monotone).
- **D2 Server ledger: additive migration `0007_play_trails.sql`.** One MAX'd `derived_marks` cannot hold "paid by
  value" (a dataset drop in one rule would mask a new step in another) and cannot answer per-kind counts or seed a
  restore. Adds: `play_trail_paid(user_id, dataset, key, marks, PK(user_id,dataset,key))` WITHOUT ROWID, FK cascade;
  `ALTER TABLE play_totals ADD COLUMN trail_marks INTEGER NOT NULL DEFAULT 0`;
  `play_progress_keys(user_id, dataset, occ, key, PK(all four))` (ch-parkhop distinct parks, D5). Inserts are
  `ON CONFLICT DO NOTHING`; the totals upsert sets `trail_marks = (SELECT COALESCE(SUM(marks),0) FROM play_trail_paid
  WHERE user_id=?1 AND dataset=?2)` in the same batch and every `total =` expression adds it (race-free, idempotent).
  Ledger inserts are allowed on the no-change upload path (idempotent, derived); `assertNoPlayWrites` documents it.
  Holds no time, day or position; values derivable from coverage bits + completions the server already stores.
- **D3 `GET /v1/play/me`, dataset=trails only:** `perKind` gains `climb, high_ground, milestone, park_first,
  park_complete, island` (counts of ledger keys by prefix) and the body gains `paid: string[]` (sorted keys). Roads
  body byte-identical (guard test). Restore: `trailPaid ∪= me.paid`, then D4's catch-up pays the rest locally.
- **D4 Start balance (ux § 6).** `TrailStart.onEnable` runs in `creditOnce` on every turn-on/start, trails only, after
  `StartBalance`: owed keys → one `start_trails` entry (what "Trail rewards so far" when `!trailsStarted`, else "Trail
  rewards while Play was off"), `island` as its own entry; nothing owed → nothing written; sets `trailsStarted`. After
  a restore it still runs (owed is relative to the server's `paid`). `WalkMarks` writes trails rows only when
  `trailsStarted` (an upgrade walk before the first credit is folded into `start_trails`). Server equivalent: the
  first Play upload inserts every qualifying key (pays once); uploads without `play` (Play off) touch no Play table.
- **D5 Challenge target (Q3), both sides:** `target = min(default, R)`, `R = remaining(C) + P`, evaluated after the
  walk/upload: C = sticky completed set, P = occurrence progress. climb: integers in **decimetres** (`round(gain_m×10)`),
  remaining = Σ dm of non-quiet trails ∉ C; parks: remaining = |{Play parks with ≥ 1 non-quiet trail ∉ C} ∪ parks
  already in P|. Since every in-window Play completion moves value from remaining to P, R equals "remaining at window
  start" (ux § 7). R = 0 → no card, no chip count, no What's On row; no claim. Server: claim accepted iff `P ≥ target
  > 0`; `GET /v1/play/challenges` returns the personal `target` when signed in, the default otherwise; climb
  `progress`/`target` are dm (api.md). Client P = this walk + Logbook `complete` entries dated inside the window
  (validator forces climb/parks windows to whole island days, so a date is exact; no new timestamps persisted).
- **D6 Numbers.** Climb compares integer dm (99.9 m = 999 < 1000). Mile steps on `nonQuietCoveredM / 1609.344` with
  the `Milestones` ladder (1, then every 5; EPS 1e-9). AC 2's "1,609 m → 1 mi" is read as one statute mile:
  tests pin 1,609.344 m → `mile:1`, 1,609.0 m → none (deviation, stated in PD-26). W-ISLAND = island fraction
  ≥ 1 − 1e-9 (`AppGraph.Celebration.WHOLE`; server `islandM ≥ islandTotalM × (1 − 1e-9)`). Park sets come only from
  counted features (Kotlin `Progress.counts`, server `METAS.trails`); Pritchard via `pack.excludedParks`.
- **D7 Downgrade (ux § 2.1): refused, documented, tested at the boundary.** New `LogKind`s append to the enum (names
  are the stored keywords). A v1 build reading a v1.1 state file fails to decode the enum → `JsonFile` reads null →
  empty Play state on that phone (server total intact; a signed-in turn-on restores one "Restored" entry). Shipyard
  only moves forward; a manual rollback is the owner's call. Test pins: v1 state JSON (captured from 2aee859) decodes
  in v1.1 with every v1 kind; all v1.1 kinds round-trip.
- **D8 Upload contract unchanged (AC 7).** No edit to `net/ApiDtos.kt` or `PlayWalkClaimsDto`; `walks[]` and `play[]`
  key sets pinned from 2aee859. Trails ledger keys never leave the phone (server derives its own).
- **D9 Roads guard of `roads.json`:** the file embeds `"pack"` (now `2026.11.2`), so main's sha `ad2c1435…` cannot
  survive any pack bump. Guard = sha256 of roads.json **without the `"pack":` line** = `4ea0afa8ea4c134b…f44b9`
  (computed from 2aee859, equal on the branch today).
- **D10 Pack version.** `published/2026.11.2.json` exists, so ch-climb/ch-parkhop ship in **2026.11.3** (L4-2).
- **D11 Before the owner deploys the Worker** (Shipyard reality): trails rows settle with the walk's upload as today
  (old Worker answers a `play` block) though it paid nothing; ch-climb/ch-parkhop claims come back `unknown_item` →
  `local_only`. After deploy + 0007, the first Play upload inserts every qualifying key, so totals converge.

## 2. Lanes, owners, waves

| lane | owns | agent |
|---|---|---|
| L1 `:play` rules | `app/play/**` | kmp-implementer |
| L2 composeApp | `app/composeApp/**`, `app/iosApp/**` | kmp-implementer (Android + iOS) |
| L3 Worker | `backend/src/**`, `backend/migrations/0007_play_trails.sql`, `backend/test/**`, `docs/mvp/api.md` | backend implementer |
| L4 data | `data/play/**`, `scripts/build-play-content.mjs` (+test), generated pack copies, goldens | content/Node implementer |

Single owner per shared file: `strings.xml` → L2-1 · `PlayConfig.kt` + `PlayConfigValidator.kt` + `Logbook.kt` +
`LogCopy.kt` → L1-1 · `net/PlayDtos.kt`, `PlayRepository.kt`, `PlayDerived.kt`, `PlayStateStore.kt`, `WalkMarks.kt`,
`graph/AppGraph.kt` → L2-2 · `MeScreen.kt`, `nav/AppShell.kt` → L2-4 · `play-content.json`,
`build-play-content.mjs` → C0, then L4-2 only · `derived.ts`, `trails.ts`, `routes/play.ts`, `routes/coverage.ts`,
`routes/me.ts`, `0007` → L3-1 · `claims.ts` → L3-1 then L3-2 (serial) · `challenges.ts` → L3-2 ·
`data/play/golden/{trails-rewards,roads-guard}.json` → G0 (read-only afterwards) · `docs/mvp/api.md` → L3-2.

| wave | parallel group (≤ 3) | depends on |
|---|---|---|
| W0 | **G0** goldens (serial) | C0 committed |
| W1 | **L1-1** rules · **L3-1** server ledger · **L2-1** strings, format, glyph, badge colors | G0 |
| W2 | **L4-2** metrics validator + 2 challenges + 2026.11.3 · **L2-2** client data path · **L3-2** challenges | L1-1 (L4-2, L2-2); L3-1 (L2-2 DTO, L3-2) |
| W3 | **L2-3** Logbook, results, challenge cards · **L2-4** Me strip + sheet · **D1** docs | L2-1, L2-2, L4-2 |
| W4 | **E2E** trails run, Android + iOS (serial) | all |
| W5 | **REVIEW** → fixes → **R** PR, merge, Shipyard (serial) | E2E |

L3-2 rebases on L4-2 before its last verify (L4-2 merges first: its `current pack` test needs the items).

## 3. Stages

**G0 — goldens before any rule code** [L4, serial]
- intent: one shared parity fixture and the AC 1 roads goldens captured on main.
- files: `data/play/golden/trails-rewards.json` (hand-computed from brief § 2 over the committed real
  `derived/trails.json`: per case `{input: {nonQuietCoveredM, touchedTrails[], completed[], islandWhole},
  paidBefore[], expectNewKeys[], expectMarks: {climb, high_ground, milestone, park_first, park_complete, island}}`
  plus `challengeCases: {metric, default, completed[], progress, expectTarget}`); `data/play/golden/roads-guard.json`;
  guard tests `backend/test/play-roads-guard.test.ts`, `app/play/src/jvmTest/.../rules/RoadsGuardTest.kt`,
  `app/composeApp/src/commonTest/.../play/RoadsGuardTest.kt`.
- capture: `git worktree add /tmp/bt-main 2aee859`, copy the three guard tests in, run with `PLAY_GOLDEN_WRITE=1`
  (server: roads `walkDerivedMarks` on 3 fixture coverage states, roads `playStatements` totals, `/v1/play/me?dataset=
  roads` body, `challengesOf(CURRENT,"roads")` ids+specs; Kotlin: roads `StartBalance.onEnable` and
  `WalkMarks.apply` entries for a fixture, bundled-pack roads challenge list, v1 `walks[]`/`play[]` key sets, a v1
  `PlayState` JSON with every v1 kind), copy the JSON back; branch runs assert mode.
- cases required (AC 2): climb 99.9 → none; 100.0 → `climb:100`; partial coverage of Peter's (92.1) → none; 1,368.8 →
  6 steps = 300; steep 40.0 → +50, 39.9 → 0, Blakley Cemetery (66.5, quiet) → 0, re-completion → 0; 1,609.344 m →
  `mile:1`, 1,609.0 → none, 102,964 m → 13 steps = 650; first segment in Gazzam → `park1:Gazzam Lake Preserve`,
  second case same park → none; Pritchard Trail touched → none; Hayano completed → `park1` + `parkc` Moritani
  (+50, +150); island → +1,000 once; fixture table minus a trail → nothing lowered; plus a trail → only its keys.
- test-first: `play-roads-guard.test.ts › roads walkDerived, totals, me body, challenge list equal main`;
  `play-roads-guard.test.ts › data/play/derived/roads.json minus the pack line has sha256 4ea0afa8…` (D9);
  Kotlin `RoadsGuardTest › roads start balance, walk marks, challenge list equal main`
- verify: `node --test scripts/*.test.mjs` + verify.api + verify.kmp (all green: guards pass on unchanged code)
- rollback: delete the files

### W1

**L1-1 — trails rules, start, Logbook kinds, challenge metrics, claim decision** [L1]
- files: `play/rules/TrailRewards.kt` (new: `TrailTables(featurePark, featureGainDm, parks, quietFeatures)`,
  `TrailRewardInput`, `CLIMB_STEPS_M = [100,250,500,750,1000,1250]`, `qualifying(input, tables): Set<String>`,
  `marksOf(key)` (+50; parkc +150; island +1,000), `kindOf(key)`, `entries(newKeys, names, date, walkId, newId)`,
  `challengeTarget(metric, default, completed, progressKeysOrDm, tables)`), `play/rules/TrailStart.kt` (new, D4),
  `rules/Logbook.kt` (append `climb, high_ground, start_trails`; not `walkDerived`, not `found`), `rules/LogCopy.kt`
  (ux § 2.2/§ 6 lines for climb, high_ground, milestone, park_first, park_complete, island, start_trails,
  `catchUpTrails`), `config/PlayConfig.kt` (`ChallengeMetric` += `climb, parks`), `config/PlayConfigValidator.kt`
  (climb/parks: scope solo, goals exactly `[trails]`, target keyed `trails` only, window whole days),
  `claim/ClaimEvaluator.kt` (solo climb/parks claims from this walk's completed ids + in-window earlier ids, D5)
- test-first (`:play` commonTest): `TrailRewardsTest` (every G0 case by name, e.g. `climb 99_9 m pays nothing`,
  `steep 40_0 pays 50 and 39_9 pays 0`, `quiet trail gain 66_5 never pays`, `one-trail park pays 50 and 150 same walk`,
  `Pritchard Park never pays`, `dropping a trail lowers nothing`, `adding a trail pays only its keys`, `keys are unit
  free`); jvmTest `TrailRewardsGoldenTest › every case of trails-rewards.json`; `TrailStartTest` (`opt-in with prior
  progress writes start_balance then start_trails then island` (AC 3), `off then on with nothing new writes nothing`,
  `off then on with new keys writes one catch-up`, `restore paid keys are not paid again`, `roads never writes
  start_trails`); `LogCopyTest` (new lines pinned verbatim); `LogbookTest › trails kinds appended, not walkDerived,
  not found`; jvmTest `LogKindCompatTest` (D7: G0's v1 JSON decodes; every kind round-trips); `PlayConfigParseTest ›
  climb and parks parse`; `PlayConfigValidatorTest` (`climb on roads rejected`, `parks window not whole days rejected`,
  `roads target on climb rejected`); `TrailChallengeTest` (`149_9 m does not claim, 150_0 m claims 100`, `2 parks no
  claim, 3 claims`, `completion outside window ignored`, `quiet trail and Pritchard Park add nothing`, `same park
  twice counts once`, `claims once per occurrence`, `120 m remaining → target 120`, `nothing remaining → no claim`)
- verify: `cd app && ./gradlew :play:jvmTest`, then verify.kmp
- rollback: revert (no production caller until L2-2)

**L3-1 — server ledger, 0007, `/me`** [L3]
- files: `migrations/0007_play_trails.sql` (D2), `src/play/derived.ts` (load `featurePark`, `featureGainM`→dm,
  `parks`; trails only), `src/play/trails.ts` (new: TS mirror of `qualifying`/`marksOf`/`challengeTarget`; input from
  merged coverage + completed + `metricsFor` islandM), `src/play/claims.ts` (`playStatements`: ledger inserts + totals
  `trail_marks`; `loadPlayState` reads the ledger), `src/routes/coverage.ts` (pass completed + islandM; dataset trails
  only), `src/routes/play.ts` (D3), `src/routes/me.ts` (DELETE removes `play_trail_paid`, `play_progress_keys`)
- test-first: `test/play-trails.test.ts` (`golden trails-rewards cases equal Kotlin` (parity), `first Play upload with
  prior completions credits each rule once` (AC 3), `second upload pays 0`, `upload without play writes no ledger
  row`, `off-period progress paid once on next Play upload`, `dataset drop lowers nothing`, `total = cov + derived +
  found + week + trail_marks`, `no-change upload inserts ledger keys only`, `me trails perKind and paid`, `delete
  removes ledger and progress keys`); `migrations.test.ts › 0007 applies after 0006, additive only`;
  `play-roads-guard.test.ts` still green
- verify: verify.api
- rollback: Worker revert; 0007 is additive and inert for old Workers

**L2-1 — strings, `TrailRewardFormat`, `FxGlyph.Summit`, badge colors** [L2]
- files: `composeResources/values/strings.xml` (every ux § 8 key; no roads twin), `ui/play/TrailRewardFormat.kt` (ux § 1
  floor/nearest/clamp, thousands separators, spoken units), `play/fx/FxArt.kt` (`FxGlyph.Summit`, ux § 2.3),
  `ui/components/RoadComponents.kt` (`MilestoneMedallion`/`MilestoneBadge` optional colors, roads default unchanged)
- test-first (host): `TrailRewardFormatTest` (UX-3: `99_9 m shows 327 ft`, `100 m step shows 328 ft`, `250 m = 820 ft`,
  `149_9 of 150 m shows 149 of 150 m`, `150 of 150 m`, `10 mi step = 16.1 km`, `1,207 ft`, `ladder equals
  Milestones.distanceStep`); `GoalVocabularyGuardTest` (keys with "trail" in `TRAIL_ONLY`); `PlayCopyTest` (blocklist
  over new keys); `MilestoneColorsTest › default colors equal the roads constants`
- verify: verify.kmp + `cd app && ./gradlew :androidApp:assembleRoadsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
- rollback: revert (unreferenced until W3)

### W2

**L4-2 — metrics in the Node validator, `ch-climb` + `ch-parkhop`, pack 2026.11.3** [L4]
- precondition: L1-1 merged (Kotlin parses `climb`/`parks`; `PlayConfigBundledTest` would fail otherwise).
- files: `scripts/build-play-content.mjs` (+ `.test.mjs`), `data/play/play-content.json` (two items, ux § 7 copy,
  `goals:["trails"]`, `ch-climb` `every: week, on: [mon], from 00:00, to 24:00, days 7`, target `{trails:150}`;
  `ch-parkhop` as `ch-sweep`'s window, target `{trails:3}`; marks 100), `data/play/published/2026.11.3.json`,
  regenerated copies (backend + composeResources), `app/play/src/jvmTest/resources/golden/agenda-trails.tsv`,
  `docs/play/e2e-ids.env` (via `./gradlew :play:jvmTest -Pplay.updateGolden=true`)
- test-first: `build-play-content.test.mjs` (`accepts climb and parks for trails`, `rejects climb on roads`, `rejects
  parks window not whole days`, `rejects unknown metric`, `committed pack is 2026.11.3`)
- verify: `node --test scripts/*.test.mjs && node scripts/build-play-content.mjs --check && git diff --exit-code 2aee859
  -- app/play/src/jvmTest/resources/golden/agenda-roads.tsv` + `cd app && ./gradlew :play:jvmTest` + verify.api
- PR artifact: 4-week trails agenda (2026-10-26 → 2026-11-23 rows of agenda-trails.tsv) pasted in the PR (AC 5).
- rollback: revert to 2026.11.2 (unpublished on main)

**L2-2 — client data path** [L2]
- files: `play/PlayDerived.kt` (parse `featurePark`, `featureGainM`, `parks` → `TrailTables`, null on roads),
  `play/PlayStateStore.kt` (`PlayState.trailPaid`, `trailsStarted`), `play/WalkMarks.kt` (trails rows when
  `trailsStarted`; `SETTLED_WITH_WALK` += climb, high_ground, milestone, park_first, park_complete, island),
  `play/PlayRepository.kt` (`trailInput` provider; `creditOnce` → `TrailStart`; `restore` seeds `me.paid`; claim
  inputs for climb/parks; challenge targets for UI), `PlayRepository.kt` `SnapshotMarks.trailInput(...)`,
  `net/PlayDtos.kt` (`PlayMeDto.paid: List<String> = emptyList()`), `graph/AppGraph.kt` (wire providers, trails only)
- test-first (host): `WalkMarksTrailsTest` (`Hayano walk writes complete, park_first, park_complete pending`, `walk
  before trailsStarted writes no trails rows`, `rows settle with the walk`); `PlayRestoreTest › restore seeds trailPaid
  from me.paid and pays only the rest`; `PlayStatePrivacyTest › trail kinds and trailPaid hold no time of day`;
  `TrailsUploadKeysTest › Play on after every rule: walks[] and play[] key sets equal G0's v1 sets` (AC 7);
  `SyncRepositoryTest` Play-off byte-identical (existing, green); `PlayStateCompatTest › v1 state decodes, defaults`;
  `RoadsGuardTest` green; `PlayOffNothingCreatedTest` green
- verify: verify.kmp + `cd app && ./gradlew :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug
  :composeApp:linkDebugFrameworkIosSimulatorArm64`
- reachability: start balance runs from the Settings toggle / app start (existing); walk rows from the Finalizer hook
- rollback: revert; new state fields default and are ignored by the v1 code path

**L3-2 — `ch-climb` / `ch-parkhop` on the server** [L3]
- files: `src/play/challenges.ts` (`challengeDef` solo climb/parks; `completionProgress` adds dm / park keys; D5
  target; `challengesBody` personal target; cron purges `play_progress_keys` once the occurrence is past its grace),
  `src/play/claims.ts` (challenge path reads `play_progress_keys`), `docs/mvp/api.md` (`/me` trails fields,
  challenges climb dm, 0007)
- test-first: `test/play-trails-challenges.test.ts` (`ch-climb 149.9 m no_bits, 150.0 m accepted +100`, `ch-parkhop
  2 parks no_bits, 3 accepted`, `completion after Mon 00:00 not counted`, `quiet trail and Pritchard Park add
  nothing`, `same park in two uploads counts once`, `instance pays once`, `day cap and week cap still bind`, `120 m
  remaining → target 1200 dm`, `remaining 0 → not listed for the user`, `anonymous gets default target`, `current pack:
  both trails-only, absent for roads`, `progress keys purged after grace`)
- verify: verify.api
- rollback: Worker revert

### W3

**L2-3 — Logbook rows, results collapse, challenge cards** [L2]
- files: `ui/play/components/LogbookRow.kt` (labels, `Summit` glyph, re-render climb/milestone from `contentId` via
  `TrailRewardFormat`, spoken a11y), `ui/play/screens/LogbookLogic.kt`, `ui/play/record/ResultsPlayLogic.kt` (ux § 3
  order; ≤ 8 rows, 7 + `play_results_more`), `ui/play/record/ResultsPlaySectionView.kt`,
  `ui/play/screens/ChallengesLogic.kt` (climb/parks progress text, `{target}` via format, D5 hide),
  `ui/play/map/ChallengesChip.kt` + `ui/play/screens/WhatsOnLogic.kt` (hidden card excluded)
- test-first (host): `ResultsPlaySectionTest` (`9 entries → 7 rows + "+2 more in your Logbook", total = sum of 9`,
  `8 entries → no more-line`, `high_ground before park_first, climb before milestone`); `LogbookTrailsRowTest`
  (`climb:250 → "820 ft climbed" in miles, "250 m climbed" in km`, `unparseable contentId → persisted what`, `a11y
  "820 feet climbed"`); `ChallengesTrailsTest` (`"95 of 150 m climbed"`, `"311 of 492 ft climbed"`, `"2 of 3 parks"`,
  `remaining 0 → absent from screen, chip count, What's On`, `roads model has neither`); `LogbookGroupingTest`
  `never formats a time of day` green
- verify: verify.kmp + assembleTrailsDebug + assembleRoadsDebug + linkDebugFrameworkIosSimulatorArm64
- rollback: revert

**L2-4 — Me › Trail milestones strip + sheet** [L2]
- files: `ui/play/me/{TrailMilestonesLogic,TrailMilestoneStrip,TrailMilestonesSheet}.kt` (ux § 5; model computed from
  `TrailRewardInput` + `TrailTables` off composition, null on roads), `ui/MeScreen.kt` (`key = "trail-milestones"`
  under the PlayCard), `nav/AppShell.kt` (sheet park row → Trails tab with `ParkFilter.Named`; trail row → TrailDetail)
- test-first (host): `TrailMilestonesLogicTest` (`4 badges fixed order with fixture values`, `new player "0 / 328"`,
  `sheet: 6 climb steps, miles to reachable top 60, 19 parks alphabetical without Pritchard, 10 high-ground steepest
  first`, `counts come from the tables`, `roads model null`)
- verify: verify.kmp + assembleTrailsDebug + assembleRoadsDebug + linkDebugFrameworkIosSimulatorArm64
- reachability: Me (production) → strip → sheet → Trails list / TrailDetail
- rollback: revert

**D1 — docs** [docs only]
- files: `docs/play/decisions.md` (PD-26: brief § 2 table, Q1–Q5 defaults, D1–D11 of this plan, the D6 AC 2 reading,
  the v1-client pack risk), `docs/play/product.md` (§ 3.2 six rows + Q4 overlap note, § 3.7.3 trails ≈ 29,196 vs roads
  ≈ 23,286, § 4.5 two challenge rows), `docs/play/README.md` (index row → `trails/`), `app/AGENTS.md` (Play: e2e
  trails mode)
- verify: `grep -n 'PD-26' docs/play/decisions.md && grep -n 'trails/' docs/play/README.md && grep -n 'MODE=trails'
  app/AGENTS.md`
- rollback: revert

### W4 — E2E (AC 8) [L2 + L3, serial; emulator API 37 + iOS simulator]

**Fixed inputs.** Island time **Sat 2026-10-31 10:00 PT** (17:00Z, daylight; week `2026-10-26`; `ch-climb@2026-10-26`
Mon→Mon and `ch-parkhop@2026-10-31` Sat→Mon both open). Fresh dev account; units miles. Local wrangler with
`DEV_CLOCK_OFFSET_MS = Date.parse("2026-10-31T17:00:00Z") − Date.now()`; `npm run dev` applies 0001–0007 locally.
`scripts/play-e2e.sh` gains `MODE=trails` (PKG `com.bainbridgetrails.app.dev`, `DIST=data/dist`, clock above,
`emu-route.sh <id> 2`, waits for sync between walks). Simulated routes from dataset geometry only.

| walk | trail id (park, length, gain) | expected Logbook rows (exact) |
|---|---|---|
| 1 | `trail-hayano-trail` (Moritani Preserve, one-trail park, 402 m, 8.4) | Hayano Trail +100 · First steps in a park: Moritani Preserve +50 · Every trail in a park: Moritani Preserve +150 · New ground / Fog lifted (W-COV/W-SQUARE) |
| 2 | `trail-peters-trail` (Gazzam Lake Preserve, 1,110 m, 92.1) | Peter's Trail +100 · High ground: Peter's Trail +50 · First steps: Gazzam Lake Preserve +50 · Climb "328 ft climbed" +50 (Σ 100.5 m) · Walking day +15 · coverage/squares |
| 3 | `trail-grand-forest-east-trail` (Grand Forest, 1,778 m, 58.7) | Grand Forest East Trail +100 · High ground +50 · First steps: Grand Forest +50 · Milestone "1 mi of trail walked" +50 (Σ ≈ 3,290 m) · Uphill week +100 (159.2 m ≥ 150) · Park hop +100 (3 parks) · coverage/squares |

**Assertions.** (1) Logbook total = `npx wrangler d1 execute bainbridge-trails --local --command "SELECT total,
trail_marks, found_marks FROM play_totals WHERE dataset='trails'"` total; `trail_marks = 500`. (2) `GET
/v1/play/me?dataset=trails` perKind `climb 1, high_ground 2, milestone 1, park_first 3, park_complete 1, island 0,
challenge 2`; `paid` = the 8 keys (`climb:100`, `high:` ×2, `mile:1`, `park1:` ×3, `parkc:Moritani Preserve`). (3) Challenges: both Done, "159 of 150 m" never shown (clamp → "150 of 150 m
climbed" / "492 of 492 ft"). (4) wrangler log: coverage bodies' `walks[]`/`play[]` keys = v1 sets; no new endpoint.
(5) Me strip: Climb "522 / 820", Trail miles "≈ 2.0 / 5", Parks "1 / 19", High ground "2 / 10"; sheet: Moritani
Finished, Gazzam Lake Preserve + Grand Forest Started.
(6) Roads APK, Play on: Me has no strip, no new card (UX-1). (7) TalkBack + VoiceOver spot checks (UX-8), dark theme
and font 2.0 on strip/sheet (UX-9).
**iOS half:** stop wrangler, `rm -rf backend/.wrangler/state`, restart (fresh D1); `xcrun simctl launch <udid>
com.bainbridgetrails.app -btDevSignIn 1 -apiBaseUrl http://localhost:8787 -btPlayClock 2026-10-31T10:00
-acceptSimulatedLocations 1 -btScreen settings`; `SIM=<udid> scripts/sim-route.sh` the same three ids (wait each
route's duration); screenshots of results, `-btScreen logbook`, `-btScreen me`.
- files: `scripts/play-e2e.sh`, `docs/play/trails/screens/{t-01-settings-play,t-02..04-results-walk1..3,t-05-logbook,
  t-06-challenges,t-07-me-strip,t-08-me-sheet-light,t-08-me-sheet-dark,t-09-me-font2,t-10-roads-me}-android.png`,
  `{t-02..04-results,t-05-logbook,t-07-me-strip}-ios.png`, `docs/play/trails/screens/README.md` (HEAD, commands,
  wrangler query output, findings)
- verify: assertions above + verify.full + verify.api still green
- rollback: n/a (evidence)

### W5 — REVIEW, then R

**R — PR, merge, Shipyard** [release-shepherd]: PR `play/trails-v1-1` → `main` (body: AC matrix, 4-week agenda, e2e
README link, D11 note, "no Worker deploy"); auto-merge once reviews are SHIP and CI green. From merged main
(`git checkout main && git pull`): `cd app && ./gradlew -PshipyardDeployPluginRepository=file://$HOME/.m2/repository
:androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug`; per APK `shipyard-deploy-bainbridge publish <apk>
--dry-run --json` then `publish`; `status --application-id com.bainbridgetrails.app.dev --json` shows the merge commit.
Roads APK **changes** (shared composeApp code, pack 2026.11.3 bundled) → republished. No Worker deploy, no 0007 remote.

## 4. Reachability

| new thing | production call site | stage |
|---|---|---|
| trails rows, ledger (device) | Finalizer `gatedHook` → `recordWalkMarks` → `WalkMarks` | L2-2 |
| `start_trails` | Settings › Play toggle / app start → `PlayRepository.start` → `creditOnce` | L2-2 |
| ledger, `trail_marks`, `/me` fields | `PUT /v1/coverage` (Play element) · restore on turn-on | L3-1, L2-2 |
| ch-climb / ch-parkhop | pack 2026.11.3 → Challenges screen, chip, What's On; claim on Finish → upload | L4-2, L2-3, L3-2 |
| Logbook / results rows | Logbook screen, results sheet | L2-3 |
| Trail milestones strip + sheet | Me tab → strip → sheet → Trails list (park) / TrailDetail | L2-4 |

## 5. CROSS-CUTTING
- swip: n/a (`factory.swip: false`); no new analytics event.
- debugdrawer: n/a — existing Play clock plugin + agenda dump cover the e2e; no new panel.
- atlas: n/a (`product_atlas: none`).
- goldens: `trails-rewards.json` (parity), `roads-guard.json` (AC 1), `agenda-trails.tsv` + `e2e-ids.env` regenerated
  (L4-2), `agenda-roads.tsv` byte-identical; no UI snapshot tooling → screenshots `docs/play/trails/screens/`.
- migrations: `0007_play_trails.sql` additive (2 tables, 1 column); remote apply is the owner's, with the Worker deploy.

## 6. REVIEW (ordered; reviews filed in `docs/play/trails/reviews/`)
1. adversarial-reviewer on **this plan** (spec kind) before W1.
2. privacy-security-reviewer after L3-1 + L3-2 (ledger, `play_progress_keys` park-per-weekend retention, `/me`
   fields) and after L2-2 (AC 7 key sets, state file) — location-sharing posture explicitly.
3. compose-ui-reviewer on L2-1, L2-3, L2-4 (tokens, a11y merged nodes, spoken units, derived off composition, no motion).
4. kmp-verifier at each wave end and after E2E: verify.full + verify.api + xcodebuild, read from machine output.
5. simplification-reviewer on the branch. 6. adversarial-reviewer on the branch pre-PR (AC 1–10 vs evidence).
7. privacy-security-reviewer final SHIP. 8. release-shepherd (R).

## 7. RISKS
1. **v1 clients and the new pack.** `coerceInputValues=false`: once the owner deploys a Worker serving 2026.11.3, v1
   builds reject the whole pack (keep last good). Shipyard-only today; state in PD-26.
2. **`play_progress_keys`** stores Play park names per challenge weekend (week-and-place class, as claim occurrence
   keys). Mitigated: purged after grace, deleted with the account. If privacy rejects it, fallback = per-upload distinct
   parks (a park hit in two walks counts twice) — a product change, back to the orchestrator.
3. **AC 2 "1,609 m"** read as 1 statute mile (D6); if the orchestrator wants 1,609.0 m literally, the ladder needs a
   tolerance on both sides and the golden changes.
4. **Kotlin/TS parity** of summation (mile boundary) and of "counted" features (D6); the golden pins the boundaries.
5. **Pre-deploy divergence** (D11): Logbook > server total on Shipyard until the owner deploys; converges after.
6. **C0 drift**: content placements can move `E2E_TREASURE_*` and agenda goldens; L4-2 regenerates, roads must not move.
7. **Emulator completion** of Grand Forest East (12 segments, greedy chaining) may miss the completion threshold;
   fallback walk 3 = `trail-main-loop-trail` (59.5 m, 2,437 m; Σ climb 160.0, same expected kinds).
