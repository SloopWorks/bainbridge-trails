# Round-1 adversarial review of the Play specs (2026-10-20)

Reviewer: `adversarial-reviewer` (read-only). Docs: brief.md, product.md, effects.md, engine.md, schedule.md, ai.md.

VERDICT: **NEEDS-RESPEC**

The four docs describe four different games. They disagree on treasure cadence, kraken timing, eggs, races, night
rules, the points schema and the server API, and several sections break the brief's non-goals. Fix: one
reconciliation pass. product.md owns rules and safety, schedule.md owns time and determinism, engine.md owns
rendering, and effects.md conforms to all three.

## P0

1. **Night and kid safety: three docs give three rules.** product PP-7/T-1: finds only civil dawn → civil dusk; creatures
   sunrise+30 → sunset−30; coyote never at dusk. schedule §3.4 r1–2: playHours 06:00–21:00 and `afterSunsetOk:
   pool:winslow` (Winslow road items live until 21:00 while December sunset ≈ 16:18); §4.7 `grand-forest-dusk`
   sunset→dusk, `chorus-frogs` to dusk, October theme "Grand Forest after dark". effects §3.5 owl "at dusk and dark",
   coyote dawn/dusk; §3.6 Prue's window glows after sunset; §3.9 lit lanterns "best night feature".
   **Fix:** the schedule §4.3 validator rejects any item with `resolve.by≠none` whose window is not inside
   [civil dawn, civil dusk]; creatures inside [sunrise+30, sunset−30]. Delete `afterSunsetOk`, the dusk items and the
   "after dark" theme. Effects: no dusk or dark triggers.
2. **Timed races on roads.** effects §3.11 3-2-1 countdown, ghost pace, "Finished in {mm:ss}", personal best; schedule
   §7.3 ranked standings with displayName and scoreKm, copy "Weekend race"/"You raced"; ai.md AI-6 race write-ups
   naming people. Breaks product PP-8, §4.5, PF9-4/5, OD-8; no shoulder data → timed walking on roads is a kid-safety
   problem. **Fix:** delete countdown, ghost, finish time. A challenge shows window + progress and finishes with a
   stamp. Standings return `{band,count}` only. Rename `race`→`challenge` in ItemType, tables, routes, copy. Drop AI-6.
3. **Memorial quiet zone misplaced and breached.** schedule §4.7 `respectZones` centre 47.6098,−122.5076 is 674 m from
   `trail-bainbridge-island-japanese-american-memorial-trail` (span 47.6146–47.6157, −122.5103…−122.5097); the 120 m
   zone covers nothing. `pool:shore-trails` and `pool:harbor-trails` contain `trail-pritchard-trail` (kraken, Ferry
   Friday, heron egg, Fourth flag at Pritchard Park = memorial + Superfund). Cemeteries missing. product pays memorial
   marks (W-DONE +100 on the memorial trail; Pritchard Park W-PARK1 +50 / W-PARKC +150; celebration "+100 marks"),
   contradicting R-1 and §4.6.3. **Fix:** quiet zones from dataset geometry (memorial trail buffer 200 m, cemetery trail
   100 m); validator rejects any pool member touching one; quiet trails/parks/squares excluded from every W-* mark;
   pure coverage unchanged.
4. **Unsafe roads.** effects §3.4 Frog Rock at Phelps × Madison vertex (47.69591,−122.52353), trigger "from the
   shoulder": both roads are 35 mph arterials (`road-phelps-road-northeast-1`, `road-north-madison-avenue-northeast-1`);
   product snaps to `road-northeast-spargur-loop-road` (25 mph). Speed limit differs: product ≤30, schedule ≤25.
   Product items failing the schedule validator: K1 `country-club`, `fort-ward-hill` (30); K7 `manzanita` (30);
   `lore-fay` via `sunrise-drive-northeast-1` (30); pool `fourth-parade` `madison-avenue-north-2` (30). Product's
   "406 of 430" counts 8 counted roads with `ownership:"private"` and 3 `"unknown"`; its own rule allows ≤395.
   **Fix:** one `roadSafety` rule: category residential/collector/arterial; speed ≤25; `public:true` and `ownership`
   public_*; not `state_route`. Recompute kraken coasts; drop hard-coded counts.
5. **High-tide kraken.** schedule `kraken-thursday` anchored to the highest daytime HIGH tide; `king-tide-kraken` fires
   at ≥3.9 m on shore roads. product §6/T-2 allows shore content only near LOW water. **Fix:** delete king-tide-kraken;
   kraken window clock-based (Thu 06:00 → Sun 20:00, daylight) and suppressed above a set tide height.
6. **Two incompatible server economies.** product §3.6.3/§3.10: per-walk `play` field on `PUT /v1/coverage`, server
   recomputes coverage-derived marks, `PUT /v1/play/week`, `/v1/play/board`, `PUT /v1/play/board-consent`.
   schedule §7: separate `PUT /v1/play/finds` trusting client `points`, `play_points` sums finds only, no board, race
   tables, `race` field on coverage. Schema caps `points` 0..100 but product rewards 150/250/1,000. Caps/grace/clock:
   product found marks 400/day, 2,800/week, late claims week+7 d, clock ±1 week; schedule `maxPointsPerDay` 250,
   `lateSubmitDays` 14, +1 day. **Fix:** adopt product's model. Claims ride the coverage upload, the server derives
   marks from the content table and never trusts client marks. Rewrite schedule §4.3/§7; one caps table.
7. **Content definitions contradict (AC3/AC5 untraceable).** Treasure: product weekly set of 12/20 in 4 tiers, Mon
   05:00, 20/25 m; schedule one daily chest (10 pts, 40 m, sunrise → sunset+30) + Sunday map fragments; effects sea
   glass only, tap within range finds it. Eggs: product permanent/global/hidden/15 m/once ever; schedule personal
   salt/weekly reroll/6–28 d cooldowns/35–40 m; effects "time/sequence/visited twice". Riddles: product 2/week +
   permanent, solved from home, claimed by walking; schedule 1/week "solve by walking". Kraken: product rotates 7/5
   coasts, 2–6 stretches, Thu→Sun, repelled at 60 %; schedule Thursday only, 1 road from 9-road pool, minM 300, 25
   pts; effects ≤3 concurrent, ≤150 m reach (product 60 m). Frog: product fixed landmark; schedule "moves" weekly.
   Ferry: product horn at every departure (≤2/day); schedule Ferry Friday (≤3 arrivals). **Fix:** product §4 is the
   authority. Rewrite schedule §3.2 and §4.7 to encode it exactly. Effects cards stop defining triggers.

## P1

8. **Determinism.** product §6 seeds with `hash(packVersion,weekId,goal)` + a server per-square aggregate: a pack bump
   mid-week reshuffles treasures; offline phones disagree; index ids like `tr-2026W44-07` change meaning.
   **Fix:** schedule §2.2 per-item seeds; bake aggregates into the pack at publish; ids are occurrence keys.
9. **Config version skew.** Server validates against its current config; devices may hold older configs; editing
   `when`/`where` in place makes in-flight claims fail `bad_occurrence`. **Fix:** published item ids immutable; a change
   = new id + `expires` on the old; payload carries `configVersion`; Worker keeps the last N configs.
10. **Claims without walking, clock rollback.** `no_walk` only tests `last_walk_upload_at` ≥ start of occurrence day:
    any walk unlocks every occurrence that day; client sends `points`; 14-day late window + clock rollback replays two
    weeks of treasures (~3,500 pts under 250/day). effects §3.7 "taps it while within range" contradicts "tapping never
    claims". **Fix:** each claim references a `walkId` in the same upload; line/stretch items require their anchor
    segment ids to carry bits for this user; server computes marks; occurrence day ≥ upload day − 2; remove tap-finds.
11. **Sync payload shape ≠ code.** `SyncRepository` sends one `CoverageUpload(version,TICK_M,delta,walks)` batching up
    to 200 walks across weeks; product's `play:{weekId,…}` is a single object; a 422 drops the batch; capped walks
    dropped uncredited lose claims. **Fix:** `play:[{walkId, claims:[{id,kind,weekId}], dayKey, hints}]` tied to
    `walks[]`; claims of capped walks stay queued.
12. **Claim pass has no Finalizer input.** `Finalizer.finalize` keeps `gate.fixes` internal; mock/accuracy/speed
    filtering is in coverage `Fixes.kt` (`coverFixes`), not `Gating`. **Fix:** `onGated(sessionId, keptFixes)` hook
    after the Fixes filter inside `finalize`, idempotent per (sessionId, contentId).
13. **Record map not wired.** `RecordScreen` has its own `TrailMap`; engine PF-8 allows only MapTab/TrailMap/AppShell
    and turns ambient off while recording; product needs find moments on the record map and screen-off sensing.
    **Fix:** RecordScreen is a gated call site; sensing runs in a graph-level observer of recording fixes.
14. **Tap precedence.** product PF3-5 opens the pure trail preview first; engine §2.3 queries `fx-treasure-icon`
    first. **Fix:** product wins; Play takes the tap only when there is no `TappableTrailLayerIds` hit.
15. **engine vs effects rendering.** overlay heroes: engine kraken RUN loops vs effects ≤3 s then layers; reduced
    motion: `reducedAtMs` pose vs final frame; treasure icon: `Anchor.Top` vs P4 under labels; arbitration:
    HERO/NOTICE/AMBIENT 8 s vs H0–U 1.2 s; idle 20 s vs 20/60 s; recording: ambient off vs layers ≤2 Hz; layer
    animation: `globalState` 30 Hz vs `setPaintProperty` 6–10 Hz; projection: `placedAt` vs camera snapshot + "swim";
    StatusLayers: none vs `casingFx` slot; budget device Pixel 6a vs 4a. effects §1.3 "labels yield to markers" is
    false (symbols place top-down). **Fix:** engine.md normative; rewrite effects §1.6/§2/§4/§5; add a casing band to
    engine if wanted.
16. **Vocabulary/honesty.** effects/schedule/ai say "points", "ledger pill"; `points_pop` fires mid-walk while PP-3
    says marks are provisional until Finish. **Fix:** glossary; announce with `play_find_a11y`; pop only on claim.
17. **Daily streak.** effects §3.13 `streak_float_line` contradicts PP-12. Cut or key to kept weeks.
18. **Weather/third parties.** effects §3.15 rain feed, weather setting; ai.md N-1 NWS cron. Contradicts PP-10 / S-4. Cut.
19. **Beach obstacle.** effects §3.10 `tide_cut` over a beach; §3.1 kraken on "beach access" trails. Contradicts S-3. Cut.
20. **Privacy claim false.** "not where or when": a found fixed-place id + weekId tells the server a user was within
    20 m of a known point that week. **Fix:** say so in copy, update the privacy policy (schedule D-5); store weekId,
    never `day`.
21. **W-COV can go down.** `floor(coveredM×10,000/island_total_shippable_m)`; a dataset update growing the total
    lowers marks. **Fix:** fixed rate per goal, or credit max(previous, new).
22. **Acceptance criteria not owned.** AC7 showcase for every effect (engine builds only two); AC8 e2e walkthrough
    owned by no doc (needs a play-clock override both platforms + emu-route); AC9 only in engine; PF0-1 pixel snapshot
    tests but `verify.ui_snapshot: none`. **Fix:** `docs/play/plan.md` with AC→section matrix, e2e script, effect
    inventory; replace PF0-1 with emulator screenshot diffs + a test that no Play node is composed.
23. **Mochi Tsuki.** schedule turns BIJAC's event into a "Mochi Moon chest". **Fix:** no gameplay on Japanese American
    community events without BIJAC consent (D-10).
24. **Turning Play off.** §5.5 "no Play calls" when off, yet board row "hidden after next sync". **Fix:** one carve-out
    `PUT board-consent {visible:false}` when Play is turned off.
25. **Weekly coverage/concurrency.** Oct 31 second kraken overlaps the weekly one under `heroMaxConcurrent 1`;
    Thursday-only kraken / Ferry Friday break "every weekly item has a weekday and a weekend day". Resolved by 7.

## P2

26. Week boundary "Sun 23:59" leaves a gap → half-open [Mon 00:00, next Mon 00:00) island time; week id = Monday date.
27. Two grids (0.01°×0.015° vs 0.009°×0.0133° at 47.55/−122.62) → one.
28. "Season": board quarters Jan–Mar vs schedule seasons from Dec 1; "per season" creature repeats ambiguous.
29. engine `seed=id.hashCode()` vs schedule "NEVER String.hashCode" → fnv1a64.
30. Name collisions: S-1… numbering in two docs; product cites `mapfx.md`, `scheduler.md`, `ux.md` (nonexistent);
    `/v1/play/pack` vs `/v1/play/content`; kill switch `status:paused` vs `kill{}`.
31. F-INK variable reward not derivable server-side (no metres sent) → buckets by % walked.
32. Facts to check: "biggest party since the 1950 Strawberry Festival" vs 1948; chum salmon at Sakai; wrens, squirrels,
    chorus frogs, bats, banana slugs unsourced; Frog Rock birthday 3rd Saturday vs Jun 6–7; Lynwood "first sound-film
    cinema"; Fay bell provenance; ferry terminal coordinates differ across docs.
33. Eggs need 15 m accuracy; canopy (Fairy Dell) often won't give it.
34. `0004_cleanup.sql.pending` exists; fix migration order before 0005.
35. Reinstall loses the local logbook while the server total stays → restore from the server summary.

NOT FOUND (clean): `speed_mph`/`category`/`ownership`/`public` exist, `shoulder` does not; `last_walk_upload_at`
exists per (user, dataset); cron `17 9 * * *` is the only trigger; D1 write estimates fit the free tier; NOAA
9445882/9447130 consistent; economy arithmetic checks out; engine library facts plausible, spikes scoped.

## v1 cut line

**Ships in v1:** opt-in from Settings + one-time offer, off = pure app · marks for walk-derived events (W-COV,
W-DONE, W-SQUARE (fog), W-DAY, W-WEEK, starting balance) · Logbook and ranks · weekly treasures (fixed tiers) ·
permanent seed eggs (global, simple stamp) · weekly kraken (one coast, clock window, roads ≤25 mph) · permanent
riddles (place and feature answers) · lore cards with fixed anchors (Frog Rock, ferry, history, remembrance as quiet)
· ferry sprite on the timetable (visual only) · two challenges `ch-sweep`, `ch-krakenweek` · Mar 30 quiet day ·
claims on the coverage upload · effects: treasure_glint/found, kraken_attack/recede/lurk, riddle_marker/light,
frog_rock, ferry_crossing (layers), exploration-fog layer, claim pop, play_wake · engine stages 0–7 and 9 · the e2e
flow.

**Later:** Play board and seasons · creatures and tracks (daylight-only, sourced) · Prue's House (OD-1) · brambles ·
F-HORN and `ch-crossing` · tide-gated shore items (v1 needs no tide table) · personal-salt eggs · treasure-map
fragments · event windows other than Mar 30 · shaders (stage 8) · Lottie · notifications · all of ai.md except
offline authoring.

**Cut entirely:** race_track (timed courses) · streak_float_line · weather and rain · morning fog · tide_cut ·
fallen_tree and fog_bank · king-tide kraken · named race standings and write-ups · Mochi Tsuki gameplay.

## Cross-doc glossary

marks (never points/pts) · Logbook (never ledger) · Play chip (never ledger pill) · challenge (never race) ·
content item = entry in the content pack `play-content.json`, served at `GET /v1/play/content` · occurrence key
`<itemId>@<Monday-or-start date>` · week id = that week's Monday date · island time = America/Los_Angeles ·
daylight = civil dawn → civil dusk · creature hours = sunrise+30 → sunset−30 · sensed → claimed; armchair → stamp ·
quiet zone (never respect zone) · kraken attack / stretch / repel (never wrap, drive off, clear) · fog = exploration
fog only · chart square = the single grid · scope island | personal · content types: treasure, egg, obstacle, riddle,
challenge, lore, creature, ambient · effect priority HERO/NOTICE/AMBIENT, bands as engine PF-3; effect ids snake_case,
referenced by the item's `fx` field · seed hashes fnv1a64.
