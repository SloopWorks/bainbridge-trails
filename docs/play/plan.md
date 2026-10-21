# Play v1 — staged implementation plan

Date: 2026-10-20 · Role: plan-author · Owner of: the build order, the AC 8 e2e script, the effect inventory per stage
(PD-17). Inputs: `README.md` (precedence, AC matrix, glossary), `decisions.md` (PD-1 … PD-19), `product.md` (v1 scope),
`schedule.md` (§4 pack, §5 scheduler, §7 server, §8 stage contract), `engine.md` (§2 API, §4 stages, §5 spikes),
`spikes/fx.md` (S1–S3/R8 PASS, **S4 FAIL**), `effects.md` (§7 v1 list), `ux.md` (§2 components, §3 screens, §8 build
notes), `ai.md` (v1 = offline authoring only). Where this plan and a spec disagree, the spec wins and this plan is wrong.
Vocabulary: marks, Logbook, Play chip, challenge, quiet zone, kraken attack / stretch / repel.

This plan **merges** `schedule.md` §8 (stages 1–11) and `engine.md` §4 (stages 1–7, 9) into one ordering, and adds the
UI, sync, content and e2e work neither owns. Stage ids below are this plan's; the source stage is named in each.

```
PLAN: Play v1 — opt-in gamification, both goals  (acceptance criteria satisfied: 1–9)
PRECONDITIONS:
  - Branch gamification/v1 (exists; specs committed). Each implementer works on a child branch cut from it, or on it
    directly when the stage is serial. Any NEW branch is cut after `git pull` of latest origin/main (owner rule) and
    gamification/v1 is rebased/merged onto main before the PR.
  - Specs ACCEPTED: product, schedule, engine, effects, ai (decisions.md Status). ux.md carries a design-director
    ACCEPT verdict (§8) but its header and README row still say pending/in progress: stage P0 flips both before any UI
    stage starts (gate design_before_build). ux.md §0.3 #9 "≤ 30 mph" is superseded by PD-7 (≤ 25 mph); UI follows PD-7.
  - No new ADR needed: every decision this plan relies on is PD-1 … PD-19.
  - Owner actions (cannot be done by an agent): B0 (apply 0004 remotely, destructive), OD-16 TBC facts (items stay
    `enabled:false` until checked), the privacy-policy paragraph wording sign-off (OD-19), ferry timetable values.
```

---

## 0. Lanes, agents, parallel groups

| lane | owns | agent |
|---|---|---|
| **L1** `:play` pure module | `app/play/**`, `app/coverage/.../Fixes.kt` (keptFixes), `app/tracking/.../Finalizer.kt` (hook) | kmp-implementer |
| **L2** composeApp | `app/composeApp/**`, `app/androidApp/src/debug/**`, `app/iosApp/**` launch args | kmp-implementer |
| **L3** backend | `backend/**`, `docs/mvp/api.md` (Play section), `docs/legal/privacy-policy.md` | cloudflare-implementer |
| **L4** data/content | `data/play/**`, `scripts/build-play-content.mjs`, `scripts/build-tides.mjs`, `scripts/sync-app-assets.sh`, generated copies in `backend/src/data/play/**` and `app/composeApp/src/commonMain/composeResources/files/play/**` | cloudflare-implementer (Node) |

**Concurrency rule:** at most 3 implementers at once; a parallel group lists stages whose file sets are disjoint
(checked below). Shared files have **one owner stage**: `strings.xml` → L2-1 (all v1 `play_*` keys at once);
`nav/Routes.kt` + `nav/AppShell.kt` → L2-7 then L2-10; `net/ApiDtos.kt` → L2-9 (Play DTOs live in a new
`net/PlayDtos.kt` owned by L2-3); `graph/AppGraph.kt` → L2-3 then L2-9; `ui/MapTab.kt` → L2-2 then L2-8;
`backend/src/routes/coverage.ts` → L3-2 only; `backend/src/index.ts` → L3-1, then L3-2, then L3-3 (serial).

| wave | parallel group (≤ 3) | depends on |
|---|---|---|
| W0 | **P0** docs gate · **S1** `:play` module (L1) · **B0** migration 0004 resolution (L3, owner) | — |
| W1 | **L1-2** FxScene/clock/projection · **L1-3** time (sun, island time, tides, ferry) · **L4-1** v1 pack + build script | S1 |
| W2 | **L1-4** config model + validator · **L2-1** theme, components, all strings · **L3-1** content/tides endpoints | L1-3, L4-1 (L1-4, L3-1); S1 (L2-1) |
| W3 | **L1-5** recurrence + PlayScheduler · **L2-2** FxHost core + map slots · **L3-2** claims on PUT /v1/coverage | L1-4; L1-2+L2-1; B0+L3-1 |
| W4 | **L1-6** marks, Logbook, claims, sensing, quiet/roadSafety + Finalizer hook · **L2-3** PlayRepository, clock, debug · **L3-3** challenges + cron | L1-5; L1-5+L2-2; L3-2 |
| W5 | **L2-4** Layer-tier effects + showcase (deletes spike) · **L2-7** Settings/opt-in, Logbook, Ranks, What's On, Challenges, Stories, Me | L2-3; L2-3+L1-6 |
| W6 | **L2-5** Overlay/Moment effects · **L2-8** Map + Record + Results surfaces · **L2-9** sync claims + restore + start balance | L2-4; L2-4+L1-6; L1-6+L3-2 |
| W7 | **L2-10** integration wiring, offer, quiet day, pure-mode guards (serial) | all above |
| W8 | **E2E** AC 8 script + screenshots (serial) · **PERF** engine stage 9 (serial, device) | L2-10, L3-3 |

Every stage leaves `main`-bound CI green: no stage adds a production call site before the code it calls exists, and
the Play gate (`AppSettings.play.enabled`, default false) keeps every new path dark until L2-7 adds the toggle.
Reachability is closed in L2-7/L2-8/L2-10 (§4).

---

## 1. Stages

Verify commands come from `.agents/profile.yml` (`verify.kmp`, `verify.api`, `verify.full`); narrower Gradle/npm
commands are given where they are the real gate for the stage. `verify.kmp` = `cd app && ./gradlew :play:jvmTest
:composeApp:testAndroidHostTest` (red until S1 lands `:play`; S1 makes it green).

### W0

**P0 — docs gate (no code)**
- intent: flip `ux.md` header to `ACCEPTED 2026-10-20 (design-director verdict §8)`, README rows `ux.md` → ACCEPTED,
  `plan.md` → written; note PD-7 supersedes ux §0.3 #9 "≤ 30 mph"; mark ux §7.2 open 2 and 3 resolved (decisions,
  PD-19).
- files: `docs/play/ux.md`, `docs/play/README.md`
- test-first: n/a · verify: `grep -c 'ACCEPTED' docs/play/ux.md` ≥ 1 and `grep -n 'plan.md' docs/play/README.md`
- rollback: revert · parallel: with S1, B0

**S1 — `:play` module + RNG + timeline/easing** (schedule st.1 + engine st.1) [L1]
- files: `app/settings.gradle.kts` (+`include(":play")`), `app/play/build.gradle.kts` (clone of
  `app/tracking/build.gradle.kts`: jvm, android `namespace com.bainbridgetrails.play`, iosArm64, iosSimulatorArm64;
  deps serialization-json, kotlinx-datetime, coroutines-core; **no** `:coverage`, no Compose plugin),
  `app/play/src/commonMain/kotlin/com/bainbridgetrails/play/rng/{SplitMix64,Fnv1a}.kt`,
  `.../play/fx/{FxEasing,FxTimeline,FxModel}.kt` (GeoPoint, FxAnchor, FxBand, FxPriority, FxStage, FxSpec),
  `app/composeApp/build.gradle.kts` (`implementation(project(":play"))`), `.github/workflows/ci.yml` (android job:
  add `:play:jvmTest`), `app/AGENTS.md` (module list + full verify set gains `:play:jvmTest`)
- test-first: `play/src/commonTest/.../rng/SplitMix64Test` (`first 8 outputs seed 0`, `… seed 1`, `… seed 0x9E3779B97F4A7C15`,
  `nextInt stays in bound`, `pick independent of input order`); `Fnv1aTest` (`empty string`, `a`, `foobar`, `utf8 id`);
  `fx/FxEasingTest` (`emphasizedDecelerate matches Compose at 101 samples within 1e-3`); `fx/FxTimelineTest`
  (`stageAt boundaries`, `foldedMs loops RUN`, `dismiss jumps to SETTLE without cutting`, `phase equals CelebrationScreen phase`)
- verify: verify.kmp
- rollback: remove the include, the dir, the dependency line and the ci.yml token
- parallel: with P0, B0 (disjoint)

**B0 — resolve `0004_cleanup.sql.pending`** (schedule §7.2, PD-11) [L3 + owner]
- intent: Play's migration must be `0005_play.sql` and apply after 0004.
- **Owner, irreversible:** confirm the 0003 Worker is the only one serving, then per `backend/README.md` §"Rolling out
  migration 0003" step 3: `git mv backend/migrations/0004_cleanup.sql.pending backend/migrations/0004_cleanup.sql &&
  cd backend && npm run migrate:remote`. This drops tables in production D1; an agent never runs it.
- agent part (after the owner confirms): `backend/vitest.config.ts` (load 0004 from the normal migrations list; drop
  the `.pending` lookup and `CLEANUP_QUERIES` binding or point it at 0004), `backend/test/migrations.test.ts`
  (expected names gain `0004_cleanup.sql`; the cleanup case runs from the list), `backend/README.md` (step marked done)
- test-first: `migrations.test.ts › migration 0003 (dataset scope) and 0004 cleanup` updated to the new list (fails
  until the config change lands)
- verify: verify.api (`cd backend && npm test && npx tsc --noEmit`)
- rollback: the remote step has none (destructive by design); the repo part reverts with git
- **If the owner cannot do it before W3:** L3-2 waits; L3-1 and L3-3's pure parts do not need D1 tables.
- parallel: with P0, S1

### W1 (parallel group: L1-2 · L1-3 · L4-1)

**L1-2 — FxScene, caps, clock policy, projection, rigs, stats window** (engine st.2–3, S4/S1 corrections) [L1]
- files: `play/fx/{FxScene,FxCaps,FxClockPolicy,FxProjection,TentacleRig,FxStatsWindow,FxGesturePolicy}.kt`
- spec deltas from the spikes (engine §0 "Spike corrections"): clock publishes on elapsed frame time ≥ 33 ms, never
  `delay(33)`; `FxClockPolicy` exposes `mapMaxFps(mode)` (30 while any Layer-tier kind animates, null otherwise);
  `FxGesturePolicy.overlayVisible(cameraMoving, reasonGesture)` = false during gestures (PD-19).
- test-first: `FxSceneTest` (`second HERO queues`, `hero cooldown 8s`, `queued hero expires after 10s`,
  `overlay part retires at overlayMaxMs`, `out-of-view HERO jumps to final frame`, `overlay cap demotes oldest ambient`,
  `same inputs same frames`, `reduced marks every live item reduced and isAnimating=false`); `FxClockPolicyTest`
  (`stopped when paused/reduced/play off`, `ambient-only idle 20s stops`, `recording disables ambient but not hero`,
  `ambient publishes at most every 33ms at 120Hz frames`, `mapMaxFps 30 only while layer animation runs`);
  `FxGesturePolicyTest` (`overlay hidden while gesture moves camera`, `programmatic fitTo keeps overlay`,
  `fades back on move end`); `FxProjectionTest` (`offsets match hand-computed Web Mercator at z12/z15/z18`,
  `metresToDp at 47.6°`); `TentacleRigTest` (`deterministic by seed`, `reduced pose is time independent`,
  `pose writes only into preallocated arrays`); `FxStatsWindowTest` (`p50 p95 p99 over ring`, `no allocation on add`)
- verify: `cd app && ./gradlew :play:jvmTest`
- rollback: revert (no callers)
- parallel: L1-3 (different package `play/time`), L4-1 (no Kotlin)

**L1-3 — island time, sun, tide table, ferry timetable** (schedule st.2–3) [L1]
- files: `play/time/{IslandTime,Sun,TideTable,FerryTimetable}.kt`; `scripts/build-tides.mjs` (network only when run
  by hand; `--check` offline), `data/play/tides/9445882-{2026,2027,2028,2029}.json`, `data/play/ATTRIBUTION.md`
  (NOAA "not for navigation"), fixture copy `app/play/src/jvmTest/resources/tides/9445882-2027.json`
- test-first: `IslandTimeTest` (`2027-03-14 02:30 resolves to 03:00`, `2027-11-07 01:30 first instance`,
  `week id is the Monday`, `season of a week is its Thursday's quarter`); `SunTest` (`12 NOAA reference dates within 2 min`,
  `civil dawn and dusk present`, `DST days`); `TideTableTest` (`events per day 3 to 4`, `cosine interpolation monotonic
  between extrema`, `out of range returns null`); `FerryTimetableTest` (`weekday weekend holiday column`,
  `season boundaries`, `arrival is departure plus crossingMin`, `expired timetable returns null`)
- verify: `cd app && ./gradlew :play:jvmTest` and `node scripts/build-tides.mjs --check`
- rollback: revert; kraken stretches then stay inactive (fail closed) by design
- parallel: L1-2, L4-1

**L4-1 — v1 content pack + build/validate script** (schedule §4.1, §4.7; PD-6, PD-7, PD-11) [L4]
- intent: `data/play/play-content.json` = schedule §4.7 trimmed to v1: treasures (both goals), `egg-ladybug`,
  `egg-fairydoor` (`enabled:false` until owner verifies the spot), `egg-helix`, `egg-marquee`, `egg-madrone`,
  `egg-stationS` (each `enabled:false` while its OD-16 TBC stands), `riddle-frog`, `riddle-bond`, `riddle-fairy`,
  `riddle-madrone`, `riddle-monroe` (roads; trails stamp-only), `kraken-roads` (K5,K2,K6,K4,K7,K3), `kraken-trails`
  (T4,T2,T3,T5), `ch-sweep`, `ch-krakenweek`, `lore-frog`, `lore-mill`, `lore-memorial`, `lore-yama` (no marker),
  `lore-helix`, `lore-fortward`, `lore-eagleharbor`, `lore-lynwood`, `lore-strawberry`, `lore-fay` (armchair),
  `lore-bloedel` (armchair), `lore-kraken`, `ferry-sprite`; the Mar 30 window; v2 items absent (not drafts — keeps
  the validator surface small; schedule §4.7's drafts were for schema exercise only). Real coordinates from §4.7 and
  product §4. `version: "2026.11.0"`, epoch 2026-10-29.
- files: `data/play/play-content.json`, `data/play/published/2026.11.0.json` (first publish),
  `scripts/build-play-content.mjs`, generated `data/play/derived/{trails,roads}.json` (per goal: `roadSafety` pass
  list, quiet-zone polygons (200 m memorial / 100 m cemetery buffers from `coverage_segments.json`), stretch segment
  lists + lengths from `coastline.json` at 60 m (≥ 100 m), segment → chart square map, quiet segment ids,
  `play-safe-*` pools), copies to `app/composeApp/src/commonMain/composeResources/files/play/{play-content.json,
  derived-trails.json,derived-roads.json,tides/*}` and `backend/src/data/play/{current.json,<version>.json ≤ 8,
  derived-*.json,tides/*}`; `scripts/sync-app-assets.sh` (last step: `node scripts/build-play-content.mjs`, which
  re-derives with the freshly synced data and re-copies); `backend/package.json` (`build:play`; `test` runs
  `node ../scripts/build-play-content.mjs --check` before vitest, like `build:legal`)
- `--check` (CI, no map data): copies byte-equal to source, schema + rewards/caps/copy rules, immutability vs
  `data/play/published/`, daylight rule on 400 simulated days (JS port of the date subset). Data-dependent checks
  (refs, roadSafety, quiet zones, stretches, coastline buffer) run when `data/dist` and `data/roads/dist` exist and
  print `skipped: no map data` otherwise. The derived files are committed, so CI never needs the data.
- test-first: `scripts/build-play-content.test.mjs` run by `node --test scripts/` (`rejects 30 mph road in a pool`,
  `rejects null-speed collector`, `rejects pool member touching memorial zone`, `rejects Pritchard Park member`,
  `rejects in-place edit of published when`, `rejects copy containing race`, `rejects caps drift`,
  `derives 370 roadSafety roads on 2026-10-20 data (skip without data)`, `K5 has 2 stretches ≥ 100 m (skip without data)`)
- verify: `node --test scripts/ && node scripts/build-play-content.mjs --check && cd backend && npm test`
- rollback: revert; nothing reads the pack yet
- parallel: L1-2, L1-3 (no shared files; `scripts/build-tides.mjs` belongs to L1-3, this stage only reads its output)
- AI (ai.md AI-12/AI-1): riddle and lore text may be drafted offline by an agent; every published line is human-reviewed
  and sourced in the PR (14-day agenda preview pasted in the PR body).

### W2 (parallel group: L1-4 · L2-1 · L3-1)

**L1-4 — config model, strict parser, validator, PlaceIndex** (schedule st.4) [L1]
- files: `play/config/{PlayConfig,TimeExpr,PlayConfigValidator}.kt`, `play/schedule/PlaceIndex.kt`,
  `play/geo/{ChartSquare,QuietZones,RoadSafety}.kt`, `play/src/commonTest/.../FakePlaceIndex.kt`,
  `play/src/jvmTest/.../JvmDatasetPlaceIndex.kt`
- test-first: `TimeExprTest` (`HH:MM`, `sunset+30m`, `dawn-15m`, `+6d`, `24:00`, `invalid rejected`);
  `PlayConfigParseTest` (`v1 pack parses`, `unknown keys ignored`, `wrong enum is an error`, `schema 2 rejected`);
  `PlayConfigValidatorTest` (one failing fixture per schedule §4.3 rule, names as in schedule §5.4); `RoadSafetyTest`
  (`370 of 430 pass on 2026-10-20 data`, `every coast pool road passes`; skipped without data); `QuietZoneTest`
  (`buffers from segment geometry`, `touching member rejected`, `remembrance card allowed in its own zone`);
  `ChartSquareTest` (`anchor 47.55 -122.62`, `62 trails squares / 83 roads squares` skipped without data);
  `PlayConfigBundledTest` (jvm: bundled `composeResources/files/play/play-content.json` validates; refs skipped
  without data)
- verify: `cd app && ./gradlew :play:jvmTest`
- rollback: revert
- parallel: L2-1, L3-1

**L2-1 — theme tokens, shared components, all v1 strings** (ux §1, §2.12–2.13) [L2]
- files: `ui/theme/BtColors.kt` (`PlayColors`, `PlayLight`, `PlayDark`, `seaGlassWash`; `BtExtraColors.play`),
  `ui/theme/BtMotion.kt` (`PlayMotion.overshootSettle`, `anticipate`), `ui/theme/BtTheme.kt`/type (`marksDelta`,
  `marksTotal`), `BtSpacing` Play sizes, `ui/components/MapSheet.kt` (public + `maxHeightFraction`),
  `ui/components/{PlayBar,RewardChip,MetaChip,GateNote}.kt`, `play/fx/FxArt.kt` (placeholder glyph set per ux §7.2 #4:
  △ marks, 4 tiers, egg stamp, lantern unlit/lit, frog, ferry, kraken eye, pennant, quiet ring, gate icons),
  `composeResources/values/strings.xml` + `values/strings_roads.xml` (every v1 `play_*` key from product §7.3 and
  ux §6.2, minus v2 board keys; goal twins), `ui/Showcase.kt` (new "Play" page: roles, glyphs, chips, components)
- test-first (host, `composeApp/src/commonTest/.../ui/theme/`): `PlayColorsGuardTest` (`every play color ΔE ≥ 20 from
  status, recording, userTrack, positive — light`, `… dark`, `seaGlass text contrast ≥ 4.5 on surface, surfaceContainerLow,
  mapChrome`); `PlayCopyTest` (`no play_ string contains race run fast speed sprint beat points`, `no exclamation mark`);
  `GoalVocabularyGuardTest` extended (`every goal-tagged play key has a roads twin`); `MapSheetTest`
  (`maxHeightFraction caps at 60 percent`)
- verify: verify.kmp and `cd app && ./gradlew :androidApp:assembleTrailsDebug`
- rollback: revert (nothing references the tokens outside the showcase)
- parallel: L1-4, L3-1

**L3-1 — content + tides endpoints, server recurrence subset** (schedule st.7) [L3]
- files: `backend/src/play/{config.ts,recurrence.ts}`, `backend/src/routes/play.ts` (`GET /v1/play/content?v=`,
  `GET /v1/play/tides/{year}`), `backend/src/index.ts` (ROUTES), `data/play/golden/date-rules-2027.json` (written by
  L1-5's Kotlin golden; this stage commits a provisional copy generated by the JS side and L1-5 must reproduce it),
  `docs/mvp/api.md` (Play section)
- test-first: `backend/test/play-content.test.ts` (`200 with ETag pc-<version>-<sha12>`, `304 on If-None-Match`,
  `304 on ?v= equal version`, `cache-control max-age=300`, `public rate limit applies`, `tides 200 for 2027`,
  `tides 404 for 2031`); `backend/test/play-recurrence.test.ts` (`reproduces date-rules-2027 golden`,
  `kraken window Thu 06:00 to Sun 20:00 across DST`)
- verify: verify.api
- rollback: Worker revert; no client calls it yet
- parallel: L1-4, L2-1

### W3 (parallel group: L1-5 · L2-2 · L3-2)

**L1-5 — recurrence DSL + PlayScheduler** (schedule st.5–6) [L1]
- files: `play/config/Recurrence.kt`, `play/schedule/{DayPlan,PlayScheduler,Density,KrakenRotation,TideGate}.kt`,
  `play/src/jvmTest/resources/golden/agenda-{trails,roads}.tsv` (2026-10-26 → 2027-12-31),
  `data/play/golden/date-rules-2027.json` (regenerated; must equal L3-1's), `docs/play/e2e-ids.env` (generated:
  `E2E_TREASURE_ROAD`, `E2E_TREASURE_TRAIL` = the first treasure placement of week 2026-10-26 on a Play-safe feature
  reachable by `emu-route.sh` / `sim-route.sh`)
- test-first: `RecurrenceTest`, `RecurrenceGoldenTest`, `DensityTest`, `HeroConflictTest`, `DaylightRuleTest`,
  `KrakenRotationTest` (`kraken-roads@2026-10-29 is K5`, `@2026-11-05 is K2`, `wraps after 6`, `trails after 4`),
  `TideGateTest` (`inactive above 2.44 m`, `inactive without table`), `DeterminismTest` (`editing item B leaves A`),
  `QuietDayTest` (`Mar 30 has no kraken, treasure or challenge occurrence`), `PlaySchedulerGoldenTest`,
  `PlaySchedulerPerfTest`, `E2eFixtureTest` (`e2e-ids.env treasure ids are placements of week 2026-10-26`)
- verify: `cd app && ./gradlew :play:jvmTest` (update goldens with `-Pplay.updateGolden=true`; diff reviewed in PR)
- rollback: revert (no callers)
- parallel: L2-2, L3-2

**L2-2 — FxHost core, TrailMap/StatusLayers slots, MapTab + RecordScreen gates** (engine st.4, PF-8/PF-9, PD-19) [L2]
- files: `play/fx/{FxClock,FxHost,FxRenderer,FxPalette,FxLayers,FxOverlay,FxAnnouncer}.kt` (composeApp),
  `map/TrailMap.kt` (`fxOverlay: (@Composable MapOverlayScope.() -> Unit)? = null`, drawn before chrome;
  `renderOptions` hook for `maximumFps`), `map/StatusLayers.kt` (`casingFx: (@Composable @MaplibreComposable () ->
  Unit)? = null` after halos, before status lines), `ui/MapTab.kt` and `record/RecordScreen.kt` (plain `if
  (playEnabled)` blocks: UNDER_STATUS before StatusLayers, CASING via slot, OVER_STATUS after, OVER_LABELS before
  `LocationIndicatorLayer`), `data/SettingsRepository.kt` (`AppSettings.play: PlaySettings = PlaySettings()` with
  `enabled=false, everToggled=false, offerShown=false, sounds=false, ambient=true`)
- FxHost duties from the spikes: re-write every live `fx.*` globalState key on `MapEvent.StyleLoaded` (S1 #1); set
  `maximumFps = 30` while a Layer-tier kind animates and restore on stop (S1 #2); clock gated on elapsed frame time
  (S1 #3); hide/freeze overlay parts while `isCameraMoving && cameraMoveReason == GESTURE`, fade back on
  `CameraMoveEnded` (S4, PD-19); FX layer taps only through MapTab `onTap` (R7).
- test-first (host): `PlayGateTest` (`fx host inputs null when play off`, `StatusLayers casingFx null composes today's
  layers`); `AppSettingsCompatTest` (`old settings.json without play decodes play.enabled=false`);
  `FxLayerOrderTest` (`LocationIndicator composed after OVER_LABELS`, pure function over the band list);
  `FxPaletteGuardTest` (`no FX color within ΔE 20 of a status color, light and dark`)
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleTrailsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
- rollback: revert; every new parameter defaults to today's composition
- parallel: L1-5 (`app/play` only), L3-2 (backend)

**L3-2 — migration 0005 + claims on PUT /v1/coverage + GET /v1/play/me** (schedule st.9, PD-3) [L3]
- precondition: B0 done.
- files: `backend/migrations/0005_play.sql` (schedule §7.2 tables), `backend/src/play/{claims.ts,marks.ts,derived.ts}`
  (checks 1–7, server-derived marks incl. W-COV `max(prev,new)`, W-DONE, W-SQUARE from `derived-*.json`, quiet trails
  excluded), `backend/src/routes/coverage.ts` (optional `playConfig`/`play` parsed **after** existing validation;
  statements appended to the same `DB.batch`; response gains `play` only when the request had it),
  `backend/src/routes/play.ts` (`GET /v1/play/me`), `backend/src/routes/me.ts` (DELETE cascades Play rows),
  `backend/src/deps.ts` + `index.ts` (dev clock: `DEV_CLOCK_OFFSET_MS` honoured only when `ALLOW_DEV_AUTH=true` and
  the host is in `DEV_HOSTS`; needed by the e2e, see §3), `backend/.dev.vars.example`, `docs/legal/privacy-policy.md`
  (Play paragraph: week ids + occurrence keys, never days; OD-19) + `npm run build:legal`
- test-first: `backend/test/play-claims.test.ts` (`accepted with server-derived marks`, `client marks field ignored`,
  `idempotent retry reports accepted`, `stale_config`, `unknown_item`, `bad_occurrence`, `not_this_walk`, `no_bits when
  band above coverage share`, `too_late`, `too_early`, `week_cap accepts with 0 marks`, `dayKey over 7 per week ignored`,
  `killed item rejected`, `422 implausible rejects claims too`, `pure upload response byte-identical without play`,
  `50 claims per upload cap`, `deletion cascades play rows`, `dev clock offset ignored in prod host`);
  `migrations.test.ts` (`0005 applies after 0004 and is additive`)
- verify: verify.api
- rollback: Worker revert; 0005 is additive and inert; old Workers ignore the fields
- parallel: L1-5, L2-2

### W4 (parallel group: L1-6 · L2-3 · L3-3)

**L1-6 — marks rules, caps, ranks, Logbook model, claim evaluation, sensing logic, Finalizer hook** (product §3, §3.6; PD-12) [L1]
- files: `play/rules/{Marks,Caps,Ranks,Logbook,StartBalance}.kt`, `play/claim/{ClaimEvaluator,SensingTracker,UnclaimedReason}.kt`;
  `app/coverage/src/commonMain/.../Fixes.kt` (`keptFixes(fixes, params, proj): List<Fix>` sharing rules 1–4 with
  `filterFixes`, which becomes `keptFixes` + piecing; behaviour unchanged), `app/tracking/src/commonMain/.../Finalizer.kt`
  (constructor param `onGated: () -> (suspend (sessionId: String, kept: List<Fix>, summary: SessionSummary) -> Unit)? = { null }`,
  called after the merge inside `finalize`; provider evaluated per call so Play off = null)
- test-first: `MarksTest` (`W-COV trails floor nonQuiet/10`, `roads /25`, `F-INK buckets 60 70 80`, `riddle hints -25% floor 50%`,
  `remembrance 0`); `CapsTest` (`day cap 390 + 25 → +0 with play_log_cap` (PF5-7)); `RanksTest` (`2291 → Salal Scout, 709 to Madrona Wanderer` (PF12-1));
  `LogbookTest` (`totals equal sum` (PF11-4), `no time of day stored`, `start balance once across off/on` (PF1-2));
  `ClaimEvaluatorTest` (`walk 15 m at 10 m accuracy 1.3 m/s claims +25` (PF5-1), `12 m/s drive claims nothing` (PF5-2),
  `accuracy 35 m near 20 m treasure not sensed` (PF5-5), `kraken 1158 of 1158 m → band 95` (PF7-1), `50 % → nothing` (PF7-2),
  `tide above 2.44 m → play_unclaimed_tide` (PF7-7), `after civil dusk → dark`, `inside quiet zone → nothing, visit stamp`
  (PF10-2), `riddle unsolved still claims` (PF8-4), `idempotent per session and content`); `SweepTest` (`3 trails Sat+Sun
  → +100` (PF9-1), `walk finishing Mon 00:05 does not count` (PF9-2)); `FixFilterTest` extended (`keptFixes equals the
  points filterFixes keeps`); `FinalizerTest` extended (`onGated null when provider returns null`, `onGated receives
  kept fixes after mock and accuracy filter`)
- verify: `cd app && ./gradlew :coverage:jvmTest :tracking:jvmTest :play:jvmTest`
- rollback: revert; Finalizer default provider returns null
- parallel: L2-3 (composeApp only), L3-3 (backend)

**L2-3 — PlayRepository, content repo, play clock, place index, debug tools** (schedule st.8) [L2]
- files: `play/{PlayRepository,PlayContentRepository,PlayClock,DatasetPlaceIndex,PlayStateStore}.kt` (composeApp;
  state file `<privateNoBackupDir>/play/<goal>-state.json (PD-22)`: found occurrences, solved riddles, hints, logbook entries, start-balance
  flag), `net/PlayDtos.kt`, `net/ApiClient.kt` (`playContent(v, etag)`, `playTides(year)`, `playMe()`,
  `playChallenges()`), `graph/AppGraph.kt` (`Installed.playOrNull: PlayRepository?` — created lazily the first time
  `settings.play.enabled` is true; never constructed while off; `PlayClock` binding), `nav/DebugLaunch.kt`
  (`playClock`, `playTide`), `app/iosApp/.../` launch-arg parsing for `-btPlayClock 2026-10-29T14:00`,
  `-btPlayTide low|high|expired`, `-btPlayConfig bundled`, Android `MainActivity` debug extras `playClock`,
  `playTide`, `app/androidApp/src/debug/.../DebugPlugins.kt` (`PlayClockPlugin`: Real/Fixed/Offset, +1 d, +7 d,
  next Thu 06:00, Mar 30, low/high/expired tide, config source switch, agenda dump, benchmark, "Play FX" panel stub),
  `record/RecordingGraph.kt` (passes the Finalizer `onGated` provider → `AppGraph.installedOrNull()?.playOrNull?.gatedHook`)
- `PlaySensing`: a graph-level observer started by `PlayRepository` (Play on only) on `RecordingController.track`;
  feeds `SensingTracker` (L1-6) and spawns find moments in the scene.
- test-first: `PlayContentRepositoryTest` (`bundled when no cache`, `304 keeps cache`, `invalid fresh keeps last good
  and sets rejected`, `older cached than bundled uses bundled`, `play off makes no request`); `PlayRepositoryTest`
  (`fixed PlayClock`, `day rollover rebuilds plan`, `DST day`, `kill.items hides treasure and blocks sensing` (PF16-1),
  `expired tide table → gate_tide_unknown` (PF16-3)); `PlayOffNothingCreatedTest` (`play off: playOrNull null, no
  FxClock, no PlaySensing, no /v1/play request` (PF0-1, PF0-2)); `DebugLaunchTest` (`playClock parses island time`)
- verify: verify.kmp, then `cd app && ./gradlew :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
- reachability: debug drawer + launch args until L2-7 adds the Settings toggle (same wave pair, no merge to main between)
- rollback: revert; AppGraph additions are lazy
- parallel: L1-6, L3-3

**L3-3 — kraken aggregates, challenges, cron** (schedule st.10) [L3]
- files: `backend/src/play/challenges.ts` (stretch counts once per user, F-RETREAT, C-ISLAND, `ch-sweep` acceptance,
  W-WEEK close), `backend/src/index.ts` (`daily()` → `playDaily(env, now)`; no new cron), `backend/src/routes/play.ts`
  (`GET /v1/play/challenges?dataset=` → `{band, count}` only), `docs/mvp/api.md`
- test-first: `backend/test/play-challenges.test.ts` (`stretch counted once per user`, `retreat only when every stretch ≥ 3`,
  `cron twice same credits`, `W-WEEK from day_keys ≥ 2`, `ch-sweep accepted only with enough new completions in window`,
  `response has no names scores ranks or timestamps`, `counts below 5 hidden`)
- verify: verify.api
- rollback: Worker revert
- parallel: L1-6, L2-3
- board consent (v2, PD-1): **no v1 work**; `PUT /v1/play/board-consent` is not built. L2-10's `PlayOffRequestsTest`
  asserts turning Play off sends no request at all.

### W5 (parallel group: L2-4 · L2-7)

**L2-4 — Layer-tier effects + FX showcase (replaces the spike)** (engine st.5, st.7; effects §7) [L2]
- files: `play/fx/effects/{TreasureGlint,ExplorationFog,RiddleMarker,KrakenInk (casing + pool + far marker + grip
  LineLayer),KrakenLurk,FerryCrossing,FrogRockStamp}.kt`, `play/fx/showcase/{FxShowcaseScreen,FxHud,FxPresets}.kt`,
  `map/StyleSpikeScreen.kt` (`SpikeLaunch.Screen.FX` now opens the showcase; options `fx`, `state`, `t`, `theme`,
  `reduced`, `warp`; drop `fxAuto/fxTexture/fxNoTween/fxMaxFps`), `nav/Routes.kt` (`FxSpikeRoute` → `FxShowcaseRoute`),
  `nav/AppShell.kt` (route swap only), **delete** `play/spike/FxSpikeScreen.kt` and
  `play/spike/FxSpikeUiOptions.{android,ios}.kt` (decision: the spike's job is done, results live in `spikes/fx.md`
  and commit 12204a2; Surface render mode stays the default, so the render-mode expect/actual is not kept),
  `docs/play/screens/fx-*-{android,ios}.png` for this stage's effects
- test-first (host): `TreasureGlintTest` (`geoJson numeric ids = seq`, `phase in 0..1`, `empty list valid`);
  `FxLayerIdsTest` (`every FX layer id starts with fx- and is not in TappableTrailLayerIds`); `ExplorationFogTest`
  (`no fog polygon inside a quiet zone`, `lifted squares excluded`); `FerryPositionTest` (`position on timetable,
  hidden when expired, 1 Hz cap`); `KrakenInkTest` (`casing only on active stretches`, `repelled stretch removed per
  user`); `SpikeLaunchTest` (`screen=fx parses fx state t options`)
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64` + screenshots committed
- rollback: remove renderers from the registry (one line each) or revert
- parallel: L2-7 (disjoint: `play/fx/**`, spike files, StyleSpikeScreen vs `ui/play/**`, Settings, Me). **Routes.kt /
  AppShell.kt conflict:** L2-4's edit is a one-line route swap; L2-7 rebases on it (L2-4 merges first).

**L2-7 — opt-in and the off-map Play screens** (product §5, PF1, PF2, PF11, PF12, PF14; ux §3.1–3.4, 3.12–3.17) [L2]
- files: `ui/SettingsScreen.kt` (Play section between Units and Account; rows per ux §3.1 minus v2 board),
  `ui/play/sheets/{OptInCard,PlayIntroSheet,PlayOfferSheet,PlayOffSheet,PlayAboutSheet}.kt`,
  `ui/play/screens/{LogbookScreen,RanksScreen,WhatsOnScreen,ChallengesScreen,ChallengeSheet,IslandStoriesScreen,LoreCardScreen,PlayClockDebugScreen}.kt`,
  `ui/play/components/{LogbookRow,RankBadge,RankUpLine,ChallengeCard,WhatsOnRow,PlayCard}.kt`, `ui/MeScreen.kt`
  (PlayCard under the stat tiles, Play on only), `nav/Routes.kt` + `nav/AppShell.kt` (Logbook, Ranks, WhatsOn,
  Challenges, IslandStories, LoreCard routes; `-btScreen logbook|whats-on|challenges|ranks|stories`)
- test-first (host): `PlayToggleFlowTest` (`intro dismissed without Start keeps play off and makes no request` (PF1-3),
  `Start sets enabled and credits start balance once` (PF1-1, PF1-2)); `LogbookGroupingTest` (`groups by walk newest
  first` (PF11-1), `local_only shows note` (PF11-2), `never formats a time of day` (PF11-3)); `ChallengeCardTest`
  (`no countdown text`, `island shows band and count only` (PF9-4)); `WhatsOnTest` (`rows from agenda`, `quiet day
  only memorial row`, `island time suffix when phone zone differs`)
- verify: verify.kmp + `cd app && ./gradlew :androidApp:assembleTrailsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
- reachability: Settings toggle is the first production entry point (AC 1)
- rollback: revert; the toggle disappears and Play is unreachable again
- parallel: L2-4

### W6 (parallel group: L2-5 · L2-8 · L2-9)

**L2-5 — Overlay and Moment effects** (engine st.6; effects §3.1, 3.4, 3.7, 3.9, 3.12, 3.17; PD-19) [L2]
- files: `play/fx/effects/{TreasureFound,KrakenAttackOverlay,KrakenRecede,RiddleLight,FrogRockHop,ClaimPop}.kt`,
  `play/fx/FxTapTarget.kt`, `ui/play/PlayWake.kt` (MOMENT), `play/fx/showcase/FxPresets.kt` (adds this stage's
  presets; **owner of the file from here on**), `docs/play/screens/fx-*` for these effects
- S4 rules (PD-19): tentacles, burst, hop and lantern lighting are Overlay parts ≤ 3 s, hidden during gestures,
  and never carry geometry that must coincide with a line (the kraken grip stroke is L2-4's Layer `LineLayer`);
  pin bump and select ring are Layer `icon-size` swaps; `claim_pop` map variant starts screen-anchored at the sheet's
  reward chip or the HUD, never geo-glued under a gesture.
- test-first: `KrakenTimelineTest` (`:play`: `reducedAtMs is the final frame`, `total ≤ overlayMaxMs`, `RUN never loops`,
  `grip envelope 0 at spawn and after settle`); host `KrakenAnchorTest` (`anchor is the water point ≥ 60 m offshore`,
  `extent covers rig at z18`); `ClaimPopTest` (`coalesces pops within 400 ms`, `reduced shows +n beside HUD 1.5 s`);
  `PlayWakeTest` (`any tap before 2200 ms skips to lift`, `reduced = static card 1.2 s`);
  `FxShowcasePresetTest` (`every v1 effect id has a preset` over effects §7's 12 ids — **lands here, green only when
  L2-4 + L2-5 are both in**; AC 7)
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleRoadsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64` + screenshots
- rollback: remove renderers or revert
- parallel: L2-8 (map/record/results UI files), L2-9 (sync files)

**L2-8 — Map, Record and Results surfaces** (PF3–PF8, PF10; ux §3.5, 3.8–3.10, 2.1–2.4, 2.9) [L2]
- files: `ui/MapTab.kt` (row 2: PlayHud + ChallengesChip; `controlsTopPadding` 120 / fit padding +128 with Play on;
  tap order ux §3.5.4: `fx-*-hit` query **only after** the pure `TappableTrailLayerIds` query misses (PF3-5, PD-12);
  EncounterSheet in the existing sheet stack; legend Play group), `ui/play/map/{PlayHud,ChallengesChip,PlayMarkers
  (pins, clusters, coast label as Layer art),EncounterSheet,KrakenStretchRow,RiddleSheet,RiddleAnswerPicker,QuietBanner,
  PlayLegendGroup}.kt`, `record/RecordScreen.kt` (FindMoment card, FindsChip, Nearby section; notification suffix
  `play_notif_finds`), `ui/play/record/{FindMoment,PlayNearbyStrip}.kt`, `record/ResultsSheet.kt` (Play section after
  all pure content: LogbookRows, Not claimed, RankUpLine), `ui/CelebrationScreen.kt` (≤ 2 Play lines), new
  MapTab params `onPlayLogbook`, `onPlayChallenges` (defaults no-op; wired in L2-10)
- test-first (host): `MapTapPriorityTest` (`trail hit wins over play pin` (PF3-5), `pin hit opens sheet`, `cluster
  zooms to z14 no sheet`, `inked stretch tap is a trail tap`); `EncounterSheetStateTest` (`gated treasure has no CTA
  and a GateNote` (PF4-2/3), `remembrance has no marks CTA or motion` (PF4-4), `kraken 50 % row text` (PF7-2));
  `RiddleAnswerTest` (`wrong answer shows play_riddle_wrong only` (PF8-6), `armchair solve stamps 0 marks` (PF8-1),
  `list picker offered` (PF8-5)); `ResultsPlaySectionTest` (`pure content first then Play` (PF6-1), `unclaimed reasons`
  (PF6-2), `rank-up is a line not a modal` (PF6-3), `not shown again after summary_shown` (PF6-4))
- verify: verify.kmp + `cd app && ./gradlew :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
- rollback: revert; Play-off composition unchanged by construction (gated blocks)
- parallel: L2-5, L2-9

**L2-9 — claims on the coverage upload, Logbook restore, start balance** (PF15, §3.6.4–3.6.5, PD-3) [L2]
- files: `play/PlayClaimQueue.kt` (per-walk Play elements keyed by session id, file like `SyncFile`),
  `sync/SyncRepository.kt` (optional `playPart: suspend (walkIdBySession, walksInThisUpload) -> PlayPart?`; null
  provider = today's code path and byte-identical body), `net/ApiDtos.kt` (`CoverageUpload.playConfig`/`play`
  nullable, omitted when null; `CoverageResponse.play`), `graph/AppGraph.kt` (wire the provider only when
  `playOrNull != null`), `play/PlayRepository.kt` restore/start-balance methods
- PF15-5 conflict (see RISKS 4): `SyncRepository` drops walks over the per-upload cap uncredited (documented known
  limitation, pure behaviour). Play elements whose walk is still in `pending` stay queued; an element whose walk left
  `pending` uncredited becomes `local_only` (`over_cap`). Changing the pure cap behaviour is out of scope.
- test-first: `PlayClaimQueueTest` (`rejected → local_only` (PF15-2/3/6), `idempotent resend`, `walk over cap → over_cap`);
  `SyncRepositoryTest` extended (`play elements only for walks in walks[]`, `fields absent and body byte-identical
  when play off` (PF0-2), `payload has no timestamps days marks or coordinates` (PF15-4), `offline 2 claims sent on
  reconnect` (PF15-1)); `PlayRestoreTest` (`reinstall with 2410 server marks writes one restored entry` (PF15-7))
- verify: verify.kmp
- rollback: revert; server tolerates absence
- parallel: L2-5, L2-8

### W7

**L2-10 — integration wiring, offer, quiet day, pure-mode guards, docs** [L2, serial]
- files: `nav/AppShell.kt` (MapTab Play callbacks → Logbook / Challenges; PlayWake overlay above the shell; one-time
  offer after the first finished walk's results close, 400 ms later, never over a celebration (PF2); quiet-day banner
  path), `ui/MapTab.kt` (QuietBanner when `agenda.quietDay`), `app/AGENTS.md` (Play section: pack workflow
  `node scripts/build-play-content.mjs`, play clock extras/launch args, `-btScreen fx:<id>`, e2e pointer),
  `.github/workflows/ci.yml` (backend job runs `node --test scripts/` before `npm test`), `docs/mvp/api.md` final
- test-first (host): `PlayOfferTest` (`shows once after first results close` (PF2-1), `after celebration's results`
  (PF2-2), `never again after dismiss` (PF2-3), `never if toggled before` (PF2-4), `not for discarded or < 1 min`
  (PF2-5)); `PlayOffRequestsTest` (`turning off sends no request, keeps logbook` (PF14-1/2, PD-1)); `PureModeTest`
  (`play never enabled: no fx- layer id in any composed layer list, no PlayRepository, no Play analytics event`
  (PF0-1, PF0-4))
- verify: **verify.full** (`cd app && ./gradlew :coverage:jvmTest :tracking:jvmTest :play:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug :androidApp:assembleTrailsRelease :androidApp:assembleRoadsRelease :composeApp:linkDebugFrameworkIosSimulatorArm64`) + verify.api + the AGENTS.md xcodebuild line + the R8 smoke test (AGENTS.md "Release builds") with Play on and off
- rollback: revert the wiring commit; screens stay reachable from Settings

### W8

**E2E — AC 8 walkthrough + screenshots** (§3) [L2 + L3, serial; needs an emulator and an iOS simulator]
- files: `docs/play/screens/*.png`, `docs/play/screens/README.md` (commands run, build hashes, date), `scripts/play-e2e.sh`
  (optional wrapper of §3's commands)
- verify: the §3 assertions pass; screenshots committed; verify.full still green
- rollback: n/a (evidence only)

**PERF — engine stage 9 on device** [L2, serial; Pixel 6a / iPhone 12]
- files: `docs/play/perf.md`, tuning constants in `FxCaps`/`FxQuality`
- includes the S4 re-run on a physical Android device (fx.md) and the §3 budgets with `maximumFps` capping
- verify: verify.full + measurements recorded (p95 overlay draw ≤ 2 ms, clock STOPPED ≥ 95 % idle map time)
- rollback: constants only

---

## 2. Effect inventory (effect id → stage → showcase preset id)

Preset ids are `fx=<id>&preset=<preset>` (Android `--es fx <id> --es preset <preset>`; iOS `-btScreen fx:<id>
-btFxPreset <preset>`). Tier per effects §4.1 as amended by PD-19.

| effect id | tier (v1) | stage | preset ids | screenshots |
|---|---|---|---|---|
| `treasure_glint` | Layer (icon OVER_LABELS, halo/sparkle OVER_STATUS) | L2-4 | `fay-5` | `fx-treasure_glint-{light,dark}-t0-{android,ios}` |
| `exploration_fog` | Layer (UNDER_STATUS) | L2-4 | `island-20of62` | `fx-exploration_fog-…` |
| `riddle_marker` | Layer (OVER_STATUS pin, sway via transitions) | L2-4 | `winslow-unlit` | `fx-riddle_marker-…` |
| `kraken_lurk` | Layer (UNDER_STATUS ripples + OVER_STATUS eye) | L2-4 | `manitou-water` | `fx-kraken_lurk-…` |
| `ferry_crossing` | Layer (OVER_STATUS boat, UNDER_STATUS wake, 1 Hz setData) | L2-4 | `eagle-harbor-x60` | `fx-ferry_crossing-…` |
| `frog_rock` | Layer stamp + blink (L2-4); Overlay hop ≤ 1.8 s at rest (L2-5) | L2-4, L2-5 | `frog-z17` | `fx-frog_rock-…-t0/300/700/1100` |
| `kraken_attack` | Layer casing (CASING) + pool + far marker + **grip LineLayer** (L2-4); Overlay tentacles + splash ≤ 2.6 s, hidden in gestures (L2-5) | L2-4, L2-5 | `manitou`, `rockaway` | `…-t400/1100/1500/2600` |
| `kraken_recede` | Overlay + Layer un-draw | L2-5 | `manitou-repelled` | `fx-kraken_recede-…` |
| `treasure_found` | Overlay burst ≤ 1.6 s → Layer found shell | L2-5 | `fay-found` | `fx-treasure_found-…` |
| `riddle_light` | Overlay lighting → Layer lit pin | L2-5 | `winslow-solve`, `winslow-dark-3lit` | `fx-riddle_light-…` |
| `claim_pop` | Chrome/sheet (screen-anchored) | L2-5 | `map`, `results` | `fx-claim_pop-{map,results}-…` |
| `play_wake` | Moment | L2-5 | `island-home` | `fx-play_wake-…-t0/1900/2600` |

Reduced variant = each preset with `reduced=1` (final frame), screenshot `fx-<id>-reduced-{android,ios}.png`.
`FxShowcasePresetTest` (L2-5) fails if any of the 12 ids lacks a preset (AC 7).

---

## 3. AC 8 e2e script (opt-in → treasure → kraken → riddle → challenge → Logbook)

**Fixed inputs.** Island time **Thu 2026-10-29 14:00 PT** (daylight; first attack after the 2026-10-29 epoch: roads
**K5 Manitou / Murden Cove**, trails **T4 West shore**; week id `2026-10-26`; treasures live since Mon 05:00;
`ch-krakenweek` live). Tide forced low by the debug override (the real 14:00 tide is not checked here; the tide
gate itself is covered by `TideGateTest` and PF7-7's unit test). Treasure feature ids come from
`docs/play/e2e-ids.env` (generated and pinned by L1-5's `E2eFixtureTest`, so the script is exact and fails loudly if
the draw changes).

| step | Android emulator (roads app) | iOS simulator (trails app) |
|---|---|---|
| treasure | `$E2E_TREASURE_ROAD` (week 2026-10-26 draw) | `$E2E_TREASURE_TRAIL` |
| kraken | `road-manitou-beach-drive-northeast` (1,158 m stretch, ≥ 95 % → band 95, +80) | `trail-fairy-dell-trail` (T4 stretch, ≥ 95 % → +80) |
| riddle | `riddle-frog`: solve armchair (pick Frog Rock), claim by walking `road-northeast-spargur-loop-road` (snapped point within 30 m, +40) | `riddle-bond`: solve armchair (Grand Forest), claim by walking ≥ 100 m of `trail-grand-forest-west` (★★ +80) |
| challenge | `ch-krakenweek` sheet: progress "1 of 2 stretches", your band `{band, count}` | `ch-krakenweek`: "1 of 2" (T4 has two stretches ≥ 100 m), band |
| bonus (seen, not asserted) | `lore-frog` hop at the same replay | — |

**Backend (local wrangler, dev clock).**
```sh
cd backend && npm install && cp .dev.vars.example .dev.vars
# .dev.vars: ALLOW_DEV_AUTH=true and DEV_CLOCK_OFFSET_MS = (2026-10-29T21:00:00Z − now) in ms (L3-2; dev hosts only)
node -e 'console.log("DEV_CLOCK_OFFSET_MS="+(Date.parse("2026-10-29T21:00:00Z")-Date.now()))' >> .dev.vars
npm run dev                                   # applies 0001–0005 locally, wrangler on 0.0.0.0:8787
```
(An offset, not a fixed time: the coverage plausibility rule needs real elapsed time between walk uploads.)

**Android emulator (roads flavour).**
```sh
cd app && ./gradlew :androidApp:assembleRoadsDebug && cd ..
export ANDROID_SERIAL=emulator-5554; source docs/play/e2e-ids.env
adb install -r app/androidApp/build/outputs/apk/roads/debug/androidApp-roads-debug.apk
# pure baseline screenshots first (PF0-1): Map, Roads list, Me, Settings with Play off → pure-*-android.png
adb shell am start -n com.bainbridgetrails.roads.dev/com.bainbridgetrails.app.MainActivity \
  --es playClock 2026-10-29T14:00 --es playTide low
# debug drawer › Backend › Local wrangler dev (10.0.2.2:8787) › Apply & Restart; Profile › dev sign-in
# Settings › Play on › Start  → wake → Map with Play
scripts/emu-route.sh "$E2E_TREASURE_ROAD"                    # walk 1: treasure find moment, Finish
scripts/emu-route.sh road-manitou-beach-drive-northeast 2 900   # walk 2 (whole road ≈ 46 min at 1.3 m/s; 900 s covers > 60 % of the stretch): kraken repel, Finish
# Map › riddle pin › Pick on the map › Frog Rock → solved stamp
scripts/emu-route.sh road-northeast-spargur-loop-road        # walk 3: riddle claimed (+ Frog Rock lore), Finish
# Map › Challenges chip › Kraken week; Play chip › Logbook; Settings › Play off → pure map again
```
Assumption: `scripts/emu-route.sh <feature-id>` replays the feature's geometry at walking pace with ≤ 10 m accuracy as
in `docs/roads/screens/README.md`; if its argument form differs, adapt the form, never the ids. Sync after each
Finish (automatic; Me › sync status shows done) before the next replay.

**iOS simulator (trails).**
```sh
cd app && ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 && cd .. && xcodebuild -project app/iosApp/iosApp.xcodeproj \
  -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/bt-dd build
xcrun simctl install <udid> /tmp/bt-dd/Build/Products/Debug-iphonesimulator/BainbridgeTrails.app   # pass the UDID: two simulators may be booted
source docs/play/e2e-ids.env
xcrun simctl launch <udid> com.bainbridgetrails.app -btDevSignIn 1 -apiBaseUrl http://localhost:8787 \
  -btPlayClock 2026-10-29T14:00 -btPlayTide low -btScreen settings -acceptSimulatedLocations 1   # without it every simulated fix is dropped as mock
scripts/sim-route.sh "$E2E_TREASURE_TRAIL"; scripts/sim-route.sh trail-fairy-dell-trail; scripts/sim-route.sh trail-grand-forest-west
xcrun simctl launch booted com.bainbridgetrails.app -btPlayClock 2026-10-29T14:00 -btScreen logbook   # and challenges, me
xcrun simctl io booted screenshot docs/play/screens/<name>-ios.png
```

**Screenshots** (`docs/play/screens/`, each `-android.png` and `-ios.png`): `pure-map`, `pure-list`, `pure-me`,
`pure-settings`, `e2e-01-settings-play`, `e2e-02-intro`, `e2e-03-wake`, `e2e-04-map-play`, `e2e-05-treasure-sheet`,
`e2e-06-record-find`, `e2e-07-results-treasure`, `e2e-08-kraken-sheet`, `e2e-09-record-kraken`, `e2e-10-results-kraken`,
`e2e-11-riddle-sheet`, `e2e-12-riddle-solved`, `e2e-13-results-riddle`, `e2e-14-challenges`, `e2e-15-challenge-krakenweek`,
`e2e-16-logbook`, `e2e-17-me-playcard`, `e2e-18-play-off-map`; plus the §2 `fx-*` set.

**Assertions.**
1. `pure-*` and `e2e-18-play-off-map` show no Play chrome, no `fx-` layers (debug showcase layer list or logcat
   `fx layers: 0`), and match the pre-Play build visually (PF0-1); wrangler log shows no `/v1/play/*` call while off
   and coverage bodies without `play` (PF0-2).
2. Walk 1 results: treasure line `+25|+40|+60|+150` per tier; after sync the server response `play.accepted` has
   `treasure-weekly-roads@2026-10-26#NN` (wrangler log) and Logbook entry not `local_only`.
3. Walk 2: `kraken-roads@2026-10-29#0` accepted with band 95, +80; KrakenAttackSheet row "You sent it back".
4. Walk 3: riddle claimed `+40` (roads) / `+80` (trails); the armchair solve entry is a stamp with no number.
5. Challenges: `ch-krakenweek` shows shared progress and `{band, count}`, no names, no countdown (PF9-4).
6. Logbook total equals the sum of its rows and the `GET /v1/play/me` total (PF11-4, PD-3); no time of day anywhere.
7. Play chip marks = Logbook total; rank title matches the ladder.
8. Unit and backend suites green: verify.full + verify.api (AC 8's test half).

---

## 4. Reachability

| new thing | production call site | stage |
|---|---|---|
| Play toggle, intro, wake | Settings › Play | L2-7, L2-5 |
| Offer | AppShell after first results close | L2-10 |
| FxHost, effects | MapTab, RecordScreen (gated) | L2-2, L2-4, L2-5 |
| HUD, chips, markers, sheets | MapTab | L2-8 |
| Logbook, Challenges | HUD / Challenges chip, Me › PlayCard, Settings rows | L2-7, L2-10 |
| What's On, Island stories, Ranks | Me › PlayCard, Settings › Ranks | L2-7 |
| Claims, restore | Finalizer hook → PlayRepository → SyncRepository | L1-6, L2-3, L2-9 |
| `/v1/play/content`, `/me`, `/challenges` | PlayContentRepository, PlayRepository | L2-3 |
| Showcase, play clock | debug only (`screen=fx`, drawer, Settings debug row) | L2-4, L2-3 |

## 5. Cross-cutting

- **swip:** n/a (`factory.swip: false`). Analytics: product §7.4 events through `Analytics.track` (debug logs only);
  none while off (PF0-4 test in L2-10).
- **debugdrawer:** yes — `PlayClockPlugin` + "Play FX" panel (L2-3, filled by L2-4/L2-5); iOS parity via launch args.
- **atlas:** n/a (`product_atlas: none`).
- **goldens:** no UI snapshot tooling (`ui_snapshot: none`); scheduler goldens (`agenda-*.tsv`,
  `date-rules-2027.json`) are review artifacts; visuals = `docs/play/screens/` both platforms.
- **migrations:** `0004` resolution (B0, owner) then additive `0005_play.sql` (L3-2); deploy order = README (migrate,
  then Worker). App side: `AppSettings.play` defaults off; old `settings.json` decodes unchanged.

## 6. REVIEW (in order)

1. adversarial-reviewer on **this plan** before W1.
2. After W3: privacy-security-reviewer on L3-2 (claims, week-and-place disclosure, dev clock gate, server-derived
   marks) and on L2-9 when it lands (payload contract).
3. compose-ui-reviewer + mobile expert on each UI stage as it merges: L2-1, L2-2, L2-4, L2-5, L2-7, L2-8, L2-10
   (Compose correctness, recomposition per frame = 0, battery/clock policy, a11y, reduced motion). The mobile-expert
   pass on L2-10 + PERF is the **AC 9 gate**.
4. privacy-security-reviewer on L3-3 (`{band, count}` only, counts < 5).
5. kmp-verifier: verify.full + verify.api + xcodebuild before any green claim (every wave end; mandatory after L2-10
   and after E2E).
6. simplification-reviewer on the branch.
7. adversarial-reviewer on the branch, pre-PR (checks AC 1–9 against `README.md` matrix and §3 evidence).

## 7. RISKS (what could make this plan wrong)

1. **S4 on device.** If a physical-device re-run (PERF) shows drift is worse than one frame, even short overlay parts
   at rest may look detached; fallback is Layer-only heroes (lose tentacle richness, keep every v1 id).
2. **B0 is an owner-only destructive step.** If it slips, L3-2/L3-3 block and AC 2/8's server half waits; client
   stages proceed (claims stay `pending`).
3. **e2e clock vs server time rules.** The play clock is in the future of real time; without L3-2's dev clock offset
   every claim is `too_early`. The offset must be impossible in prod (tested).
4. **PF15-5 is not satisfiable** without changing pure `SyncRepository`'s over-cap drop (a documented limitation that
   only bites > 200 queued walks). Plan default: `local_only (over_cap)`; needs a product ruling to amend PF15-5.
5. **Weekly treasure draw drift.** Any pack or scheduler change can move `E2E_TREASURE_*`; `E2eFixtureTest` makes it
   loud, the regenerated env file makes it cheap.
6. **Real tide at the e2e time** is overridden; the gate is only unit-tested end to end, not on device.
7. **Art.** `FxArt` placeholders (ux §7.2 #4) may not meet the effects §6 craft checklist; final art is content work
   outside this plan's stages and can land after v1 behind the same ids.
8. **Kotlin/JS parity** of recurrence and of the derived tables (two implementations); mitigated by the shared golden.
9. **TBC facts (OD-16)** keep several eggs/lore `enabled:false`; v1 content may be thinner than product §4 lists.
10. **ux.md** precedence text (ux above effects/engine) differs from README; this plan follows README/decisions.

**Cut-to-ship order** (drop first → last; AC 1–9 stay satisfiable):
1. Island stories + LoreCard screens (lore still readable in EncounterSheet).
2. List badges, "Play this week" filter, Detail "Play on this trail" section (not built in any stage above; keep out).
3. What's On screen (agenda still visible on map + Challenges).
4. Ranks screen (rank shows on chip, Me card, Logbook header).
5. `ferry_crossing` motion (static sprite at timetable position; keep the preset).
6. `frog_rock` hop overlay (stamp + blink only).
7. Sounds (already default off) and `kraken_lurk` motion (static eye).
8. PERF device measurements beyond the AC 9 review's minimum.
Never cut: opt-in/off guarantees, marks + Logbook + sync, the four AC 8 content steps, every v1 showcase preset,
reduced-motion variants, quiet zones/day, server-derived marks.
