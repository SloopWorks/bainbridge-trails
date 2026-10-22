# Play — Trails edition (v1.1): staged implementation plan

Date: 2026-10-21 · Role: plan-author · Branch `play/trails-v1-1` (cut from `origin/main` 2aee859).
**Rev 2:** plan review round 1 (FIX-FIRST) applied — `docs/play/trails/reviews/2026-10-21-round1-plan.md`; finding
ids in brackets. Inputs: `docs/play/trails/{brief,ux,lore-facts}.md` (brief Q1–Q5 defaults accepted; ux ACCEPTED,
§ 10 binding), `docs/play/plan.md` (stage format, § 3 e2e), `docs/play/decisions.md` PD-1…PD-25, `product.md` § 3,
`schedule.md` § 4–§ 7. Specs win over this plan except the deviations in § 1, each recorded in PD-26.

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
  `mile:<mi>` (1, 5 … 60), `high:<trailId>`, `park1:<park>`, `parkc:<park>`, `island`; the key is also the Logbook
  `contentId` (dedupe `(kind, contentId)`; ux § 2.2's `climb:250` / `mile:10` hold). **paid** = `trailPaid` ∪ the
  `contentId`s of Logbook entries of kinds climb, high_ground, milestone, park_first, park_complete, island [P2-2];
  owed = `qualifying(snapshot) − paid`. A dataset that drops a trail lowers nothing; one that adds a trail pays only
  keys not yet paid (AC 2 monotone).
- **D2 Server ledger: additive migration `0007_play_trails.sql`.** One MAX'd `derived_marks` cannot hold "paid by
  value" (a drop in one rule masks a new step in another), per-kind counts or a restore seed. Adds
  `play_trail_paid(user_id, dataset, key, marks, PK(user_id,dataset,key))` WITHOUT ROWID, FK cascade;
  `ALTER TABLE play_totals ADD COLUMN trail_marks INTEGER NOT NULL DEFAULT 0`; (dropped, D12:) `play_progress_keys(user_id, dataset,
  occ, key, PK(all four))` (D12). Ledger inserts `ON CONFLICT DO NOTHING`, before the totals upsert in the same batch.
  Totals SQL [P2-1]: the VALUES list binds `(SELECT COALESCE(SUM(marks),0) FROM play_trail_paid WHERE user_id=?1 AND
  dataset=?2)` as `trail_marks`; ON CONFLICT sets `trail_marks = excluded.trail_marks`, `total = MAX(cov_marks,
  excluded.cov_marks) + MAX(derived_marks, excluded.derived_marks) + found_marks + excluded.found_marks + week_marks +
  excluded.week_marks + excluded.trail_marks`; every other `total =` in `claims.ts`/`challenges.ts` adds `trail_marks`.
  Ledger inserts are allowed on the no-change upload path (idempotent, derived; `assertNoPlayWrites` says so).
  `play_trail_paid` / `trail_marks` hold no time, day or position: they derive from coverage bits + completions.
- **D3 `GET /v1/play/me`, dataset=trails only:** `perKind` gains `climb, high_ground, milestone, park_first,
  park_complete, island` (ledger key counts by prefix); the body gains `paid: string[]` (sorted). Roads body
  byte-identical (guard). **Restore [P0-1, P1-7]:** `restorable` = no `restored` entry and every Logbook entry ∈
  {start_balance, start_trails, island with `walkId == null`}. On the Restored write only: `owed = me.total −
  credited.total + Σ marks(locally start-paid keys ∖ me.paid)` and, in that same update, `trailPaid ∪= me.paid`. A
  restore without a Restored write (foreground, sign-in) never seeds `trailPaid`.
- **D4 Start balance (ux § 6).** `TrailStart.onEnable` (trails only) runs in `creditOnce` after `StartBalance`, on
  every turn-on/start, reading paid **inside** the `store.update`: owed keys → one `start_trails` entry (what "Trail
  rewards so far" when `!trailsStarted`; later catch-ups what "Trail rewards caught up", why "Trail rewards owed from
  earlier walks" [P2-8, ux § 6 copy deviation]), `island` as its own entry; nothing owed → nothing; sets
  `trailsStarted`. **Race [P1-8]:** when `!trailsStarted`, `WalkMarks.apply` runs `TrailStart` on its own `after`
  input inside the same update (writes `start_trails`, sets the flag), then the walk's rows — a walk finalized during
  the turn-on restore pays every key once. `awaitStartBalance` returns start_balance + start_trails + island written
  by this turn-on [P2-5] (one PlayWake number, ux § 6). Server equivalent: the first Play upload inserts every
  qualifying key; uploads without `play` (Play off) touch no Play table.
- **D5 Challenge target (Q3), both sides:** `target = min(default, R)`, `R = remaining(C) + P` after the walk/upload:
  C = sticky completed set, P = occurrence progress. climb in integer **decimetres** (`round(gain_m×10)`): remaining =
  Σ dm of non-quiet trails ∉ C; parks: remaining = |{Play parks with ≥ 1 non-quiet trail ∉ C} ∪ parks in P|. Every
  in-window Play completion moves value from remaining to P, so R = "remaining at window start" (ux § 7). R = 0 → no
  card, no chip count, no What's On row, no claim. **Claim [P2-6]:** climb/parks claim when `P ≥ target > 0` and the
  occurrence is unclaimed (no `prior < target` gate). **Server [P1-1]:** one upload contributes to at most one
  occurrence per challenge item: the one active at `now`, else the latest still in grace; consequence (PD-26): a walk
  synced late, after the next occurrence opened, counts toward the new one, while the client counted it by its
  Logbook date — the two can differ until the next walk. Park-hop progress is mirrored into `play_progress` (+1 per
  new park key, guarded by `NOT EXISTS` on the key, the `incStretch` pattern) so bands and `/challenges` progress =
  distinct parks [P1-9]. `/challenges` returns the personal `target` when signed in (default otherwise); climb
  `progress`/`target` are dm (api.md); `bandSql` binds the dm target for climb and `you.band` uses the personal target
  [P2-7]. Client P = this walk + Logbook `complete` entries dated inside the window (validator forces whole-day
  windows for climb/parks; no new timestamps persisted).
- **D6 Numbers and inputs.** Climb compares integer dm (99.9 m = 999 < 1000). Miles: `nonQuietCoveredM / 1609.344` on
  the `Milestones` ladder (1, then every 5; EPS 1e-9); AC 2's "1,609 m → 1 mi" is read as one statute mile (1,609.344
  m → `mile:1`, 1,609.0 → none; deviation). W-ISLAND = island fraction ≥ 1 − 1e-9 (`AppGraph.Celebration.WHOLE`;
  server `islandM ≥ islandTotalM × (1 − 1e-9)`). **Quiet [P1-2, ruling]:** the new rules take quiet segments from the
  derived JSON `quietSegments` on both sides (server `trails.ts` reads it; v1/roads `walkDerivedMarks` untouched for
  AC 1). Inputs from raw coverage `{segId: coveredM}`: `nonQuietCoveredM` (included segments ∉ quietSegments),
  `touchedTrails` / `touchedParks` (a covered included segment ∉ quietSegments), completed (sticky, ∉ quietFeatures).
  Park members = counted features (Kotlin `Progress.counts`, server `METAS.trails`); Pritchard via `excludedParks`.
- **D7 Downgrade: local Play state lost on downgrade (refused, documented) [P2-2].** New `LogKind`s append (names are
  the stored keywords). A v1 build cannot decode a v1.1 file → `JsonFile` null → empty Play state on that phone and
  overwritten on its next write; server total intact; a signed-in v1.1 turn-on restores (D3). Shipyard moves forward;
  a rollback is the owner's call. Tests: v1 state (G0) decodes in v1.1; every kind round-trips; v1.1 → v1 → v1.1 never
  pays a key twice.
- **D8 Upload contract unchanged (AC 7).** No edit to `net/ApiDtos.kt`, `PlayWalkClaimsDto`, `PlayClaimDto`; ledger
  keys never leave the phone. Test [P1-6]: (a) recursive `SerialDescriptor` element names of CoverageUpload, WalkDto,
  PlayWalkClaimsDto, PlayClaimDto = G0's names; (b) one fixed upload fixture serialized byte-equal to G0's 2aee859
  bytes; (c) `git diff --exit-code 2aee859 -- app/composeApp/src/commonMain/kotlin/com/bainbridgetrails/net/ApiDtos.kt`
  + a source-span check that the two DTOs in `PlayDtos.kt` equal 2aee859's.
- **D9 Roads guard of `roads.json`:** it embeds `"pack"`, so main's sha `ad2c1435…` cannot survive a pack bump. Guard =
  sha256 of roads.json **without the `"pack":` line** = `4ea0afa8ea4c134b…f44b9` (2aee859, equal on the branch today).
  `agenda-roads.tsv` likewise compared without its header line (`tail -n +2`) [P1-4].
- **D10 Pack version.** `published/2026.11.2.json` exists, so ch-climb/ch-parkhop ship in **2026.11.3** (L4-2).
- **D11 Before the owner deploys the Worker [P2-4].** The client runs max(bundled, cached) = 2026.11.3, so a deployed
  v1 Worker answers `stale_config` for every claim naming 2026.11.3 (→ `local_only`); its `play` block still settles
  the walk rows though it paid no trails key. A pre-Play Worker returns no `play` block: rows stay `pending`. After
  deploy + 0007 the first Play upload inserts every qualifying key and totals converge.
- **D12 `play_progress_keys` — REVERSED 2026-10-21 [P1-5].** The privacy-security-reviewer's early pass on L3-1
  (`docs/play/trails/reviews/2026-10-21-privacy-l3-1.md`) escalated the table (place + sub-week window). Ruling: dropped
  from 0007 before any apply; `ch-parkhop` progress = distinct parks of one upload's new completions counted into
  `play_progress` (bounded overcount accepted). L3-2 applies it; no cron purge needed.

## 2. Lanes, owners, waves

| lane | owns | agent |
|---|---|---|
| L1 `:play` rules | `app/play/**` (+ D-listed one-line arms in composeApp, P1-10) | kmp-implementer |
| L2 composeApp | `app/composeApp/**`, `app/iosApp/**` | kmp-implementer (Android + iOS) |
| L3 Worker | `backend/src/**`, `backend/migrations/0007_play_trails.sql`, `backend/test/**`, `docs/mvp/api.md` | backend |
| L4 data | `data/play/**`, `scripts/build-play-content.mjs` (+test), generated pack copies, goldens | content/Node |

Single owner per shared file: `strings.xml` → L2-1 · `PlayConfig.kt`, `PlayConfigValidator.kt`, `Logbook.kt`,
`LogCopy.kt` → L1-1 · `ui/play/components/LogbookRow.kt`, `ui/play/screens/LogbookLogic.kt` → L1-1 (arms only), then
L2-3 [P1-10] · `net/PlayDtos.kt`, `PlayRepository.kt`, `PlayDerived.kt`, `PlayStateStore.kt`, `WalkMarks.kt`,
`graph/AppGraph.kt` → L2-2 · `MeScreen.kt`, `nav/AppShell.kt` → L2-4 · `play-content.json`, `build-play-content.mjs`
→ C0, then L4-2 only · `derived.ts`, `trails.ts`, `routes/{play,coverage,me}.ts`, `0007` → L3-1 · `claims.ts` → L3-1
then L3-2 · `challenges.ts` → L3-2 · `data/play/golden/{trails-rewards,roads-guard}.json` → G0 (read-only after) ·
`docs/mvp/api.md` → L3-2.

| wave | parallel group (≤ 3) | depends on |
|---|---|---|
| W0 | **G0** goldens (serial) | C0 committed |
| W1 | **L1-1** rules · **L3-1** server ledger · **L2-1** strings, format, glyph, badge colors | G0 |
| W2 | **L4-2** metrics + 2 challenges + 2026.11.3 · **L2-2** client data path · **L3-2** challenges | L1-1; L3-1 |
| W3 | **L2-3** Logbook, results, challenge cards · **L2-4** Me strip + sheet · **D1** docs | L2-1, L2-2, L4-2 |
| W4 | **E2E** trails run, Android + iOS (serial) | all |
| W5 | **REVIEW** → fixes → **R** PR, merge, Shipyard (serial) | E2E |

L4-2 merges first in W2; **L3-2 and L2-2 rebase on it before their last verify** [P2-11].

## 3. Stages

**G0 — goldens before any rule code** [L4, serial]
- files: `data/play/golden/trails-rewards.json`, `data/play/golden/roads-guard.json`; guard tests
  `backend/test/play-roads-guard.test.ts`, `app/play/src/jvmTest/.../rules/RoadsGuardTest.kt`,
  `app/composeApp/src/commonTest/.../play/RoadsGuardTest.kt`.
- trails-rewards.json [P1-2]: self-contained (CI has no `data/dist`): `tables` subset (featurePark, featureGainM,
  parks, quietSegments, quietFeatures, trailSegments, segmentLengthM, included segments) extracted from the committed
  derived trails.json + backend bundled meta; per case `{coverage: {segId: coveredM}, completed[], islandWhole,
  paidBefore[], expectInputs: {nonQuietCoveredM, touchedTrails, touchedParks}, expectNewKeys[], expectMarks}`;
  `challengeCases {metric, default, completed[], progress, expectTarget}`. Server test also checks the subset equals
  bundled `METAS`/derived JSON (drift guard).
- cases (AC 2): climb 99.9 → none; 100.0 → `climb:100`; partial coverage of Peter's (92.1) → none; 1,368.8 → 6 steps
  = 300; steep 40.0 → +50, 39.9 → 0, Blakley Cemetery (66.5, quiet) → 0, re-completion → 0; 1,609.344 m → `mile:1`,
  1,609.0 → none, 102,964 m → 13 steps = 650; first segment in Gazzam → `park1:Gazzam Lake Preserve`, again → none;
  Pritchard Trail touched → none; Hayano completed → `park1` + `parkc` Moritani (+50, +150); **Blakely Harbor:
  coverage only on quiet `seg-001793` → no `park1`; a non-quiet Blakely Harbor segment → `park1:Blakely Harbor
  Park`**; island +1,000 once; table minus a trail → nothing lowered; plus a trail → only its keys.
- roads capture: `git worktree add /tmp/bt-main 2aee859`, copy the guard tests in, run with `PLAY_GOLDEN_WRITE=1`
  (server: roads `walkDerivedMarks` on 3 fixture states, roads `playStatements` totals, `/v1/play/me?dataset=roads`
  body, `challengesOf(CURRENT,"roads")`; Kotlin: roads `StartBalance.onEnable` and `WalkMarks.apply` entries for a
  fixture, bundled-pack roads challenge list, v1 DTO element names + one fixed upload's bytes (D8), a v1 `PlayState`
  JSON with every v1 kind); copy the JSON back; the branch runs assert mode.
- test-first: `play-roads-guard.test.ts` (`roads walkDerived, totals, me body, challenge list equal main`,
  `roads.json minus the pack line has sha256 4ea0afa8…`, `agenda-roads.tsv minus header equals 2aee859's`); Kotlin
  `RoadsGuardTest › roads start balance, walk marks, challenge list equal main`
- verify: `node --test scripts/*.test.mjs` + verify.api + verify.kmp (green on unchanged code)
- rollback: delete the files

### W1

**L1-1 — trails rules, start, Logbook kinds, challenge metrics, claim decision** [L1]
- files: `play/rules/TrailRewards.kt` (new: `TrailTables`, `inputs(coverage, completed, islandWhole, tables)` (D6),
  `CLIMB_STEPS_M = [100,250,500,750,1000,1250]`, `qualifying`, `paid(trailPaid, logbook)` (D1), `marksOf(key)` (+50;
  parkc +150; island +1,000), `kindOf`, `entries(newKeys, names, date, walkId, newId)`, `challengeTarget`),
  `play/rules/TrailStart.kt` (D4), `rules/Logbook.kt` (append `climb, high_ground, start_trails`; not `walkDerived`,
  not `found`), `rules/LogCopy.kt` (ux § 2.2/§ 6 lines + D4 catch-up copy), `config/PlayConfig.kt` (`ChallengeMetric`
  += `climb, parks`), `config/PlayConfigValidator.kt` (climb/parks: solo, goals exactly `[trails]`, target keyed
  `trails`, whole-day window), `claim/ClaimEvaluator.kt` (D5 claim); one-line arms for the three new kinds in the
  exhaustive `when`s of `ui/play/components/LogbookRow.kt` (`labelRes`, glyph) and `ui/play/screens/LogbookLogic.kt:109`,
  mapped to existing keys/glyphs, so verify.kmp stays green [P1-10]
- test-first (`:play`): `TrailRewardsTest` (every G0 case by name: `climb 99_9 m pays nothing`, `steep 40_0 pays 50
  and 39_9 pays 0`, `quiet trail gain 66_5 never pays`, `one-trail park pays 50 and 150 same walk`, `Pritchard Park
  never pays`, `quiet Blakely segment starts no park`, `dropping a trail lowers nothing`, `adding a trail pays only its
  keys`, `keys are unit free`); jvmTest `TrailRewardsGoldenTest › inputs and keys of every trails-rewards case`;
  `TrailStartTest` (`opt-in with prior progress writes start_balance then start_trails then island` (AC 3), `off then
  on with nothing new writes nothing`, `off then on with new keys writes one "Trail rewards caught up"`, `keys in
  Logbook contentIds are paid even with empty trailPaid`, `roads never writes start_trails`); `LogCopyTest` (new lines
  verbatim); `LogbookTest › trails kinds appended, not walkDerived, not found`; jvmTest `LogKindCompatTest` (`v1 JSON
  decodes`, `every kind round-trips`, `v1_1 to v1 to v1_1 never pays a key twice`); `PlayConfigParseTest › climb and
  parks parse`; `PlayConfigValidatorTest` (`climb on roads rejected`, `parks window not whole days rejected`, `roads
  target on climb rejected`); `TrailChallengeTest` (`149_9 m no claim, 150_0 m claims 100`, `2 parks no claim, 3
  claims`, `completion outside window ignored`, `quiet trail and Pritchard Park add nothing`, `same park twice counts
  once`, `P above target and unclaimed claims`, `claims once per occurrence`, `120 m remaining → target 120`,
  `nothing remaining → no claim`)
- verify: `cd app && ./gradlew :play:jvmTest`, then verify.kmp
- rollback: revert (no production caller until L2-2)

**L3-1 — server ledger, 0007, `/me`** [L3]
- files: `migrations/0007_play_trails.sql` (D2), `src/play/derived.ts` (load `featurePark`, `featureGainM`→dm, `parks`,
  JSON `quietSegments` as `trailRuleQuietSegments`; trails only), `src/play/trails.ts` (TS mirror of `inputs` /
  `qualifying` / `marksOf` / `challengeTarget`), `src/play/claims.ts` (ledger inserts, D2 totals SQL, `loadPlayState`
  reads the ledger), `src/routes/coverage.ts` (pass merged coverage, completed, islandM; trails only),
  `src/routes/play.ts` (D3), `src/routes/me.ts` (DELETE removes `play_trail_paid`)
- test-first: `test/play-trails.test.ts` (`golden inputs and keys equal Kotlin` (parity), `first Play upload with
  prior completions credits each rule once` (AC 3), `second upload pays 0`, `upload without play writes no ledger
  row`, `off-period progress paid once on next Play upload`, `dataset drop lowers nothing`, `totals UPDATE path: total
  = cov + derived + found + week + trail_marks` (existing totals row first) [P2-1], `no-change upload inserts ledger
  keys only`, `me trails perKind and paid`, `delete removes ledger and progress keys`); `migrations.test.ts › 0007
  applies after 0006, additive only`; `play-roads-guard.test.ts` green
- verify: verify.api · rollback: Worker revert; 0007 is additive and inert for old Workers

**L2-1 — strings, `TrailRewardFormat`, `FxGlyph.Summit`, badge colors** [L2]
- files: `composeResources/values/strings.xml` (every ux § 8 key; no roads twin), `ui/play/TrailRewardFormat.kt` (ux
  § 1), `play/fx/FxArt.kt` (`FxGlyph.Summit`), `ui/components/RoadComponents.kt` (optional medallion/badge colors)
- test-first (host): `TrailRewardFormatTest` (UX-3: `99_9 m shows 327 ft`, `100 m step shows 328 ft`, `250 m = 820
  ft`, `149_9 of 150 m shows 149 of 150 m`, `150 of 150 m`, `10 mi step = 16.1 km`, `1,207 ft`, `ladder equals
  Milestones.distanceStep`); `GoalVocabularyGuardTest` (trail keys in `TRAIL_ONLY`); `PlayCopyTest` (blocklist);
  `MilestoneColorsTest › default colors equal the roads constants`
- verify: verify.kmp + `cd app && ./gradlew :androidApp:assembleRoadsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
- rollback: revert (unreferenced until W3)

### W2

**L4-2 — metrics in the Node validator, `ch-climb` + `ch-parkhop`, pack 2026.11.3** [L4]
- precondition: L1-1 merged (else `PlayConfigBundledTest` fails on `climb`/`parks`).
- files: `scripts/build-play-content.mjs` (+ `.test.mjs`), `data/play/play-content.json` (two items, ux § 7 copy,
  `goals:["trails"]`; `ch-climb` `every: week, on: [mon], 00:00–24:00, days 7`, target `{trails:150}`; `ch-parkhop`
  as `ch-sweep`'s window, target `{trails:3}`; marks 100), `data/play/published/2026.11.3.json`, regenerated copies,
  `app/play/src/jvmTest/resources/golden/agenda-trails.tsv`, `docs/play/e2e-ids.env` (`./gradlew :play:jvmTest
  -Pplay.updateGolden=true`)
- the build fails when a park name or trail id present in the committed `derived/trails.json` disappears [P2-3]
  (an alias map for renames is v2; noted in PD-26).
- test-first: `build-play-content.test.mjs` (`accepts climb and parks for trails`, `rejects climb on roads`, `rejects
  parks window not whole days`, `rejects unknown metric`, `fails when a published park or trail id disappears`,
  `committed pack is 2026.11.3`)
- verify: `node --test scripts/*.test.mjs && node scripts/build-play-content.mjs --check && diff <(git show
  2aee859:app/play/src/jvmTest/resources/golden/agenda-roads.tsv | tail -n +2) <(tail -n +2
  app/play/src/jvmTest/resources/golden/agenda-roads.tsv)` [P1-4] + `cd app && ./gradlew :play:jvmTest` + verify.api
- PR artifact: 4-week trails agenda (2026-10-26 → 2026-11-23 rows of agenda-trails.tsv) in the PR (AC 5).
- rollback: revert to 2026.11.2 (unpublished on main)

**L2-2 — client data path** [L2]
- files: `play/PlayDerived.kt` (parse `featurePark`, `featureGainM`, `parks` → `TrailTables`, null on roads),
  `play/PlayStateStore.kt` (`trailPaid`, `trailsStarted`), `play/WalkMarks.kt` (D4 race path; `SETTLED_WITH_WALK` +=
  climb, high_ground, milestone, park_first, park_complete, island), `play/PlayRepository.kt` (`trailInput` provider
  via `SnapshotMarks`; `creditOnce` → `TrailStart`; D3 restore; `awaitStartBalance` sum; climb/parks claim inputs;
  challenge targets), `net/PlayDtos.kt` (`PlayMeDto.paid: List<String> = emptyList()` only), `graph/AppGraph.kt`
- test-first (host): `WalkMarksTrailsTest` (`Hayano walk writes complete, park_first, park_complete pending`, `rows
  settle with the walk`, `walk finalized during turn-on restore pays every key once` [P1-8]); `PlayRestoreTest`
  (`signed-out opt-in with trail progress, then sign-in restores server finds once`, `foreground restore without a
  Restored entry does not seed trailPaid` [P0-1]); `StartBalanceSumTest › awaitStartBalance = start_balance +
  start_trails + island` [P2-5]; `PlayStatePrivacyTest › trail kinds and trailPaid hold no time of day`;
  `UploadContractTest` (AC 7, D8 a–c) [P1-6]; `SyncRepositoryTest` Play-off byte-identical (existing);
  `PlayStateCompatTest › v1 state decodes with defaults`; `RoadsGuardTest`, `PlayOffNothingCreatedTest` green
- verify: verify.kmp + `cd app && ./gradlew :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug
  :composeApp:linkDebugFrameworkIosSimulatorArm64` + `git diff --exit-code 2aee859 -- <ApiDtos.kt path>`
- reachability: start balance from the Settings toggle / app start (existing); walk rows from the Finalizer hook
- rollback: revert; new state fields default and the v1 code path ignores them

**L3-2 — `ch-climb` / `ch-parkhop` on the server** [L3]
- files: `src/play/challenges.ts` (`challengeDef` solo climb/parks; `completionProgress` → one occurrence per item
  (D5, P1-1), dm / park keys + mirrored `play_progress` (P1-9); D5 target; `challengesBody` personal target, dm band
  target, personal `you.band` (P2-7); per-upload distinct parks, D12 reversed), `src/play/claims.ts` (challenge
  path), `docs/mvp/api.md` (`/me` trails fields, challenges climb dm, 0007)
- test-first: `test/play-trails-challenges.test.ts` (`ch-climb 149.9 m no_bits, 150.0 m accepted +100`, `ch-parkhop 2
  parks no_bits, 3 accepted`, `upload after Mon 00:00 contributes to the new occurrence only`, `late upload in grace
  with no active occurrence contributes to the latest`, `one completion never counts toward two ch-climb occurrences`,
  `quiet trail and Pritchard Park add nothing`, `same park in two uploads counts once`, `parkhop /challenges progress
  equals distinct parks`, `instance pays once`, `day cap and week cap still bind`, `120 m remaining → target 1200 dm`,
  `climb bands use the dm target`, `you.band uses the personal target`, `remaining 0 → not listed for the user`,
  `anonymous gets default target`, `current pack: both trails-only, absent for roads`, `progress keys purged after
  grace`)
- verify: verify.api (after rebasing on L4-2) · rollback: Worker revert

### W3

**L2-3 — Logbook rows, results collapse, challenge cards** [L2]
- files: `ui/play/components/LogbookRow.kt` (real labels, `Summit` glyph, re-render climb/milestone from `contentId`
  via `TrailRewardFormat`, spoken a11y), `ui/play/screens/LogbookLogic.kt`, `ui/play/record/ResultsPlayLogic.kt` (ux
  § 3 order; ≤ 8 rows, 7 + `play_results_more`), `ui/play/record/ResultsPlaySectionView.kt`,
  `ui/play/screens/ChallengesLogic.kt` (progress text, `{target}` via format, D5 hide), `ui/play/map/ChallengesChip.kt`
  + `ui/play/screens/WhatsOnLogic.kt` (hidden card excluded)
- test-first (host): `ResultsPlaySectionTest` (`9 entries → 7 rows + "+2 more in your Logbook", total = sum of 9`, `8
  entries → no more-line`, `high_ground before park_first, climb before milestone`); `LogbookTrailsRowTest`
  (`climb:250 → "820 ft climbed" in miles, "250 m climbed" in km`, `unparseable contentId → persisted what`, `a11y
  "820 feet climbed"`); `ChallengesTrailsTest` (`"95 of 150 m climbed"`, `"311 of 492 ft climbed"`, `"2 of 3 parks"`,
  `remaining 0 → absent from screen, chip count, What's On`, `roads model has neither`); `LogbookGroupingTest` green
- verify: verify.kmp + assembleTrailsDebug + assembleRoadsDebug + linkDebugFrameworkIosSimulatorArm64
- rollback: revert (L1-1's placeholder arms remain valid)

**L2-4 — Me › Trail milestones strip + sheet** [L2]
- files: `ui/play/me/{TrailMilestonesLogic,TrailMilestoneStrip,TrailMilestonesSheet}.kt` (ux § 5; model from
  `TrailRewardInput` + `TrailTables` off composition, null on roads), `ui/MeScreen.kt` (`key = "trail-milestones"`
  under the PlayCard), `nav/AppShell.kt` (park row → Trails tab with `ParkFilter.Named`; trail row → TrailDetail)
- test-first (host): `TrailMilestonesLogicTest` (`4 badges fixed order with fixture values`, `new player "0 / 328"`,
  `sheet: 6 climb steps, miles to reachable top 60, 19 parks alphabetical without Pritchard, 10 high-ground steepest
  first`, `counts come from the tables`, `roads model null`)
- verify: verify.kmp + assembleTrailsDebug + assembleRoadsDebug + linkDebugFrameworkIosSimulatorArm64
- reachability: Me (production) → strip → sheet → Trails list / TrailDetail
- rollback: revert

**D1 — docs** [docs only]
- files: `docs/play/decisions.md` (PD-26: brief § 2 table, Q1–Q5 defaults, D1–D12, the D6 AC 2 reading, D5 late-sync
  and claim rule, D4 copy deviation, D12 deviation, P2-3 alias map = v2, the v1-client pack risk),
  `docs/play/product.md` (§ 3.2 six rows + Q4 overlap note, § 3.7.3 trails ≈ 29,196 vs roads ≈ 23,286, § 4.5 two
  challenge rows), `docs/play/schedule.md` (challenge metrics `climb` (dm) and `parks`; § 7 server-subset rows)
  [P2-9], `docs/play/README.md` (index row → `trails/`), `app/AGENTS.md` (Play: e2e trails mode),
  `docs/mvp/STATUS.md` (follow-up issue: v1 W-COV/W-SQUARE quiet-set drift — server excludes quiet-feature segments,
  Kotlin the derived `quietSegments`; not fixed here) [P1-2]
- verify: `grep -n 'PD-26' docs/play/decisions.md && grep -n 'parks' docs/play/schedule.md && grep -n 'trails/'
  docs/play/README.md && grep -n 'MODE=trails' app/AGENTS.md && grep -n 'quiet' docs/mvp/STATUS.md` · rollback: revert

### W4 — E2E (AC 8) [L2 + L3, serial; emulator API 37 + iOS simulator]

**Fixed inputs.** Island time **Sat 2026-10-31 10:00 PT** (17:00Z, daylight; week `2026-10-26`; `ch-climb@2026-10-26`
Mon→Mon and `ch-parkhop@2026-10-31` Sat→Mon both open). Fresh dev account; units miles. Local wrangler with
`DEV_CLOCK_OFFSET_MS = Date.parse("2026-10-31T17:00:00Z") − Date.now()`; `npm run dev` applies 0001–0007 locally.
`scripts/play-e2e.sh` gains `MODE=trails` (PKG `com.bainbridgetrails.app.dev`, `DIST=data/dist`, clock above,
`emu-route.sh <id> 2`, sync between walks). Simulated routes from dataset geometry only. **Expected rows are rebuilt
from the agenda regenerated by L4-2 [P1-3]**: the script asserts the trails rows below exactly, plus find rows per
that agenda (today: `egg-strawberry` on Hayano, `treasure-weekly-trails@2026-10-26#01` on Grand Forest East).

| walk | trail id (park, length, gain) | trails rows asserted exactly (+ W-COV/W-SQUARE, + find rows per agenda) |
|---|---|---|
| 1 | `trail-hayano-trail` (Moritani Preserve, one-trail park, 402 m, 8.4) | Hayano Trail +100 · First steps in a park: Moritani Preserve +50 · Every trail in a park: Moritani Preserve +150 |
| 2 | `trail-peters-trail` (Gazzam Lake Preserve, 1,110 m, 92.1) | Peter's Trail +100 · High ground: Peter's Trail +50 · First steps: Gazzam Lake Preserve +50 · Climb "328 ft climbed" +50 (Σ 100.5 m) · Walking day +15 |
| 3 | `trail-grand-forest-east-trail` (Grand Forest, 1,778 m, 58.7) | Grand Forest East Trail +100 · High ground +50 · First steps: Grand Forest +50 · Milestone "1 mi of trail walked" +50 · Uphill week +100 (159.2 m ≥ 150) · Park hop +100 (3 parks) |

**Assertions.** (1) Logbook total = `npx wrangler d1 execute bainbridge-trails --local --command "SELECT total,
trail_marks, found_marks FROM play_totals WHERE dataset='trails'"` total; `trail_marks = 500`. (2) `GET
/v1/play/me?dataset=trails` perKind `climb 1, high_ground 2, milestone 1, park_first 3, park_complete 1, island 0`,
`challenge 2`; `paid` = 8 keys (`climb:100`, `high:` ×2, `mile:1`, `park1:` ×3, `parkc:Moritani Preserve`).
(3) Challenges both Done, clamped ("150 of 150 m climbed" / "492 of 492 ft"). (4) Me strip: Climb "522 / 820", Parks
"1 / 19", High ground "2 / 10", Trail miles = floor-one-decimal of the server's `coveredM` (`GET /v1/coverage`) / 5
[P2-10]; sheet: Moritani Finished, Gazzam Lake Preserve + Grand Forest Started. (5) Roads APK, Play on: Me has no
strip, no new card (UX-1). (6) TalkBack + VoiceOver spot checks (UX-8); dark theme and font 2.0 on strip/sheet (UX-9).
AC 7 is the unit test (D8), not a log assertion.
**iOS half:** stop wrangler, `rm -rf backend/.wrangler/state`, restart (fresh D1); `xcrun simctl launch <udid>
com.bainbridgetrails.app -btDevSignIn 1 -apiBaseUrl http://localhost:8787 -btPlayClock 2026-10-31T10:00
-acceptSimulatedLocations 1 -btScreen settings`; `SIM=<udid> scripts/sim-route.sh` the same three ids (wait each
route's duration); screenshots of results, `-btScreen logbook`, `-btScreen me`.
- files: `scripts/play-e2e.sh`, `docs/play/trails/screens/{t-01-settings-play,t-02..04-results-walk1..3,t-05-logbook,
  t-06-challenges,t-07-me-strip,t-08-me-sheet-light,t-08-me-sheet-dark,t-09-me-font2,t-10-roads-me}-android.png`,
  `{t-02..04-results,t-05-logbook,t-07-me-strip}-ios.png`, `docs/play/trails/screens/README.md` (HEAD, commands,
  wrangler query output, findings)
- verify: assertions above + verify.full + verify.api still green · rollback: n/a (evidence)

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
| `start_trails` | Settings › Play toggle / app start → `PlayRepository.start` → `creditOnce` (or `WalkMarks`, D4) | L2-2 |
| ledger, `trail_marks`, `/me` fields | `PUT /v1/coverage` (Play element) · restore on turn-on / sign-in | L3-1, L2-2 |
| ch-climb / ch-parkhop | pack 2026.11.3 → Challenges screen, chip, What's On; claim on Finish → upload | L4-2, L2-3, L3-2 |
| Logbook / results rows | Logbook screen, results sheet | L1-1 (arms), L2-3 |
| Trail milestones strip + sheet | Me tab → strip → sheet → Trails list (park) / TrailDetail | L2-4 |

## 5. CROSS-CUTTING
- swip: n/a (`factory.swip: false`, no new event) · debugdrawer: n/a (Play clock plugin + agenda dump suffice) ·
  atlas: n/a (`product_atlas: none`).
- goldens: `trails-rewards.json` (parity), `roads-guard.json` (AC 1, incl. v1 DTO names/bytes), `agenda-trails.tsv` +
  `e2e-ids.env` regenerated (L4-2), `agenda-roads.tsv` equal minus header; screenshots `docs/play/trails/screens/`.
- migrations: `0007_play_trails.sql` additive (2 tables, 1 column); remote apply is the owner's, with the Worker deploy.

## 6. REVIEW (ordered; reviews filed in `docs/play/trails/reviews/`)
1. adversarial-reviewer on **this plan** (spec kind) — round 1 FIX-FIRST applied (Rev 2); re-check before W1.
2. privacy-security-reviewer after L3-1 + L3-2 (ledger, D12 fallback, `/me` fields) and after L2-2
   (AC 7, state file) — location-sharing posture explicitly; final D12 ruling in W5.
3. compose-ui-reviewer on L2-1, L2-3, L2-4 (tokens, merged a11y nodes, spoken units, derived off composition, no motion).
4. kmp-verifier at each wave end and after E2E: verify.full + verify.api + xcodebuild, read from machine output.
5. simplification-reviewer on the branch. 6. adversarial-reviewer pre-PR (AC 1–10 vs evidence). 7. privacy-security
   final SHIP. 8. release-shepherd (R).

## 7. RISKS
1. **v1 clients and the new pack.** `coerceInputValues=false`: once the owner deploys a Worker serving 2026.11.3, v1
   builds reject the whole pack (keep last good). Shipyard-only today; PD-26.
2. **D12 retention ruling** may flip in W5 → fallback per-upload distinct parks (product change, orchestrator).
3. **AC 2 "1,609 m"** read as 1 statute mile (D6); a literal reading needs a tolerance both sides and a new golden.
4. **Parity**: summation order (mile boundary) and "counted" features (D6); the golden pins boundaries and inputs.
5. **Late sync (D5)** can split client/server counting for one window; **pre-deploy (D11)** Logbook > server total.
6. **C0 drift**: placements move `E2E_TREASURE_*`, agenda goldens and the e2e find rows; L4-2 regenerates, roads fixed.
7. **Emulator completion** of Grand Forest East (12 segments, greedy chaining) may miss the threshold; fallback walk 3
   = `trail-main-loop-trail` (59.5 m, 2,437 m; Σ climb 160.0, same trails rows; find rows per agenda).
