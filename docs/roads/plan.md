# Roads version — plan

Status: **data prepared, nothing in the app or the trail dataset touched.** Branch `roads/data-prep`
off `main`. The v1 MVP work on `v1/mvp` is untouched; no file outside `data/roads/`,
`pipeline/src/bitrails/roads/`, `docs/roads/`, `scripts/` and one backward-compatible parameter in
`pipeline/src/bitrails/registry.py` changed.

## The goal, with the number behind it

> Walk, hike or bike **every road** on Bainbridge Island.

| | km | miles |
|---|---|---|
| **Counted road network (the goal)** | **264.3** | **164.2** |
| of which geometry the app may ship (OSM/ODbL) | 261.5 | 162.5 |
| all road centerline mapped (incl. private, lanes, some driveways) | 371.0 | 230.5 |
| private roads — mapped, drawn, not counted | 94.5 | 58.7 |
| unknown ownership — held out of the goal until settled | 11.3 | 7.0 |
| trail dataset, for comparison (v1 app, shippable) | 105.4 | 65.5 |

Counted = `state_route`, `arterial`, `collector`, `residential` in public ownership
(state, city, county, parks, school). 777 named roads of which 442 are counted; 664 of those roads have shippable geometry and so reach
the app (430 counted). 2261 canonical segments.

Independent cross-check of the denominator: WSDOT's Local Agency Public Road inventory gives
152.3 mi of City-jurisdiction roads + ~8 mi of SR 305 + 1.1 mi of parks/school roads = **161.4 mi**,
against 164.2 mi from the built dataset (the difference is public segments OSM maps that WSDOT's
LRS does not, plus matching slack). Separately, OSM carriageway length on the island is 202.5 mi
*including* private roads, and the City's street layer totals ~207 mi including private.

So a roads version is **~2.5× the trail app's goal**, and nobody finishes it in a season — which is
the interesting part: it is a multi-year completionist map, not a summer checklist.

## What is already built

`data/roads/` is a second dataset, produced by `scripts/bitroads` (fetch → build → dist) and
`scripts/build-roads-tiles.sh`. It reuses the trail pipeline's island clip, junction splitting,
reconcile, stable-id registry and DEM sampler, and adds road-specific classification. Full contract:
[`data-spec.md`](data-spec.md).

Outputs the app would consume, all generated: `roads.json` (664 road rows with miles, climb,
ownership, surface, speed), `roads.pmtiles` (675 KB, z10–16), `coverage_segments.json` (same
contract as the trail dataset with `road_ids` in place of `trail_ids`), `road_ends.json`
(21 public road ends at the shoreline, 17 of them corroborated by the City's own road-end layer),
`ATTRIBUTION.md`.

Road segment ids are namespaced `rseg-000001…`, so they can never collide with the trail dataset's
`seg-000001…` in the backend's `coverage(user_id, segment_id)` table.

## What is not built, and what it would take

The app is not touched. The work splits cleanly because the coverage engine already is a separate
module that takes a dataset:

| Area | File(s) today | What a roads version needs |
|---|---|---|
| Coverage engine | `app/coverage/.../Dataset.kt`, `DatasetBinary.kt`, `SegmentIndex.kt`, `HmmMatcher.kt` | Nothing structural. `Dataset` deserialises `trails`/`trail_ids`; add `@JsonNames("roads")` / `@JsonNames("road_ids")` aliases (kotlinx-serialization) so one model reads either dataset |
| Asset install | `app/.../map/MapAssets.kt` (`"trails" to "trails.pmtiles"`, metadata `trails.json`), `scripts/sync-app-assets.sh` | A dataset descriptor instead of two string constants: tile set name, metadata file, which pmtiles to copy |
| Map style | `app/.../map/StyleTemplate.kt`, committed styles | A roads layer set: colour by `category`, dash or grey `ownership=private`, `included=false` muted |
| Trail detail / list | `app/.../map/TrailInfo.kt`, Trails list screen | Road detail: no trailheads, no park; show surface, speed limit, shoulder, climb, road ends |
| Strings / branding | app strings | "trail" → "road" throughout; the goal line and celebration copy |
| Backend | `backend/migrations/0001_init.sql` — `coverage(user_id, segment_id)`, plus `island_m`/`covered_m` aggregates and the leaderboard | Per-segment rows are safe (distinct id prefix), but the aggregates and leaderboard would blend both goals. Add a `dataset` column (`trails`/`roads`) to the aggregate rows and scope the leaderboard by it, or deploy a second Worker + D1 for roads |

### Product options

1. **Second app, shared code** (`com.bainbridgetrails.roads`): one codebase, a build flavour that
   picks the dataset, branding and store listing per flavour. Two store presences, two goals, no
   schema change beyond scoping aggregates.
2. **One app, two goals** (Trails tab / Roads tab, one island total each): best for the user who
   wants both, most work in the UI and the backend aggregates, and it risks diluting the v1 pitch.
3. **Roads as an expansion of the current app** (one combined 235 mi total): simplest story, but it
   invalidates every existing "island total" number and leaderboard standing.

**Recommendation: option 1.** The dataset contract is already identical, the engine is reusable
as-is, and it keeps the v1 app's goal and leaderboard intact. Option 2 stays available later because
both datasets already use one id space with distinct prefixes.

## Phases

- **P0 — data (done).** Sources surveyed and verified, pipeline, dataset, QA report, tiles.
- **P1 — app reuse.** Dataset descriptor + `@JsonNames` aliases, roads map style, road detail
  screen, strings, flavour. No new engine work.
- **P2 — backend scoping.** `dataset` column on the aggregate/leaderboard tables (or a second
  deployment), sync keyed per dataset.
- **P3 — road-specific enrichment** (all sources already surveyed, none fetched):
  WSDOT shoulder width and legal speed limits on SR 305, WSDOT AADT counts (9 stations on the
  island) for a traffic-exposure rating, the City's snow-priority routes as a second witness that a
  road is city-maintained, the City's ROW vacations to retire paper streets, the City's 2024 road
  inventory for pavement width.
- **P4 — the walkability layer.** Shoulder, sidewalk, width, speed and traffic make a
  "pleasant / careful / avoid" rating per road, which is the thing a trail app cannot offer and the
  reason a road-walker would open this app twice.

## Decisions the owner has to make

1. **Private roads in or out of the goal?** Out today (58.7 mi). They are drawn but uncounted, and
   flipping them on is one line in `data/roads/inclusion.roads.yaml` — no rebuild of the data.
2. **Named lanes and alleys** (3.8 mi) — same switch, currently out.
3. **WSDOT geometry.** Not shipped. WSDOT's service states only "Washington State Department of
   Transportation", and the statewide OCIO disclaimer says state data "is in the public domain but
   commercial use and licensing of state data is prohibited". Shipping it in a paid app needs
   written permission (onlinemapsupport@wsdot.wa.gov). Everything works without it.
4. **City of Bainbridge Island geometry.** Same posture as the trail dataset: attributes yes,
   geometry no. Worth one email to the City GIS team to get it in writing either way.
5. **Second app or second tab** (see above).
6. **The 7.0 mi of unknown ownership.** Listed in `data/roads/reports/qa.md`; each row confirmed
   public adds its metres to the goal. Settling them is an afternoon with the City's road map.

## Risks

- **Licensing** (3, 4 above) is the only one that can change what ships. Mitigated: the shipping
  dataset is OSM-only today.
- **OSM cannot tell public from private** on this island: `access=private` appears on only 16 of
  1246 carriageway ways, while the agencies say 58.7 mi is private. The dataset therefore depends on
  the agency sources for ownership, and a road the agencies do not cover stays `unknown` and
  uncounted rather than silently inflating the goal.
- **Driveways.** 326.8 km of OSM `service=driveway` is excluded by the query, measured and recorded
  in `sources.roads.yaml` so the exclusion stays auditable. If the goal ever includes them it is a
  query change and a rebuild, not new research.
- **Dual carriageways / one-way pairs** would double-count a road's length. On Bainbridge
  `oneway=yes` covers only 6.9 km, so this is negligible; the data spec records it so the question is
  not reopened.
- **Completion feel.** 164 mi of road, much of it shoulderless and 35–50 mph, is a harder and less
  pleasant goal than 65 mi of trail. P4's walkability rating is what keeps it honest.
