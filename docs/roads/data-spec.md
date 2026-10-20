# Road dataset — data contract

How `data/roads/` is produced, what every field means, and what is still open. Companion to
[`plan.md`](plan.md). Pipeline code: `pipeline/src/bitrails/roads/`.

## Rebuild from a clean clone

```sh
bitrails fetch --only usgs_3dep_dem_island    # shared DEM into data/raw/ (~30 MB, gitignored)
scripts/bitroads fetch                        # road sources into data/roads/raw/
scripts/bitroads build                        # -> data/roads/canonical + reports/{conflicts,qa}.md
scripts/bitroads dist                         # -> data/roads/dist + reports/summary.md
scripts/build-roads-tiles.sh                  # -> data/roads/dist/roads.pmtiles
```

`bitroads build` is offline and deterministic, takes ~3 s, and writes every output at the end
(temp file + rename), so a failed build changes nothing. It aborts before writing if any of its four
required line sources is uncached, because dropping one would shrink the dataset and retire its
registry ids forever; `--allow-missing <id>` is the dev escape hatch and then saves no registry.

Two inputs are shared with the trail dataset and only ever read: the island boundary
(`data/raw/osm_island_boundary/`, committed) and the DEM (`data/raw/usgs_3dep_dem_island/`,
gitignored). A roads run writes nothing outside `data/roads/`.

## Sources

Full survey with verified counts, fields and licence text: `data/roads/sources.roads.yaml`.

| source | role | geometry ships? |
|---|---|---|
| `osm_overpass_roads` | road centerlines, names, surface, shoulder, speed, bicycle tags | **yes** (ODbL) |
| `cobi_rd_streets` | City street centerlines: `Maint`, `Rd_Class`, `Status`, `Speed_Limi`, `Rd_Surface`, `Mini_width`, and the City's " PVT" private marker | no — attributes only |
| `wsdot_local_agency_public_road` | the authoritative public-road inventory: `Jurisdiction` (City/County/Private/parks/school) and federal functional class | no — attributes + the goal's denominator |
| `wsdot_state_route_lines` | SR 305, the one road the City does not maintain | no — attributes only |
| `cobi_rd_road_ends` | 65 road-end / shore-access points; corroborates derived road ends | no — never used for position |
| 6 more, surveyed and not fetched | shoulder width, AADT, snow routes, ROW vacations, 2024 road inventory, the statewide data disclaimer | — |

The OSM query takes carriageway classes (`motorway`…`residential`, `living_street`, `road`, `*_link`)
plus service roads that are **named** or `service=alley`. Excluded, and measured on 2026-10-19 so the
exclusion stays auditable: 7215 driveways (326.8 km), 1535 unnamed service ways (151.1 km),
157 parking aisles (7.7 km). Footways, paths, steps, cycleways and tracks belong to the trail
dataset and are not fetched here.

Licence posture, unchanged from the trail dataset: only sources that state redistribution ship
geometry. The City states no licence; WSDOT's services carry only a copyright line and the statewide
OCIO disclaimer ("all data created by the state, using government funds, is in the public domain but
commercial use and licensing of state data is prohibited"). Both are therefore attributes-only, with
their geometry held in `data/roads/canonical/private/` (gitignored) and credited in the generated
`ATTRIBUTION.md` under "names and attributes only".

## Pipeline stages

1. **normalize** — explode, clip to the island polygon, split at junctions (1 m snap), per source.
2. **reconcile** (shared with trails) — merge sources in `geometry_rank` order: OSM is the base,
   agency features transfer attributes onto the canonical segment they substantially overlap, and
   agency geometry with no OSM counterpart is appended as an agency-only segment (258 of those,
   `missing_in_osm` in `reports/conflicts.md`).
3. **stable ids** — `data/roads/registry/segments.json`, ids `rseg-NNNNNN`, matched by
   fingerprint/geometry so an id survives a source refresh. Ids are never reused. The namespace is
   distinct from the trail dataset's `seg-` on purpose: the backend keys coverage by segment id.
4. **classify** — see below.
5. **infer ownership** — a segment no agency source matched inherits the ownership that ≥80 % of its
   own street's attested length agrees on (`ownership_source: inferred:name`); 159 segments today.
   Unnamed unmatched segments stay `unknown`.
6. **overrides** — `data/roads/roads_overrides.yaml`, by segment id or street name, may set
   `ownership`, `category`, `name` or `drop: true`. Empty today; every candidate needs a human.
7. **group into roads** — segments sharing a street key (direction- and abbreviation-insensitive)
   and hanging together within 50 m become one road; a reused name in two places becomes two roads.
8. **elevation** — 20 m DEM samples, 2 m hysteresis per segment; a road's `climb_total_m` is the
   direction-free sum (gain + loss) over its segments.
9. **dist** — recompute every road row from shippable segments only, so each road the app lists can
   actually be completed, and record what is missing as `agency_only_length_mi`.

## Classification

**Category** (road type, first match wins): `state_route` (WSDOT state route, or OSM `ref`/name says
SR 305) → `arterial` (OSM primary/secondary/+links, City `*Arterial`, WSDOT `*Arterial`) →
`collector` (OSM tertiary, City `Collector`, WSDOT `*Collector`) → `residential` (OSM
residential/unclassified/living_street/road, City `Residential *`, WSDOT `*Local Access`) → `lane`
(named `highway=service`, `service=alley`) → `driveway` → `unknown`.

**Ownership** (who it belongs to, independent of type): state route → `public_state`; else WSDOT
`Jurisdiction` → `public_city` / `public_county` / `public_parks` / `public_school` / `private`; else
the City layer (`Maint=COBI` → `public_city`; `Maint`/`Status`/`Rd_Class` private, or a " PVT" name
marker → `private`); else OSM `access=private|no` → `private`; else `unknown`. WSDOT outranks the
City layer because it is the federal-reporting public-road inventory; the 63 centerlines where the
two disagree are listed in `reports/qa.md`, never averaged.

**Street names.** OSM spells names out ("Northeast Dingley Road"), the agencies abbreviate and move
the direction ("Dingley Rd NE"). `names.base_key` drops direction and abbreviation so the same street
matches across sources; the app shows OSM's spelling where OSM has one. WSDOT writes the literal
"PVT" in `RoadName` for private centerlines and the City leaves blanks as `" "` — both are treated as
"no name", not as a name.

**Inclusion rule** (`data/roads/inclusion.roads.yaml`, evaluated at build time, no re-classification
needed to change it): counted = `public_access` not excluded **and** category in
{state_route, arterial, collector, residential} **and** ownership public. Private roads, lanes and
driveways are mapped, drawn and never counted.

## Outputs

`data/roads/canonical/` (committed except `private/`):

| file | what |
|---|---|
| `segments.geojson` | one line per shippable segment with the full property set below |
| `private/agency_only.geojson` | agency-only geometry — never ships, never tiled |
| `elevation.json`, `private/elevation_agency_only.json` | 20 m profiles per segment |
| `roads.json` | rule snapshot, island totals, dataset stats per category and ownership, `qa` counts, one row per road |

Segment properties: `id`, `name`, `agency_names`, `category`, `ownership`, `ownership_source`,
`maintainer`, `functional_class`, `surface` (paved/gravel/dirt/unpaved), `speed_mph`, `lanes`,
`shoulder`, `sidewalk`, `bike`, `row_width_ft`, `public_access`, `allowed_uses`, `sources`,
`confidence`, `included`, `geometry_redistributable`, `road_ids`, `length_m`, plus elevation stats.

`data/roads/dist/` is generated and gitignored — see `data/roads/dist/README.md` for each file.

`data/roads/reports/`: `conflicts.md` (reconcile, by registry id), `qa.md` (the rows a human must
settle), `summary.md` (the human read-out with every number in this document).

## Current numbers (build of 2026-10-19)

2261 segments (2003 shippable), 371.0 km mapped. Counted: **264.3 km / 164.2 mi**, of which
261.5 km / 162.5 mi has shippable geometry. 777 named roads, 442 counted; 664 roads reach the app.

| category | km | counted km | | ownership | km | counted km |
|---|---|---|---|---|---|---|
| arterial | 64.4 | 63.8 | | public_city | 251.4 | 251.0 |
| collector | 69.7 | 68.4 | | public_state | 12.1 | 11.7 |
| residential | 209.1 | 120.4 | | public_parks | 1.5 | 1.3 |
| state_route | 11.9 | 11.7 | | public_school | 0.3 | 0.3 |
| lane | 6.2 | 0.0 | | private | 94.5 | 0.0 |
| driveway | 2.0 | 0.0 | | unknown | 11.3 | 0.0 |
| unknown | 7.8 | 0.0 | | | | |

Road ends at the shoreline: 21 derived from shipping geometry, 17 corroborated by the City's
road-end layer.

## Known gaps

- **11.3 km / 7.0 mi of unknown ownership** (142 segments, 77 of them unnamed): no agency centerline
  matched and the street's own segments do not agree. Held out of the goal, listed in `qa.md`.
- **67 `different`-kind name mismatches**: the 10 m match buffer occasionally lends a centerline the
  neighbouring street's name and attributes. Mostly between two public city streets, so the goal
  total barely moves, but each is an override candidate.
- **136 `geometry_offset` conflicts**: WSDOT's LRS centerlines sit up to 29 m off the OSM
  carriageway. Expected for a linear-referencing system; it affects matching, never the geometry
  that ships.
- **Surface and speed are thin**: OSM has `surface` on 43 % of carriageway length and `maxspeed` on
  19 %; the City's `Rd_Surface` is blank for 203 of 207 mi. P3's WSDOT layers and the City's 2024
  inventory are the fix.
- **No sidewalk network.** Sidewalks are in the trail dataset's OSM extract as footways and are
  excluded here. If the roads goal ever means "walk the sidewalk where there is one", that is a
  separate layer, not a change to these centerlines.
