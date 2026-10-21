# Work item: "Play" — opt-in gamification of Bainbridge Trails (trails + roads goals)

Date: 2026-10-20 · Stage: intake → design · Owner request (verbatim, the authority for scope):

> complete gamification of bainbridge island trails and road variant. This will be an opt in experience, keeping the
> 'pure' mapping completion available. gamification will be a big initiative including: point system, obstacles,
> treasures, easter eggs, puzzles & riddles, races, bainbridge island specific features such as a kraken in the water
> attacking roads near water, frog rock, ferries, prues house, grand forest creatures, and other local culture, myths,
> and history. use top notch rendering and visual effects where possible. showcase style, UX, craft for effects and key
> points. timing and scheduling needs proper design and thought to think how and when these should happen over days
> and months and how to manage. [...] brainstorm use of AI for novel and creative effects. one large work is creating a
> rendering pattern, practice, or engine that can integrate animations other effects into the map. complete e2e

## Problem

The app today is a pure completion tracker: walk every trail (114 named, `trails` goal) or every counted road
(664, `roads` goal), status per trail on a MapLibre map, celebration on completion, leaderboard. It is honest and calm,
and for Dana (completionist) and Marcus (weekend explorer, kids) it has no *play*: nothing surprising on the map,
nothing to find, nothing that changes with the day or the season, no reason to pick *this* trail *today*.

## Surface

A new, opt-in **Play** layer over both goals. Off by default; the "pure" experience must stay byte-for-byte the same
when Play is off (map, lists, Me, celebration, leaderboard). When on, Play adds: points and a ledger, treasures and
easter eggs placed on the island, obstacles (e.g. the kraken attacking shore roads), puzzles and riddles tied to places,
races (time- or window-boxed challenges, solo and against the community), and Bainbridge lore characters/events
(kraken, Frog Rock, the ferries, "Prue's house" (owner's words; confirm what/where), Grand Forest creatures, local
history and myths) — scheduled over days and months so the island feels alive.

## Non-goals

- No change to coverage math, completion thresholds, leaderboard ranking, or the sync contract for the pure mode.
- No paid tiers, no ads, no new third-party services with a bill (hosting stays $0: Cloudflare free tier).
- No raw GPS leaves the phone (privacy stance unchanged). Points/finds sync as small summaries only.
- No gambling mechanics (no loot boxes, no paid currency). Kids may play: nothing creepy, nothing that lures onto
  private land, beaches at high tide, or roads without shoulders.
- No real-world physical caches (nothing to hide/retrieve physically; everything is on-map/virtual).

## Constraints (from the repo)

- Kotlin Multiplatform + Compose Multiplatform 1.12, Kotlin 2.4, maplibre-compose 0.20 (MapLibre Native FFI, Compose
  draws the map; Android OpenGL ES runtime, iOS Metal). Map content = `@MaplibreComposable` layers (LineLayer,
  CircleLayer, SymbolLayer, FillLayer, GeoJSON sources, expressions) + a Compose `overlay` slot on top of the map
  with access to `MapState` (camera, viewport, screen↔geo projection).
- Existing motion vocabulary: `ui/theme/BtMotion.kt` (M3 easings, durations, `reduced` motion), Canvas-driven timeline
  animations (CelebrationScreen / IslandCelebration: one `Animatable` clock, `phase(t, from, to)` helpers, PathMeasure
  draws), water-line motif ("Chart room" design direction, docs/mvp/ux.md §0). Fraunces for headlines.
- Theme tokens: `BtTheme.colors` (status roles, mapChrome, waterLine…), both light and dark; status hues are reserved for
  coverage — Play must not reuse them for anything else.
- Goal vocabulary: `GoalVocabulary` / `LocalGoal` (trails vs roads words). Play copy must route through it.
- Process-wide object graph `AppGraph` (no DI), repositories with StateFlow, settings in a JSON file
  (`SettingsRepository`), walk pipeline: `RecordingController` → `Finalizer` → coverage store → `ProgressRepository`.
- Backend: Cloudflare Worker + D1 (`backend/`), routes in `backend/src/index.ts`, tests in `backend/test` (vitest).
- Data: `data/dist/trails.json` (114 trails, segment ids, trailheads), `data/roads/dist/roads.json`,
  `coverage_segments.json` (segment geometry), PMTiles basemap/trails/contours/hillshade, coastline (`scripts/build-coastline.sh`).
- Verification: `app/AGENTS.md` (full verify set), emulator route replay `scripts/emu-route.sh`, iOS launch args
  `-btScreen …`. CI has no map data.
- Reduced motion and screen readers are first-class (every effect needs a reduced variant and an announcement).

## Acceptance criteria (the whole initiative)

1. Play is opt-in from Settings (and offered once, gently, after the first finished walk); off = unchanged pure app.
2. A points system with a visible ledger and clear rules (what earns, how much, why), per goal, synced as summaries.
3. Treasures, easter eggs, obstacles, puzzles/riddles and races exist as content types with on-map presence, discovery,
   resolution, and reward, each with reduced-motion and a11y variants.
4. Bainbridge lore content: kraken (shore roads), Frog Rock, ferries, Prue's house, Grand Forest creatures, plus a
   catalogue of further local culture/history/myth hooks, each placed on real coordinates.
5. A scheduling design and implementation: what appears when (time of day, day, week, month, season, tides/ferry
   times), deterministic and fair, managed via content config the owner can edit without an app release.
6. A map effects engine (`MapFx`): a documented pattern + implementation that places animated effects on the map in geo
   space, driven by one clock, with layering, lifecycle, budget, reduced motion, and tests.
7. Showcase screens (debug) for every effect so they can be reviewed and screenshotted on both platforms.
8. E2E: emulator/simulator walkthrough of opt-in → find a treasure → kraken encounter → solve a riddle → race →
   ledger, with screenshots in `docs/play/screens/`, plus unit tests for rules/scheduling and backend tests.
9. Mobile-expert review (Compose correctness, perf, battery, a11y) passed before done.

## Gates

`gates.design_before_build: true` — specs under `docs/play/` must be ACCEPTed before code. Reviews: adversarial-reviewer
on the spec and on the branch, compose-ui-reviewer + a mobile expert on UI, kmp-verifier before claiming green.

## Personas (docs/mvp/product.md §1)

Dana (47, completionist, dog, mornings) · Marcus (34, weekends, kids, light competition, privacy-minded).
