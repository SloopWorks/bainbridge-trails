# Play decisions (spec review round 1)

Date: 2026-10-20. Resolves the round-1 adversarial review of the Play specs
(`docs/play/reviews/2026-10-20-round1-specs.md`, verdict NEEDS-RESPEC, 35 findings). Finding ids below are the
review's numbers (P0 1–7, P1 8–25, P2 26–35).

**Precedence:** this file > `product.md` (rules, economy, safety) > `schedule.md` (time, determinism, content pack
schema) > `engine.md` (normative for rendering) > `effects.md` (conforms to all three) > `ai.md` (trimmed to the cut
line) > the brief. `docs/mvp/decisions.md` and `docs/roads/decisions.md` still win for anything they already decide
(pure mode is unchanged by Play). Glossary and AC traceability: `docs/play/README.md`. The schedule decisions
SD-1 … SD-11 (`schedule.md` § 0) and the engine decisions PF-1 … PF-9 are accepted under PD-5 … PD-13.

## PD-1 Opt-in semantics
- **Context.** The brief makes Play opt-in with the pure app byte-for-byte unchanged when off. The specs said "no Play
  calls when off" but also "board row hidden after the next sync" (24), and PF0-1 asked for pixel snapshot tests the
  repo has no tooling for (22).
- **Decision.** Play is off on install and after update; on only from Settings or the one-time offer after the first
  finished walk (product § 5). Off = no Play node, no `fx-` layer, no clock, no sensing observer, no `/v1/play/*`
  request and no `play` / `playConfig` field on the coverage upload. One carve-out (v2, with the board): a single
  `PUT /v1/play/board-consent {visible:false}` when Play is turned off. PF0-1 = emulator/simulator screenshot diffs
  against the pre-Play build plus a host test that nothing Play is composed or created while off.
- **Consequence.** `verify.ui_snapshot: none` stays true; pure-mode evidence lives in `docs/play/screens/pure-*.png`
  and the e2e script. Turning Play off keeps marks and Logbook, deletes nothing.
- **Resolves:** 22 (PF0-1 part), 24.

## PD-2 Marks economy model
- **Context.** Two economies (product vs schedule), W-COV defined as a share of the island total (falls when the map
  grows, 21), F-INK paid per metre beyond 60 % although the server never sees metres (31), caps in three places (6).
- **Decision.** Product § 3 is the only economy. W-COV is a **fixed rate per goal** (trails 1 mark / 10 m, roads
  1 mark / 25 m), cumulative, monotone (server credits `max(previous, new)`); quiet trails earn no W-* mark. F-INK pays
  by bucket of the stretch walked in one walk: ≥ 60 % → 60, ≥ 80 % → 70, ≥ 95 % → 80. **One caps table** (product
  § 3.5): 400 found marks per island day (client), 2,800 per week id (server), find claims' occurrence must end ≥
  upload day − 2 days, week-keyed items (dayKeys, W-WEEK, challenge contributions) until week end + 7 days, clock
  tolerance stated once (occurrence start ≤ server now + 1 h; week id = current or previous). Worked examples
  recomputed under v1 rules.
- **Consequence.** v1 whole-map totals ≈ 22.9k marks (trails) / 23.3k (roads); v2 park, milestone and island marks
  add ≈ 4.8k / 4.2k. `schedule.md` `caps` mirrors the table and the validator rejects any drift.
- **Resolves:** 6 (caps part), 21, 31.

## PD-3 Server-derived marks; claims ride the coverage upload
- **Context.** Product sent per-walk summaries with `PUT /v1/play/week`; schedule sent `PUT /v1/play/finds` trusting
  client `points` (6). `no_walk` let any walk unlock every occurrence that day and a rolled-back clock replay two weeks
  (10). The product payload didn't match `SyncRepository`'s one batched `CoverageUpload` of up to 200 walks (11).
  Config skew made in-flight claims fail (9). Reinstall lost the Logbook while the server total stayed (35).
- **Decision.** Claims ride `PUT /v1/coverage` as `playConfig` + `play: [{walkId, claims: [{id, kind, weekId,
  band?}], dayKey?, hints?}]`, one element per walk in that upload's `walks[]`; walks over the cap keep their Play
  element queued. The server evaluates each claim against the pack version it names (the Worker keeps the last 8),
  requires the claim's `walkId` to be credited by the same upload and line/stretch items' anchor segments to carry
  the user's bits, applies PD-2's time rules, and **derives every mark from its content table**; the client never
  sends a mark. W-DAY / W-WEEK come from `dayKey`s; F-RETREAT and C-ISLAND from the daily cron. A 422 rejects claims
  with the coverage (`local_only`). `GET /v1/play/me` restores the Logbook total after a reinstall.
- **Consequence.** `PUT /v1/play/finds`, `PUT /v1/play/week` and the race tables are gone. Pure requests and
  responses are byte-identical when `play` is absent. Migration `0005_play.sql` (PD-11).
- **Resolves:** 6, 9 (payload part), 10, 11, 35.

## PD-4 Safety rules: nothing timed, nothing at the water, no third parties
- **Context.** Timed races with countdown, ghost pace and finish times on roads with no shoulder data (2); a
  `tide_cut` obstacle over a beach and kraken on "beach access" trails (19); a rain feed and NWS cron (18).
- **Decision.** No challenge measures time: a challenge shows its window and progress and finishes with a stamp;
  community results are `{band, count}` only, never names or ranks. Type `challenge` everywhere (item type, tables,
  routes, copy). Nothing is placed on a beach; shore content sits at road or trail ends above it. No weather data,
  no notifications in v1, no third-party calls from the phone (product PP-5…PP-10, § 8).
- **Consequence.** `race_track`, named standings, AI-6 write-ups, `tide_cut`, weather and rain are cut (PD-15).
- **Resolves:** 2, 18, 19.

## PD-5 Daylight
- **Context.** Three night rules: product (finds civil dawn → civil dusk, creatures sunrise + 30 → sunset − 30),
  schedule (playHours 06:00–21:00, `afterSunsetOk: winslow`, dusk items, "after dark" theme), effects (owl at dusk
  and dark, coyote at dawn and dusk, Prue's window after sunset, "best night feature") (1).
- **Decision.** Anything that resolves is resolvable only inside **daylight** = [civil dawn, civil dusk] (∩ the
  06:00–21:00 clamp); creatures only inside **creature hours** = [sunrise + 30 min, sunset − 30 min]. The schedule
  validator rejects any item whose authored window leaves those bounds on any of 400 simulated days; multi-day items
  sleep at night. `afterSunsetOk`, every dusk item and the "Grand Forest after dark" theme are deleted. Effects have
  no dusk, dark, rain or tide-high triggers; night looks are dark-theme looks, never time-of-day.
- **Consequence.** Walks after dark still earn walk-derived marks, never finds. In December finds run ≈ 07:20–16:50.
- **Resolves:** 1.

## PD-6 Quiet zones from dataset geometry
- **Context.** The memorial "respect zone" circle sat ≈ 0.6 km from the memorial trail and covered nothing; pools
  contained `trail-pritchard-trail`; cemeteries were missing; product paid marks on the memorial trail and Pritchard
  Park (3). Schedule turned BIJAC's Mochi Tsuki into a chest (23).
- **Decision.** Quiet zones are built at pack build from geometry: 200 m around every segment of
  `trail-bainbridge-island-japanese-american-memorial-trail` (7 segments) and 100 m around every segment of
  `trail-blakley-cemetary-trail` (3 segments), plus owner-tagged places. Pritchard Park is out of every pool and is
  never a Play park. The validator rejects any anchor inside a zone and any pool member that touches one (today:
  Pritchard Trail, Blakely Harbor Trail, Blakely Trail, Blakely Hills Trail, Taylor Ave NE, Welfare Ave NE). Quiet
  trails earn no W-* mark; pure coverage is unchanged. Mar 30 is an island-wide quiet day. No gameplay on Japanese
  American community events without BIJAC's consent.
- **Consequence.** The trails kraken coast T1 (Blakely Harbor) and the Blakely seed treasure are dropped; the trails
  rotation has 4 coasts. The remembrance card is the only marker inside its own zone.
- **Resolves:** 3, 23.

## PD-7 One `roadSafety` rule
- **Context.** Product allowed ≤ 30 mph, schedule ≤ 25; product's "406 of 430" counted private and unknown roads;
  several seeded items failed the stricter rule; effects put Frog Rock's trigger at a 35 mph junction (4).
- **Decision.** A counted road is Play-safe when `category ∈ {residential, collector, arterial}` (never
  `state_route`), `public: true`, `ownership` starts with `public_`, and `speed_mph ≤ 25`; `speed_mph: null` passes
  only for `residential` (Washington's 25 mph city-street default; TBC). Computed at pack build, never hard-coded.
  On `roads.json` 2026-10-20: **370 of 430** pass (332 residential incl. 198 untagged, 31 collector, 7 arterial);
  60 fail (24 at 30 mph, 21 at 35, 1 at 40, 1 state route at 50, 8 private, 3 unknown ownership, 2 untagged
  collectors). Frog Rock's trigger is snapped to `road-northeast-spargur-loop-road` (25 mph).
- **Consequence.** Kraken coasts recomputed: K1's Country Club Rd NE and Fort Ward Hill Rd NE (30 mph) drop and South
  Beach Drive joins K4; Manzanita Rd NE drops from K7; roads rotation has 6 coasts. `lore-fay` becomes armchair-only.
- **Resolves:** 4.

## PD-8 Kraken window
- **Context.** Schedule anchored the kraken to the highest daytime high tide and added a king-tide kraken, while
  product allowed shore content only near low water (5); a Thursday-only kraken broke the weekday + weekend rule and
  an Oct 31 second attack overlapped the weekly one (25).
- **Decision.** The attack window is **clock-based, Thu 06:00 → Sun 20:00** island time, every week, one coast per
  goal by fixed rotation. A stretch is active only in daylight and while the predicted tide at Eagle Harbor (9445882)
  is ≤ 8 ft (2.44 m) MLLW; a missing or expired tide table keeps stretches inactive. No king-tide kraken. v1 bundles
  the NOAA high/low table for this one rule (low-water windows for shore point items are v2).
- **Consequence.** The window covers a weekday and a weekend day; the v2 Oct 31 attack yields and never overlaps.
- **Resolves:** 5, 25.

## PD-9 Content definitions: product § 4 is the authority
- **Context.** Treasures, eggs, riddles, kraken, frog and ferry meant different things in each doc (7); eggs needed
  15 m accuracy under canopy (33).
- **Decision.** `schedule.md` § 3.2 and the § 4.7 example pack encode product § 4 exactly: treasures weekly, 12
  (trails) / 20 (roads) in 4 tiers from Mon 05:00; eggs permanent, global, hidden, 15 m, once ever (25 m for
  owner-flagged canopy eggs); riddles permanent in v1 (2/week in v2), solved from home as a stamp, claimed by
  walking; kraken by coast rotation; Frog Rock fixed at its landmark; the ferry sprite visual only in v1. Effects
  cards stop defining triggers.
- **Consequence.** The daily chest, "the frog moves", Ferry Friday, personal-salt eggs (v2) and map fragments (v2)
  leave the v1 schedule.
- **Resolves:** 7, 25, 33.

## PD-10 Determinism, seeds, time units
- **Context.** Seeds hashed the pack version (a bump reshuffled treasures) and a live server aggregate (8); engine
  used `String.hashCode()` (29); a "Sun 23:59" week end left a gap (26); two grids (27); two meanings of season (28).
- **Decision.** Per-item seeds: `fnv1a64(itemId)` mixed with the island seed and the occurrence date (SplitMix64;
  never `String.hashCode()`, engine seeds too). Aggregates are baked into the pack at publish. Occurrence keys
  `<itemId>@<Monday-or-start date>[#n]` are the claim ids. Week = [Mon 00:00, next Mon 00:00) island time, week id =
  the Monday date. One grid: the chart square, 0.01° × 0.015° anchored at 47.55 N, 122.62 W (62 trails / 83 roads
  squares today). Season = island quarter (Storm, Strawberry, Madrona, Kraken); a week's season is its Thursday's.
- **Consequence.** Offline phones and the server agree; a pack bump never moves an unchanged item.
- **Resolves:** 8, 26, 27, 28, 29.

## PD-11 Content pack contract
- **Context.** Editing a published item's `when`/`where` broke in-flight claims (9); `/v1/play/pack` vs
  `/v1/play/content`, kill switch `status: paused` vs `kill{}` (30); `0004_cleanup.sql.pending` before a new 0005
  (34).
- **Decision.** `schedule.md` § 4 owns the schema (`play-content.json`, served at `GET /v1/play/content?v=` with
  ETag). Published item ids are immutable: a change is a new id plus `expires` on the old one, enforced by the
  validator against the previous pack; claims carry `playConfig`; the Worker keeps the last 8 packs. The kill switch
  is one shape, the top-level `kill { all, types, items }`. Play's migration is `0005_play.sql`, landing only after
  0004 is renamed and applied.
- **Consequence.** Owner edits stay release-free; validation runs in the build script, the JVM test and on device.
- **Resolves:** 9, 30 (endpoint and kill switch), 34.

## PD-12 Claim pipeline on device
- **Context.** `Finalizer.finalize` keeps the gated fixes internal and the mock/accuracy/speed filter lives in
  `coverage/Fixes.kt` (12); RecordScreen owns its own `TrailMap` and the engine turned ambient off while recording
  (13); product opened the trail preview first while engine queried `fx-treasure-icon` first (14).
- **Decision.** `Finalizer` gains `onGated(sessionId, keptFixes)` after the coverage fix filter inside `finalize`,
  null when Play is off, idempotent per (sessionId, contentId). Live sensing runs in a graph-level `PlaySensing`
  observer of the recording's fixes; RecordScreen is a gated MapFx call site. Tap precedence is product-first: Play
  takes a tap only when the `TappableTrailLayerIds` query has no hit.
- **Consequence.** Screen-off walks claim at Finish; pure tap behaviour is unchanged.
- **Resolves:** 12, 13, 14.

## PD-13 MapFx engine (engine.md PF-1 … PF-9)
- **Context.** The engine design was PROPOSED; effects disagreed with it on hero length, reduced motion, bands,
  arbitration, idle, recording, layer animation, projection, StatusLayers and the budget device (15).
- **Decision.** Engine PF-1 … PF-9 are accepted and normative: three tiers (Layer, Overlay, Moment) over one scene
  (PF-1, PF-3); one `withFrameNanos` clock with reduced motion = the **final frame**, 20 s idle stop, ambient off
  while recording, `globalState` ≤ 30 Hz (PF-2); bands UNDER_STATUS, **CASING** (new: `StatusLayers.casingFx`),
  OVER_STATUS, OVER_LABELS, OVERLAY, MOMENT; HERO / NOTICE / AMBIENT caps with an 8 s HERO cooldown; Overlay parts
  ≤ 3 s (`overlayMaxMs`) then hand off to their Layer part (PF-3); `placedAt` projection (PF-4); rendering contract
  (PF-5); assets, no Lottie, shaders v2 (PF-6); budgets on Pixel 6a / iPhone 12 (PF-7); `:play` module with
  MapTab, RecordScreen, TrailMap, StatusLayers and AppShell as the only gated call sites (PF-8); pure mode untouched
  (PF-9). Seeds use fnv1a64. Stages 0–7 and 9 ship in v1; stage 8 (shaders) is v2.
- **Consequence.** Spikes S1–S4 gate stages 5–6 as written.
- **Resolves:** 13 (call site), 15, 29 (engine part).

## PD-14 Effects conformance
- **Context.** Effects defined its own director, slots, clock and triggers (15); `points_pop` fired mid-walk while
  marks are provisional until Finish (16); a daily streak motif contradicted "never punish" (17).
- **Decision.** `effects.md` conforms to engine (bands table, arbitration mapping, final-frame reduced, `globalState`
  clock, `placedAt`, budget device), to product (triggers restated, product wins) and to schedule (timing). Mid-walk
  finds are provisional and announce `play_find_a11y`; `claim_pop` plays only on claims (results at Finish,
  server-derived marks on sync). A new `exploration_fog` card covers the only Play fog. Showcase hosts every v1
  effect (AC 7).
- **Consequence.** `FxDirector`, the P1–P4 slot names (kept only as a mapping), the 10 Hz tick clock and the Pixel 4a
  target are gone.
- **Resolves:** 15, 16, 17 (with PD-15).

## PD-15 v1 cut line
- **Context.** The specs described more than one release could build and review; the review drew a cut line.
- **Decision.** Product's "v1 scope" section is the cut line, adjusted only where a fix changed it (the tide table
  ships for the kraken rule). **Ships:** opt-in; W-COV, W-DONE, W-SQUARE, W-DAY, W-WEEK, starting balance; Logbook
  and ranks; weekly treasures; permanent eggs; weekly kraken; permanent riddles; fixed-anchor lore; the ferry
  sprite; `ch-sweep` and `ch-krakenweek`; the Mar 30 quiet day; claims on the coverage upload; the listed effects;
  engine stages 0–7 and 9; the e2e flow. **Later (v2):** the Play board and seasons, creatures, Prue's House,
  brambles, F-HORN and `ch-crossing`, tide-gated shore items, personal-salt eggs, map fragments, other event windows,
  shaders, Lottie, notifications, ai.md beyond offline authoring, plus the unlisted W-PARK*, roads milestones,
  W-ISLAND, C-WAVE and other challenges. **Cut:** `race_track`, `streak_float_line`, weather and rain, morning fog,
  `tide_cut`, `fallen_tree`, `fog_bank`, the king-tide kraken, named standings and write-ups, Mochi Tsuki gameplay.
- **Consequence.** Later items keep their design text with **(v2)** tags; cut items are one-line tombstones.
- **Resolves:** 2, 17, 18, 19, 23 (scope part).

## PD-16 Vocabulary and honesty
- **Context.** "Points", "ledger", "ledger pill", "race", "respect zone", "wrap" and three meanings of "fog" (16, 30);
  "not where or when" was false (20); unsourced or conflicting facts (32).
- **Decision.** The glossary in `docs/play/README.md` is binding: marks, Logbook, Play chip, challenge, quiet zone,
  kraken attack / stretch / repel, fog = exploration fog, chart square, occurrence key, week id, island time,
  daylight, creature hours. Privacy copy says what is true: Play syncs which items you claimed and in which week; a
  fixed-place claim tells the server you were near it that week; never the day, the time or the route; the privacy
  policy gains a Play paragraph before ship (product OD-19). Unverified facts are marked **TBC** (product OD-16).
  Doc ids no longer collide: schedule decisions are `SD-n`, product safety rules `S-n`.
- **Consequence.** Pack copy validation rejects "race", "run", "fast", "speed" and "points".
- **Resolves:** 16, 20, 30 (names), 32.

## PD-17 Acceptance-criteria ownership
- **Context.** AC 7 (showcase for every effect), AC 8 (e2e walkthrough) and AC 9 (mobile-expert review) were owned
  by no doc or only by the engine (22).
- **Decision.** `docs/play/README.md` holds the AC 1–9 → section matrix. AC 7 = the engine showcase hosting every v1
  effect (effects § 7 list, guarded by a host test). AC 8's e2e script, with the play-clock override on both
  platforms (`PlayClockPlugin`, `-btPlayClock`) and `scripts/emu-route.sh`, lives in `docs/play/plan.md`. AC 9 is a
  review gate in the engine plan and the plan.
- **Consequence.** `docs/play/plan.md` is the next document (plan-author); this round does not write it.
- **Resolves:** 22.

## PD-18 UX spec accepted; UI follows the reconciled rules
- **Context.** `docs/play/ux.md` was written in parallel with the round-1 reconciliation against the earlier
  product.md, so it carried pre-reconciliation vocabulary (points, races, 30 mph) and open items that the other docs
  had since settled.
- **Decision.** ux.md is ACCEPTED after a conformance pass (2026-10-20). Its design decisions stand: sea glass is
  `#037245` / `#45E0AE` (ΔE ≥ 20 from spruce and every status hue); the Play HUD sits top-left on a second chrome row,
  nothing Play on the right column; every tappable marker is a lifted pin, stamps are never tappable, line taps stay
  pure; island zoom shows dots/clusters, never figures; one non-modal EncounterSheet capped at 60 %; Play selection
  never dims trails; no HUD or marks counter on the record screen (provisional finds only); no countdown, ghost, tape or
  chevrons anywhere; the float line counts kept weeks; the Offer morphs into the Intro in one sheet; a quiet day
  replaces the Play row with one banner and hides the HUD; What's On is pushed from Me › PlayCard; ambient effects have
  their own toggle, overridden by reduced motion and battery saver. Where ux.md and product/schedule still differ,
  product and schedule win (README precedence); `roadSafety` is ≤ 25 mph (PD-7).
- **Consequence.** UI stages in `plan.md` build against ux.md §2–§3 and its §8 BUILD NOTES; pin bump and select ring
  are Layer-tier (PD-19). Open items left: Prue's House (OD-1), the `FxArt` glyph author (placeholders until art
  lands), the ambient default for battery-sensitive users (on, with battery saver forcing off).
- **Resolves:** ux.md §7.2 #2 (schedule conflicts) and #3 (S4).

## PD-19 Overlay tier after spike S4: geometry-exact art is Layer tier
- **Context.** Engine PF-4 assumed `MapOverlayScope.placedAt` keeps Compose overlay drawing "in sync with the pixels on
  screen". Spike S4 (`docs/play/spikes/fx.md`, 2026-10-20) failed its ≤ 1 dp pan / ≤ 2 dp pinch criterion: the overlay
  and the map present different camera frames under gestures (iOS fling 2–15 dp) and dropped frames (Android 4–17 dp);
  at rest and under programmatic `animateCamera` it holds (≤ 1 dp on iOS). The error is overlay/map frame sync, so the
  fallback in engine §5 (zoom from two anchors) does not fix it. S1–S3 and R8 passed; R7 documented.
- **Decision.** Anything that must coincide with map geometry renders in the **Layer tier** (GeoJSON + `globalState` /
  feature state): the kraken's grip stroke and ink casing, glints and halos on markers, pin bump and select ring.
  The **Overlay tier** is only for transient art that tolerates one frame of slip (tentacles in water, bursts, the frog
  hop, lantern lighting), still ≤ `overlayMaxMs`, shown at rest or during programmatic camera moves, and **hidden (or
  handed to its Layer LOD) while the camera moves by gesture**, fading back on `CameraMoveEnded`. Screen-anchored
  moments (`claim_pop` flight, `play_wake`) are unaffected. FxHost also re-writes `fx.*` global state after every
  `StyleLoaded`, caps `maximumFps` at 30 while Layer-tier animation runs, and gates its clock on elapsed frame time
  (spike API corrections, engine §0).
- **Consequence.** Engine PF-4 and §0 amended; effects keep their ids and showcase presets, with the tier split in
  `docs/play/plan.md` §2. ux.md §7.2 open 3 is answered (pin bump/select ring layer-only). S4 is re-run on a physical
  Android device in engine stage 9.
- **Resolves:** engine §5 S4; ux.md §7.2 #3.

## PD-20 L2-1/L2-2 UI review deviations: kraken ink casing color rule
- **Context.** The compose-ui review of L2-1/L2-2 (`reviews/2026-10-20-ui-l2-1-l2-2.md`, FIX-FIRST) recorded spec
  deviations: (1) `FxPalette` has no ink-casing role, and effects §3.1's casing "`playInk` at α .85" lands ΔE 14–17
  from the not-started trail color over the map land (the guard needs ΔE ≥ 20); (2) `seaGlassWash` is a stored field,
  not a derived value (harmless); (3) the clock gates on elapsed frame time, which engine PF-2's text did not say;
  (4) Battery Saver / Low Power was not wired. (4) is fixed in the review fix-up (FxHost stops the clock and draws
  final frames under power save, `FxClockPolicy.mode(powerSave)`); (3) is amended in engine PF-2.
- **Decision.** (1) The kraken casing color keeps **ΔE ≥ 20 from not-started over map land, in both themes**. Rule:
  casing = `play.ink` blended **35 % toward `secondary`** (Sound teal), opaque; the final hex per theme is measured
  and pinned by L2-4's guard test (alongside `FxPaletteGuardTest`), which adds the `FxPalette` ink-casing role.
  (2) stays as is. (3) PF-2 amended (PD-19, spike S1 correction #7).
- **Consequence.** effects §3.1's "α .85" casing is superseded by this rule; the kraken stage (L2-4) may not ship the
  casing until the guard passes in light and dark.

## Status

| doc | status |
|---|---|
| `product.md` | ACCEPTED (round 1 findings resolved) |
| `schedule.md` | ACCEPTED (round 1 findings resolved) |
| `engine.md` | ACCEPTED (round 1 findings resolved) |
| `effects.md` | ACCEPTED (round 1 findings resolved) |
| `ai.md` | ACCEPTED (round 1 findings resolved) |
| `ux.md` | ACCEPTED 2026-10-20 after the conformance pass (PD-18) |
| `plan.md` | written 2026-10-20 (PD-17); PD-19, PD-20 recorded |
