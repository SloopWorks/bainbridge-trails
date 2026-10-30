# Play decisions (spec review round 1)

## Map clarity override — 2026-10-30

Owner request: show only Bainbridge Island and remove the translucent layer over unwalked areas. Exploration
fog no longer spawns or renders on live Map or Record, in either edition. This supersedes the live fog visual
in PD-14 and effects §3.18; its debug showcase remains. Chart-square coverage and W-SQUARE rewards are
unchanged. Other Play figures and effects remain. The shared base styles hide nearby land with opaque water
and filter labels to the island. Design/verification: `docs/superpowers/plans/2026-10-30-island-map-cleanup.md`.

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
- **Limit:** what the server cannot verify about a claim is recorded in PD-21.

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

## PD-21 Honest-client limit of claims (privacy review of L3-1/L3-2)
- **Context.** `reviews/2026-10-20-privacy-l3-1-l3-2.md` P2-3/P2-4: a claim needed no walking in its upload (a
  `durationS: 0` walk credited a W-DAY and unlocked point items), the kraken `no_bits` check reads cumulative coverage
  (a stretch walked once passes every week), and point items (treasures, eggs, lore, place riddles) carry no position
  by design (PD-3: the server never sees fixes).
- **Decision.** The server checks what it can and states the rest: a claim-bearing walk must be credited by the same
  upload **and** have `durationS > 0` (else `not_this_walk`); a W-DAY `dayKey` needs `durationS ≥ 600`; line and
  stretch items need the user's coverage bits. **Beyond walk binding + duration, point items are not
  server-verifiable**: an edited client can claim any open point occurrence once per walk it uploads. The caps bound
  the damage: 2,800 found marks per week id from the CURRENT pack whatever version the client names, "always" items
  booked to the server's current week, ≤ 50 claims per upload, 30 uploads/hour, idempotent per occurrence.
- **Consequence.** Marks are a personal logbook, not a contest: the Play board (any ranking or comparison of marks
  between people) stays v2 until a stronger check exists (for example, server-side proximity on a coarse,
  non-retained fix, which would need its own privacy decision).
- **Resolves:** privacy review P2-4 (record), with P2-3/P2-5/P2-6 fixed in `backend/src/play/claims.ts`.

## PD-22 On-device Play state: no backup, wiped with the phone (privacy review of the client sync/state)
- **Context.** `reviews/2026-10-21-privacy-client.md` (FIX-FIRST): P0-1 "Also delete … on this phone" cleared the
  claim queue only, so the Logbook (`play/<goal>-state.json`) survived a wipe; P1-2 that file sat in the backed-up
  app dir (Android `filesDir`, iOS Application Support/app) and held every `LogEntry.walkId` = the local session id
  (`s<epochMs>-hex`, a walk's exact start time), island dates with memorial/cemetery stamps, and restored `occ` keys;
  P2-3 occurrence keys carry the occurrence day/window; P2-4 no body cap on pack/tide fetches, and walk-less claim
  queue elements were kept forever.
- **Decision.** (1) Play user state lives under the no-backup dir: `<privateNoBackupDir>/play/<goal>-state.json`;
  an existing file in the old dir moves there once on first load. The pack and tide caches stay in the app dir's
  `play/`, which Android backup now excludes as well (`files/play/`, both rule files). (2) On disk a Logbook walk is an
  opaque random id (`w-<hex>`); the session ↔ walk map sits beside the state in the no-backup dir and holds only walks
  the Logbook still names, so in memory the results sheet and Logbook links keep using the session id. (3) "Also
  delete … on this phone" clears Play for **both goals** whether Play is on, off or never built in this process:
  state (in memory and on disk, including any pre-move file), the walk-id map, both dataset profiles' claim queues,
  and this process's claim results. (4) Occurrence keys (claim ids, `found`, restored `occ`) encode the occurrence's
  day or window and the week id by contract; "no days" in product § 3.10 means no day beyond those keys and never a
  time of day. Server retention of these place-and-week keys is **OD-21** (open, owner decides). (5) A content pack
  or tide year body over 2 MB is refused unread (pack: `rejected`, last good kept; tide year: not used, not cached).
  The claim queue holds ≤ 500 elements, and an element whose walk has no walk id 30 days after it was queued settles
  `local_only` (`expired`).
- **Consequence.** A restore from device/cloud backup never brings Play state back; Play's Logbook on a new device
  comes only from the account (§ 3.6.5 restore). A Logbook whose map is lost shows its walks without the walk link.
  Posture for the hub: on-device Logbook retention (until wipe or uninstall) incl. memorial stamps; backup exclusion;
  occurrence-key wording qualified by OD-21.
- **Resolves:** privacy review 2026-10-21 P0-1, P1-2, P2-3 (wording), P2-4. Proof: `PlayWipeTest`,
  `PlayStatePrivacyTest`, `PlayBackupRulesTest`, `PlayContentRepositoryTest` (oversize), `PlayClaimQueueTest`
  (retention).

## PD-23 The daily found cap travels as `capped: true` (branch review round 1 P1-4)
- **Context.** `reviews/2026-10-21-branch-round1.md` P1-4: the daily found cap (400 per island day, product § 3.5,
  PF5-7) was client-only: the phone logged an over-cap find at 0 marks, the server (which never learns days) paid it
  in full, so the Logbook total drifted below `/v1/play/me`. Retried uploads after a lost response showed the same
  drift for weekly-capped finds (P1-9).
- **Decision.** (a) of the review: a claim the client's daily cap pays 0 is sent with `"capped": true` (omitted
  otherwise, so a body without capped finds is unchanged); the server books the occurrence key with 0 marks and lists
  it under `capped`. A repeat of a key whose stored marks are 0 is listed under `capped` as well; the client treats
  every `capped` id as accepted with 0 marks. The server still never learns a day: the flag is the client's verdict.
- **Consequence.** An edited client can only lower its own marks with the flag. The weekly cap stays server-only.
  Proof: `PlayClaimQueueTest` (`daily-capped finds are sent capped`, `repeat listed under capped …`).

## PD-24 Play-pending marker, in-flight guard, and the claim-limit limitation (branch review round 1 P1-5, P1-6, P2-13)
- **Context.** P1-5: a process death between the coverage merge and the end of the Finalizer's Play hook lost the
  walk's claims (launch skips finalized sessions). P1-6: a sync in that window uploaded the walk without its Play
  element, which later settled `local_only over_cap`; at launch `playOrNull` could still be null while stopped walks
  were finalized. P2-13: an element left out of an upload that carried its walk (≤ 50 claims per upload) settled as
  `over_cap`.
- **Decision.** (1) When a Play hook will run, the Finalizer writes the session id to `play-pending.json` in the
  tracking dir **before** the merge and removes it once the hook returned or failed (a failure is not retried; a
  death is). On launch `RecordingController.onLaunch` first calls `Finalizer.resumePlay()`: every finalized, still
  marked session gets the hook again from its walk record and raw track (claims are idempotent through `found`; the
  challenge walk list is keyed by walk). Play off at launch keeps the markers for a later launch. (2) While Play is on,
  sync leaves marked sessions out of the walks it queues (`playInFlight`), so a walk goes up with its element; its bits
  may go earlier (the server's claim checks read cumulative coverage). (3) `RecordingGraph` awaits the Play gate's
  first applied settings (≤ 10 s) before the launch-time finalization. (4) An element that does not fit under the
  50-claim limit of an upload carrying its walk settles `local_only` with `too_many_claims`: the walk is credited, so
  no later upload can carry it. **Known limitation**: only a backlog of more than 50 claims across the walks of one
  upload (a long offline period) hits it; those finds stay on the phone, marked.
- **Consequence.** The marker file holds a session id only while a hook is outstanding (normally milliseconds; until
  the next launch after a death). Proof: `FinalizerTest` (crash replay, markers), `RecordingControllerTest`
  (`onLaunch replays …`), `SyncRepositoryTest` (`a walk whose play hook has not finished …`),
  `PlayOffNothingCreatedTest` (`awaitSettled…`), `PlayClaimQueueTest` (`at most 50 claims per upload`).

## PD-25 A tap inside a Play marker's own figure is the marker's (e2e run 1 F5; refines the PD-12 tap order)
- **Context.** `screens/README.md` F5: with the pure trail query first and Play only on a miss (ux.md § 3.5.4,
  PF3-5), a pin drawn over a line could never be tapped: the kraken coast label, the Frog Rock riddle pin and the
  Grand Forest pins opened "Roads/Trails on this section" instead of their sheet.
- **Decision.** MapTab probes `fx-marker-hit` at the tap point first (a 2 dp rect, Play on only): a point inside a
  marker's own 48 dp figure rect (a pin's lifted figure, the kraken label at its water point, an island-zoom disc) is
  that marker's, whatever line runs underneath; several under the point go by the declutter priority. A tap whose
  point is outside every figure rect stays pure: the 44 dp trail query decides, and only its miss queries
  `fx-marker-hit` over the tap rect (a near-miss pin still gets the tap). Ink casings, stamps and other FX layers are
  never tap targets (unchanged).
- **Consequence.** A line under a pin is tappable everywhere except under the 48 dp figure itself (select it from the
  line beside the pin, or the list). Play off makes no Play query at all. Proof: `MapTapPriorityTest`
  (`trail hit wins outside the figure`, `pin wins inside its figure`, `inked stretch tap is a trail tap`).

## PD-26 Trails edition v1.1: six trails-only rules, two challenges, ledgers by value (plan review round 1)
- **Status.** ACCEPTED 2026-10-21. Sources: `trails/brief.md` § 2 (owner delegation; Q1–Q5 defaults stand),
  `trails/ux.md` (ACCEPTED, § 10 binding), the plan `../superpowers/plans/2026-10-21-play-trails-v1-1.md` § 1
  (D1–D12, rev 2 after `trails/reviews/2026-10-21-round1-plan.md`, FIX-FIRST applied). Precedence unchanged; the plan
  yields to the specs except for the deviations listed here.
- **Context.** The owner asked for Play on the trails goal with extra marks for climbing, mileage and parks. PD-15
  put W-PARK1, W-PARKC, W-ISLAND and the milestones in v2. The brief amends that cut line **for the trails goal
  only**; PD-2/PD-3 hold: every new mark is a fixed rule, server-derived from accepted coverage plus the sticky
  completed set, monotone, never from GPS or barometric altitude; quiet trails and Pritchard Park never earn.
- **Decision (rules, `goals: [trails]` only; roads byte-identical, guarded).**

| id | rule | marks | reachable today (dataset `2026-10-18+aad9d16e`) |
|---|---|---|---|
| W-CLIMB | Ladder over Σ `gain_m` of **completed** non-quiet trails (partial coverage adds nothing; trails sharing segments each count their own `gain_m`, brief Q4). Steps keyed in metres: **100, 250, 500, 750, 1,000, 1,250** (1,500 dropped, Q1: non-quiet total is 1,368.8 m). Shown in the user's unit (ft with mi, m with km). | +50 per step | 6 steps = 300 |
| W-STEEP "High ground" | Completing a non-quiet trail with `gain_m ≥ 40`; once per trail id. | +50 | **10 trails** (Q2: Blakley Cemetery Trail is quiet) = 500 |
| W-MILE (trails) | Distance ladder 1, 5, 10 … 60 mi on cumulative non-quiet covered metres (W-COV's `coveredM`), `Milestones` math (RP-8); paid steps keyed in **miles**. | +50 per step | 102,964 m = 63.98 mi → **13 steps** = 650 |
| W-PARK1 | First non-quiet coverage in a Play park (`trails.json` `park` non-null, ≠ Pritchard Park); once per park. | +50 | **19 parks** = 950 |
| W-PARKC | Every named non-quiet trail in a Play park complete; fires on the walk completing the last one; once per park. | +150 | 19 parks = 2,850 (7 single-trail parks) |
| W-ISLAND | Island complete (existing `islandReached`); once. | +1,000 | 1,000 |

  Two solo challenges (window-boxed, never timed, `goals: [trails]`, +100 each, existing caps bind; pack
  **2026.11.3**, D10): **`ch-climb`** metric **`climb`** = Σ `gain_m` of non-quiet trails newly completed inside
  Mon 00:00 → Mon 00:00 island time, default target 150 m, progress and target carried as integer **decimetres**
  (`round(gain_m × 10)`; 149.9 m = 1,499 < 1,500); **`ch-parkhop`** metric **`parks`** = distinct Play parks with
  ≥ 1 new non-quiet completion inside Sat 00:00 → Mon 00:00 (as `ch-sweep`), default target 3. Both windows are
  whole days (validator). Target (Q3, both sides, D5) = `min(default, remaining + progress)` after the walk/upload;
  remaining 0 → no card, no chip count, no What's On row, no claim.
- **Ledgers by value (D1, D2, D3).** Device: `PlayState.trailPaid: Set<String>` + `trailsStarted`; keys `climb:<m>`,
  `mile:<mi>`, `high:<trailId>`, `park1:<park>`, `parkc:<park>`, `island`, never re-derived; the key is also the
  Logbook `contentId`, so **paid** = `trailPaid` ∪ the `contentId`s of Logbook entries of the six kinds, owed =
  qualifying − paid. Server: additive `0007_play_trails.sql` — `play_trail_paid(user_id, dataset, key, marks)`,
  `play_totals.trail_marks` (no other table; see D12); inserts `ON CONFLICT DO NOTHING` before the totals upsert,
  allowed on the no-change upload path. `GET /v1/play/me?dataset=trails` gains the six kinds in `perKind` and
  `paid: string[]`; the roads body stays byte-identical. A dataset that drops a trail lowers nothing; one that adds a
  trail pays only keys not yet paid.
- **Start balance (D4; ux § 6 with one copy deviation).** `TrailStart` runs after `StartBalance` on every turn-on
  (trails only): owed keys → one `start_trails` entry ("Trail rewards so far" the first time; later catch-ups **what
  "Trail rewards caught up", why "Trail rewards owed from earlier walks"** instead of ux § 6's "Trail rewards while
  Play was off"), `island` as its own entry, nothing owed → nothing. A walk finalized during the turn-on restore
  pays every key once (P1-8). The PlayWake number = start_balance + start_trails + island (P2-5).
- **Deviations recorded.**
  - **D6** AC 2's "1,609 m → 1 mi" is one statute mile: **1,609.344 m = `mile:1`**, 1,609.0 m pays nothing; climb
    compares integer decimetres (99.9 m = 999 < 1,000). W-ISLAND = island fraction ≥ 1 − 1e-9.
  - **Quiet set (P1-2 ruling).** The new rules take quiet segments from the derived JSON **`quietSegments` on both
    sides** (server `trails.ts` reads it, as the Kotlin v1 rules already do). v1/roads `walkDerivedMarks` stays untouched
    for AC 1; the v1 W-COV/W-SQUARE quiet-set drift is a follow-up (`docs/mvp/STATUS.md`), not fixed here.
  - **D7** Local Play state is **lost on downgrade** (refused, documented): a v1 build cannot decode a v1.1 state file
    (new Logbook kinds `climb`, `high_ground`, `start_trails`) → empty Play state on that phone, overwritten on its next
    write; the server total is intact and a signed-in v1.1 turn-on restores it. Shipyard moves forward; a rollback is
    the owner's call.
  - **D8** Upload contract unchanged (AC 7): no edit to `ApiDtos.kt` / `PlayWalkClaimsDto` / `PlayClaimDto`; ledger
    keys never leave the phone (DTO-name, byte and source-span tests).
  - **D11** Before the owner deploys the Worker: the client runs pack 2026.11.3, so a deployed v1 Worker answers
    `stale_config` for every claim naming it (→ `local_only`) while its `play` block still settles the walk rows; a
    pre-Play Worker leaves rows `pending`. After deploy + 0007 the first Play upload inserts every qualifying key and
    totals converge. Until then the Logbook total exceeds the server total.
  - **D12** `play_progress_keys` was planned as a deviation ("a park per challenge weekend") and **reversed** by the
    privacy-security-reviewer's early pass (`trails/reviews/2026-10-21-privacy-l3-1.md`, ESCALATE: a row is place + a
    sub-week window, finer than the week the policy promises; the 7 single-trail parks pin a trail). Ruling 2026-10-21:
    the table is dropped from 0007; `ch-parkhop` progress = distinct Play parks among the trails newly completed in
    **one upload** (one occurrence per P1-1), counted into `play_progress`. Nothing new is stored. Bounded overcount
    accepted: two different trails of one park completed in two separate uploads in one window count twice (worst
    case: an easier +100 solo challenge). The ledger `play_trail_paid` needs one privacy-policy clause (OD-19 draft
    in `docs/legal/privacy-policy.md`, owner sign-off before deploy).
  - **P1-1** One upload contributes to **at most one occurrence per challenge item**: the one active at `now`, else
    the latest still in grace. Consequence: a walk synced late, after the next occurrence opened, counts toward the
    new one on the server while the client counted it by its Logbook date; the two can differ until the next walk.
    Park-hop progress is mirrored into `play_progress` (+1 per new park key) so bands and `/challenges` = distinct
    parks (P1-9); `/challenges` returns the personal target when signed in, climb values in dm (P2-7).
  - **P2-6** `ch-climb` / `ch-parkhop` claim when `P ≥ target > 0` and the occurrence is unclaimed (no `prior <
    target` gate; `ch-sweep` unchanged).
  - **P2-3** The pack build fails when a park name or trail id present in the committed derived `trails.json`
    disappears; an alias map for renames is **v2**.
  - **Roads guard (D9).** `roads.json` embeds `"pack"`, so the guard is the sha256 of the file without the `"pack":`
    line (`4ea0afa8…`), and `agenda-roads.tsv` is compared without its header; roads start balance, walk marks,
    `/me` body and challenge list equal `main` (2aee859) by golden.
- **Economy (product § 3.2, § 3.7.3).** Trails whole goal ≈ 22,946 + 6,250 (300 + 500 + 650 + 950 + 2,850 + 1,000)
  ≈ **29,196**; roads stays ≈ 23,286. The asymmetry is accepted: ranks are per goal, there is no cross-goal
  comparison, the board stays v2 (PD-21); "Keeper of the Chart" (30,000) still needs Play content.
- **Owner items (not in this work item).** Worker deploy + `npm run migrate:remote` of 0007, after the OD-19
  privacy-paragraph sign-off (D11 holds until then). Once the Worker serves 2026.11.3, v1 clients reject the whole
  pack (`coerceInputValues = false`, last good kept); Shipyard-only today. Alias map v2 (P2-3). OD-21 retention
  governs `play_trail_paid` like `play_claims`.
- **Consequence.** Trails players with Play on earn the six rules and the two challenges from the first walk or the
  turn-on; nothing new leaves the phone; roads is unchanged. Proof: `TrailRewardsTest`, `TrailRewardsGoldenTest`,
  `TrailStartTest`, `LogKindCompatTest`, `TrailChallengeTest`, `UploadContractTest`, `RoadsGuardTest` (both modules),
  `backend/test/play-trails*.test.ts`, `play-roads-guard.test.ts`, the shared golden
  `data/play/golden/trails-rewards.json`.
- **Resolves:** brief Q1–Q5 (defaults); plan review round 1 P0-1, P1-1 … P1-10, P2-1 … P2-11 as applied in plan rev 2.

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
| `trails/brief.md`, `trails/ux.md`, `../superpowers/plans/2026-10-21-play-trails-v1-1.md` | v1.1 (trails only): brief intake, ux ACCEPTED, plan rev 2; PD-26 recorded 2026-10-21 |
