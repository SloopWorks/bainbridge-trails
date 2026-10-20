# Roads mode: tech-lead decisions

Date: 2026-10-20. Branch `roads/mode`, built on PR #8 (`roads/app-prototype`) and `main`.
Owner goal: a roads mode that tracks completion of all public Bainbridge Island roads. It uses the core
engine and components, with UX/UI adapted for roads. It must build end to end and install on the owner's
Pixel 10 Pro.

**Precedence:** this file > `docs/roads/ux.md` > `docs/roads/product.md` > `docs/roads/plan.md`. The trail
app's `docs/mvp/*` still governs everything shared that this file doesn't override.

## Review verdict (before this work)
The UI/UX is **not ready** for roads:
- Every string says "trail", and the island denominator is hard-coded in copy.
- Road detail shows trail fields ("Elevation gain —") and drops surface, speed, maintainer and climb. Road
  ends are not wired.
- There is no separate build: only a debug switch.
- Two data correctness blockers:
  - Long segments paint unwalked road as progress.
  - Trail walks credit roads with 9.7 % of the walked distance.
- Leaderboard and aggregates blend the two goals.
- Retention risk: a 0.5 mi walk moves the island by 0.1 %.

## Decisions
- **RD-1 Packaging:** Android product flavours on dimension `goal`:
  - `trails` keeps the current app id `com.bainbridgetrails.app`.
  - `roads` uses `com.bainbridgetrails.roads`; debug adds the `.dev` suffix.
  - The flavour fixes the `DatasetProfile`. The debug drawer switch stays available in debug builds.
  - iOS roads target is deferred and documented; the Android Pixel build is the target.
- **RD-2 Data first, both blockers fixed in the pipeline before any roads build ships:**
  - (a) Split counted road segments to a target of about 120 m (max 150 m) after junction splitting. Issue new
    `rseg-*` ids through the registry. Nothing has shipped, so no remap is owed to users, but the registry
    stays add-only.
  - (b) Append the trail dataset's shippable segments to the roads `coverage_segments.json` as matching-only
    competitors (`included: false`, `road_ids: []`).
  - Verify both with `coverage-eval cross` and the segment-length stats.
- **RD-3 On foot only for this version** (walk or run). The copy says "Walk every road". Biking is P2: it
  conflicts with the speed and activity anti-cheat and needs its own design.
- **RD-4 Copy and goal numbers are data-driven:**
  - Add a `GoalVocabulary` keyed by `DatasetProfile` (trail/road, plural forms, tab names, goal line).
  - Every user-facing number (island total, counts) is read from the dataset; none is hard-coded in copy.
- **RD-5 Road-shaped screens:**
  - Road detail shows length, climb, surface, speed limit, maintainer/ownership, and road ends where relevant.
    It shows no trailheads and no park.
  - The list groups or filters by category.
  - Exact fields and layout come from product.md and ux.md.
- **RD-6 Retention:** progress has to be felt on a goal 2.5× the size of the trails goal. Product designer
  defines intermediate milestones, for example roads completed as the primary stat, plus neighbourhood or
  area or mileage milestones. Build only what the engine already supports, or what cheap derived math can
  provide.
- **RD-7 Backend:**
  - Coverage rows are already safe because the id prefixes differ.
  - Aggregates, the leaderboard and community stats get scoped per dataset.
  - The roads build sends `dataset=roads`.
  - Deploy to the temporary Worker.
- **RD-8 Distribution:**
  - The roads debug build is installed on the Pixel with `adb`.
  - Shipyard needs a new package registration (`com.bainbridgetrails.roads.dev`), which is an admin action,
    so it's deferred to the owner.
