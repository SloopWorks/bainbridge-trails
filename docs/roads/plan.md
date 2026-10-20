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
(state, city, county, parks, school). 743 named roads of which 436 are counted; 664 of those roads have shippable geometry and so reach
the app. 3818 canonical segments after the ~120 m split (RD-2a; 2261 before).

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
`ATTRIBUTION.md`. Since RD-2b `coverage_segments.json` also carries the trail dataset's segments as
matching-only competitors (2.4 MB).

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

## What the roads mode actually feels like (prototype, 2026-10-20)

Branch `roads/app-prototype`: the v1 app runs on `data/roads/dist` behind a debug-only switch
([`DatasetProfile`](../../app/composeApp/src/commonMain/kotlin/com/bainbridgetrails/data/DatasetProfile.kt)),
no flavour, no second model, no engine change. Screenshots: [`screens/`](screens/).

**It works, and it works on the first try.** `@JsonNames("roads")` / `@JsonNames("road_ids")` were the whole
app/coverage change; `Dataset`, `SegmentIndex`, `HmmMatcher` and `Progress` read the roads dataset unmodified, and
`dataset.bin` packs it too (468 KB, parsed in 237–387 ms on an emulator against the trail dataset's 980 KB). The
second dataset adds ~2.2 MB to the APK, so both ship and the switch needs no rebuild. On the device the engine
matched a replayed road walk to the right road with no bleed onto the parallel road 100 m away
([`walk-in-progress.png`](screens/walk-in-progress.png)).

**What it feels like is *flat*.** A 0.5 mi walk took the island from 0 % to **0.1 %** (0.4 km of 261.5 km). The same
walk on the trail goal is 0.5 %. 2.5× the denominator means the first month shows almost nothing, which is the
multi-year-map argument from the other side: it is a real risk to first-run retention, not just a longer game.

**It reads as a different kind of map.** The trail dataset draws sparse ribbons inside parks; the roads dataset covers
the whole island, so at island zoom "not started" is 2003 dotted segments edge to edge
([`map-island.png`](screens/map-island.png)). The category bed added here (state route → arterial → collector →
residential, muted dashes for private, dots for the ownership holdouts) is what keeps it legible
([`map-street.png`](screens/map-street.png)); without it every road is one weight and the island is a lace.

### The three questions this was built to answer

**1. Does per-segment painting read well on a 165 m-average road network? No — the segments are too long.**

| counted segments | n | median | mean | p90 | max | share of length in segments > 200 m | > 400 m |
|---|---|---|---|---|---|---|---|
| trails | 1589 | 29 m | 66 m | 165 m | 1314 m | 41 % | 17 % |
| roads | 1462 | **122 m** | **179 m** | **398 m** | 1674 m | **64 %** | **33 %** |

The buckets themselves are fine; the unit they paint is not. Island Center Road NE is 0.77 mi in **two** segments,
137 m and **1108 m**. A replayed 494 m walk finished the 137 m segment (violet) and covered 32 % of the 1108 m one —
which paints the **whole 1108 m** gold "Partway", including the long westward leg the walker never entered
([`walk-paints-whole-segment.png`](screens/walk-paints-whole-segment.png)). **751 m of unwalked road reads as
progress.** On trails the same error is a 29 m segment, i.e. invisible.

This is a pipeline fix, not an app fix: split road segments at a target length (~100–150 m) in
`pipeline/src/bitrails/roads/` after junction splitting. Ids are issued per segment, so doing it **before** a roads
release is much cheaper than after — `rseg-*` ids are never reused and the backend keys coverage by them.

**Fixed (RD-2a, 2026-10-20).** `roads/split.py` cuts every segment over 150 m into equal pieces of ~120 m before the
registry issues ids (all segments, so the geometry never depends on the inclusion rule). 878 `rseg-*` ids retired,
2435 issued, `supersedes` recorded. Island total unchanged (264.3 km).

| counted segments | n | median | mean | p90 | max | share of length in segments > 200 m | > 400 m |
|---|---|---|---|---|---|---|---|
| roads, before | 1462 | 122 m | 179 m | 398 m | 1674 m | 64 % | 33 % |
| **roads, after** | **2619** | **109 m** | **100 m** | **133 m** | **150 m** | **0 %** | **0 %** |

Island Center Road NE's 1108 m segment is now 9 pieces of 123 m, so the replayed 494 m walk paints at most one
partway piece past where the walker stopped instead of 751 m of unwalked road.

**2. Does `bufferM = 30` over-credit parallel streets or the other side of a two-sided road? No.**

Sweeping the app's own matcher over the roads dataset (1328 simulated walks per row, HMM, `smoothWindowS = 10`,
threshold 0.85), false credit = covered metres on roads *other than the walked one* ÷ walked metres:

| buffer | σ = 5 m: false credit | complete rate | σ = 10 m: false credit | complete rate |
|---|---|---|---|---|
| 10 m | 0.80 % | 87.7 % | 2.22 % | 11.9 % |
| 20 m | 0.64 % | 96.4 % | 2.23 % | 82.0 % |
| **30 m** | **0.64 %** | **96.3 %** | **1.80 %** | **89.3 %** |

False credit is flat-to-falling in buffer while the completion rate climbs, because a wider buffer gives the HMM more
of the right road to lock onto. For comparison the trail dataset at the same settings is **0.90 %** — the road network
is slightly *better* behaved than the trail network, which makes sense: `oneway=yes` covers only 6.9 km of the island
and the dataset carries no sidewalks at all (they are footways in the trail dataset, see `data-spec.md`), so there are
very few parallel carriageways to confuse. One walk in 1328 falsely completed another road.

So `bufferM = 30` is not the problem *inside* the roads dataset. It is the problem *across* datasets:

**3. Does a walk on a trail falsely paint a nearby road? Yes — badly. This was the one blocker; fixed (RD-2b).**

New tool for this: `coverage-eval cross --data <roads dist> --meta roads.json --walk-data <trail dist>` walks every
trail chain with the app's matcher and scores it against the roads dataset.

| scenario (114 trails, 669 chain walks, 200.4 km walked, 3 seeds) | road metres credited | ÷ walked |
|---|---|---|
| roads dataset as it ships today | **19.4 km** | **9.7 %** |
| same, with trail segments injected as `included: false` competitors | 0.6–2.3 km | 0.3–1.2 % |
| **as shipped since RD-2b** (split + trail competitors, sidewalks/crossings/walkways left out) | **0.67–2.43 km** | **0.33–1.21 %** |

And the buffer ladder shows exactly where it comes from (1 seed, σ = 5 m):

| buffer | 10 m | 15 m | 20 m | 25 m | 30 m |
|---|---|---|---|---|---|
| roads only | 0.05 % | 0.68 % | 3.4 % | 6.4 % | **9.6 %** |
| with trail competitors | 0.20 % | 0.36 % | 0.36 % | 0.36 % | **0.36 %** |

Worst offenders are the roads the trails run beside: Battle Point Drive NE (2.35 km over three seeds), SR 305
(1.54 + 1.02 + 0.30 km), NE Tani Creek Road (1.07 km), NE Oddfellows Road, Old Mill Road NE. `CoverageParams` already
documents the mechanism — at `offNetworkM = bufferM = 30`, *every* walk within the buffer of a lone feature is
credited to it, and in the roads dataset the trail is not there to compete.

**Recommendation: ship the trail segments into the roads dataset as competitors** — a change in
`pipeline/src/bitrails/roads/dist.py`'s `coverage_segments_doc`, appending the trail dataset's shippable segments with
`included: false` and `road_ids: []`. No app change, no licensing change (the trail dataset already ships only
redistributable geometry), no id collision (`seg-*` vs `rseg-*`), and `SegmentGeom.isScored` already makes such a
segment matching-only, so it can never be credited or drawn as progress. Cost: `coverage_segments.json` grows from
788 KB to roughly 2.5 MB and `dataset.bin` likewise. Caveat on the 0.3–1.2 %: a simulated walk follows the trail
centreline exactly, so the injected competitor is a perfect match; expect a real walk to do somewhat worse.

**Fixed (RD-2b, 2026-10-20)** exactly as recommended, with one refinement: the trail dataset's road-proxy footways
(sidewalks, crossings, walkways, traffic islands, links, access aisles — 2689 segments, 56.6 km) are **not** shipped as
competitors, because walking a sidewalk is walking the road and should credit it. Including them bought nothing on
trail walks (0.33–1.20 % vs 0.33–1.21 %) and cost road walks 1.5–4.1 points of completion. Shipped: 4848 competitors
(228.3 km); `coverage_segments.json` 0.8 → 2.4 MB, `roads_dataset.bin` 468 KB → 1.3 MB.

Within-roads check (sweep, 1328 walks, HMM, smooth 10 s, threshold 0.85, buffer 30 m), not worse:

| | σ = 5 m: false credit | complete rate | σ = 10 m: false credit | complete rate |
|---|---|---|---|---|
| before | 0.64 % | 96.3 % | 1.80 % | 89.3 % |
| after RD-2 (split + competitors) | 0.63 % | 96.1 % | 1.82 % | 88.6 % |

The split alone moves neither number (0.64 % / 96.2 %, 1.85 % / 89.9 %); the remaining ~0.5 point of completion is
road walks beside a real trail, where the trail now takes some of the credit — the intended trade.

### Other things the prototype exposed

1. **"Walk, hike or bike" does not work today.** `CoverageParams.maxSpeedMps = 4.0` drops any fix faster than
   ~14.4 km/h over a 30 s window (`filterFixes` rule 4) and `Gating` drops vehicle-classified activity, so a bike
   ride credits nothing. The roads goal names cycling; the trail app's anti-driving defence is the same mechanism.
   Resolving this (a mode switch? a per-dataset speed ceiling? accepting that only walking counts?) is a product
   decision, not tuning, and it belongs in P1, not P4.
2. **Strings are the loudest tell.** "Find a trail", the Trails tab, "664 of 664 trails", "of the island's trails
   walked", "Trail miles on this phone", "Your walk is matched to mapped trails". None of it is wrong-looking enough
   to break the prototype and all of it has to change.
3. **The island denominator is hardcoded in copy**, not read from the dataset: onboarding says "over 60 miles of
   public trails" and no screen ever prints the total. Only the debug drawer shows it (261.5 km = 162.5 mi,
   [`debug-dataset.png`](screens/debug-dataset.png)). A flavour or a tab needs this data-driven.
4. **Road detail drops the road-specific data.** `roads.json` carries `climb_total_m`, `surface`, `speed_mph` and
   `maintainer`, but `TrailMeta` has no fields for them, so the detail screen shows "Elevation gain —" and nothing
   else road-shaped ([`road-detail.png`](screens/road-detail.png)). Road ends (`road_ends.json`) are not wired at all.
5. **The uncounted-feature treatment had to change.** Roads tiles carry `road_ids`, not `trail_ids`, so every
   uncounted road falls into `StatusLayers`' unnamed-hairline branch. That is the muted read we want, but the trail
   hairlines now explicitly skip road categories so the style's own private/holdout layers show through.
6. **MapLibre gotcha, recorded so nobody repeats it:** a zoom `interpolate` nested inside `["*", …]` is not a valid
   paint property and the native renderer silently drops the layer. The category bed applies its class factor per
   interpolation stop instead (`scripts/build-map-styles.py`).
7. Leaderboard and aggregate totals still blend the two goals — unchanged, still P2.

### What this says about the decision

The engine and the data contract are a non-issue: one enum and two `@JsonNames` aliases. What separates "second app"
from "second tab" is everything *around* the dataset — strings, the goal number, the detail screen, the bike
question, and a backend that can hold two island totals. Option 1 (second app, shared code) still looks right, and
nothing here argues for merging the goals. The two things that should happen **before** any roads release, because
they are expensive afterwards, are the segment split (P0, ids are permanent) and the trail competitors (P0, it is a
correctness bug a user would see as their map painting itself). **Both done (RD-2, 2026-10-20)** — see the "Fixed"
notes under questions 1 and 3.

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
