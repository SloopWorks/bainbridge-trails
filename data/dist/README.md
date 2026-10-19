# data/dist

Built artifacts (gitignored except this file). The app bundles these. Distribution is decided in a later task.

## Terrain tiles

```
(cd pipeline && uv run bitrails fetch --only usgs_3dep_dem_island)   # once; ~3 m island DEM, cached in data/raw/
scripts/build-terrain.sh                                              # slow; separate from `bitrails build`
```

Outputs in `data/dist/terrain/`:

- `contours.pmtiles`: vector, layer `contours`, z11-16, 5 m interval; props `elev_m`, `index` (true every 25 m;
  index contours from z11, minor from z13).
- `hillshade.pmtiles`: raster PNG (gray + alpha), multidirectional hillshade, z10-15.

Source: USGS 3DEP (public domain). Attribution: Data available from U.S. Geological Survey, National Geospatial
Program. Needs gdal, tippecanoe, pmtiles, jq.

## App distributables

```
(cd pipeline && uv run bitrails fetch --only usgs_3dep_dem_island)   # once
(cd pipeline && uv run bitrails build)                                # canonical data (commit data/canonical first: dist version uses its commit date)
(cd pipeline && uv run bitrails dist)                                 # trails.json, coverage_segments.json, segments.tiles.geojson, ATTRIBUTION.md (+ data/ATTRIBUTION.md), data/reports/summary.md
scripts/build-tiles.sh                                                # trails.pmtiles, basemap.pmtiles (network), terrain if missing
```

| file | what | size |
|---|---|---|
| `trails.pmtiles` | vector, layer `segments`, z10-16; props `id`, `category`, `trail_ids` (comma-joined string, MVT has no arrays), `name` (absent when null), `included` (bool). Public segments only. | 1.1 MB |
| `basemap.pmtiles` | Protomaps basemap extract (OSM-derived, ODbL), bbox -122.62,47.57,-122.47,47.73; the source tops out at z15, overzoom beyond. Daily build 20261017. | 5.1 MB |
| `terrain/contours.pmtiles` | see above | 3.1 MB |
| `terrain/hillshade.pmtiles` | see above | 9.3 MB |
| `trails.json` | app metadata: `version` (`<date of latest commit touching data/canonical>+<8 hex sha256 of segments.geojson>`), `island_total_m`, `island_total_shippable_m` (app v1 uses the shippable one), `trails[]` (id, name, manager, park, segment_ids, computed_length_mi, gain_m, loss_m, climb_total_m, trailheads, official_map_url, agency_only_length_mi, official_length_mi, official_length_source, folded_from). `official_length_mi` (> 0) and `official_length_source` come from `trails_overrides.yaml` (null when unset); `folded_from` (only on receivers) lists trails under 30 m folded in. Every per-trail figure comes from shippable segments only; `agency_only_length_mi` is the missing part; all-agency-only trails are omitted. | 60 KB |
| `coverage_segments.json` | geometry for on-device coverage (compact JSON): `version` (same as `trails.json`), `segments[]` of `{id, length_m, included, trail_ids, coords, category}` (`coords` = `[lon,lat]` rounded to 6 dp). Every redistributable segment (7,537; non-`included` ones are map-matching competitors with `trail_ids` = `[]` unless they belong to a trail), with its canonical `category`, sorted by id; `trail_ids` = the `trails.json` trails whose `segment_ids` contain the segment (so folded segments name their receiver; same for tile `trail_ids`). Orientation is fixed: `coords` run so the first `[lon,lat]` is lexicographically ≤ the last (bit indices for sync depend on it). Sum of `length_m` where `included` = `island_total_shippable_m`. | 1.8 MB |
| `ATTRIBUTION.md` | credits for every source used (also committed at `data/ATTRIBUTION.md`) | 1 KB |
| `segments.tiles.geojson` | tippecanoe input (props whitelisted), intermediate | 2.2 MB |

Basemap build is pinned with `BASEMAP_BUILD=YYYYMMDD`; `BASEMAP_SKIP=1` skips the network step.
