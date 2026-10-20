# data/roads/dist — road distributables

Generated, not committed (see `.gitignore`). Rebuild from a clean clone:

```sh
bitrails fetch --only usgs_3dep_dem_island    # shared DEM, into data/raw/ (gitignored, ~30 MB)
scripts/bitroads fetch                        # road sources, into data/roads/raw/
scripts/bitroads build                        # -> data/roads/canonical + reports
scripts/bitroads dist                         # -> this directory + reports/summary.md
scripts/build-roads-tiles.sh                  # -> roads.pmtiles
```

| file | what |
|---|---|
| `roads.json` | app metadata: `version`, island totals, one row per road (segment ids, miles, climb, ownership, surface, speed, `counted`, `agency_only_length_mi`) recomputed from shippable segments only |
| `segments.tiles.geojson` | tippecanoe input: `id`, `category`, `ownership`, `road_ids`, `name`, `included` |
| `roads.pmtiles` | vector tiles, layer `segments`, z10–16 (built by `scripts/build-roads-tiles.sh`) |
| `coverage_segments.json` | per-segment 6-dp geometry for on-device map matching and coverage; same contract as the trail dataset's file with `road_ids` in place of `trail_ids` |
| `road_ends.json` | public road ends within 40 m of the shoreline, derived from shipping geometry; `city_*` fields are attributes from the City's road-end layer (its coordinates are never used) |
| `ATTRIBUTION.md` | required credits, split into geometry sources and attribute-only sources |

Basemap and terrain are shared with the trail dataset: `data/dist/basemap.pmtiles`,
`data/dist/terrain/*` (`scripts/build-tiles.sh`, `scripts/build-terrain.sh`).

Only OSM-derived geometry ships. The City of Bainbridge Island's street layer and WSDOT's
public-road inventory state no redistribution licence, so they are used for ownership, names and
attributes — and as the public-road denominator — with geometry held in
`data/roads/canonical/private/` (gitignored).
