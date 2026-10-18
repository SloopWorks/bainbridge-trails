# Spikes 1–2: Trail Data Pipeline + Map Render — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a classified, reproducible dataset of every trail/path on Bainbridge Island, and render it as styled vector maps in a KMP + Compose Multiplatform shell on iOS and Android.

**Architecture:** A Python pipeline (`pipeline/`) fetches every public source into a raw cache, normalizes to line segments with stable add-only IDs, reconciles across sources, classifies, adds elevation, and builds PMTiles + a metadata JSON into `data/dist/`. A KMP app (`app/`) bundles those artifacts and renders them with maplibre-compose on both platforms, with switchable styles.

**Tech Stack:** Python 3.12+ via `uv` (shapely, pyproj, requests, pytest), tippecanoe, pmtiles CLI; Kotlin Multiplatform, Compose Multiplatform, `org.maplibre.compose` (maplibre-compose), Gradle, Xcode 26.

**Spec:** `docs/superpowers/specs/2026-10-18-bainbridge-trails-design.md`

## Global Constraints

- $0/month hosting. No paid APIs, no API keys with billing.
- Only data whose license allows redistribution in an app ships in `data/dist/`; every source's license recorded in `data/sources.yaml`; attribution string per source.
- AllTrails/Trailforks: cross-check names/mileage only. Never ingest geometry.
- Capture all trails/paths; never pre-filter. Inclusion is a config rule (`data/inclusion.yaml`), not baked into data.
- Segment IDs are stable and add-only: never renumbered, never reused. Changed geometry → new ID + `supersedes` mapping.
- Shared segments count once toward island total.
- Canonical data = GeoJSON committed in repo (`data/canonical/`). Raw fetches cached in `data/raw/` (gitignored if license forbids redistribution).
- KMP + Compose Multiplatform shared UI; platform code only behind shared interfaces.
- Map styles are runtime JSON (MapLibre style spec); changing look must not require reprocessing data.
- Trail status colors distinguishable for color-blind users in every style.
- Nothing is "done" on the app side without screenshots from both iOS simulator and Android emulator.

## Review Focus

1. Same physical trail present in OSM and an agency source → must merge to one segment set, not double-count island miles.
2. Paths crossing/near island edge (Agate Pass bridge, ferry terminal, shoreline) → clip to island land polygon; nothing off-island counted.
3. Loops, multi-part lines, and unnamed ways → still produce valid segments with IDs; unnamed get `name: null` and category from tags, not dropped.
4. Re-running the pipeline with identical inputs → identical IDs and byte-identical canonical output (determinism).
5. Overpass/agency endpoint down or rate-limited → pipeline uses cached raw data and says so; does not silently emit an empty dataset.

---

## Spike 1 — Trail data

### Task 1: Source survey + license table (Geo/maps expert, research)

**Files:**
- Create: `data/sources.yaml`, `docs/research/2026-10-data-sources.md`

**Interfaces:**
- Produces: `data/sources.yaml` entries `{id, name, url, kind: overpass|arcgis_rest|geojson|pdf_map|elevation|reference_only, endpoint, license, redistribution: yes|no|unknown, attribution}`.

- [ ] **Step 1:** Survey every source in spec §3 (Metro Parks, Land Trust, City of BI GIS/open data, Kitsap County GIS, OSM, WA DNR LiDAR, USGS 3DEP, WA State Parks, community guides, AllTrails/Trailforks as reference_only). Find machine-readable endpoints (ArcGIS REST FeatureServer layers, open data downloads), official PDF map URLs, and license terms. Record island land boundary source (OSM relation or county parcel/coastline layer).
- [ ] **Step 2:** Write the research doc: per source, what it has (names, mileage, geometry, trailheads), quality notes, license verdict with URL quoted.
- [ ] **Step 3:** Verify with `research-verifier` agent on license claims; fix refuted ones.
- [ ] **Step 4:** Commit `docs: survey trail data sources and licenses`.

### Task 2: Pipeline scaffold, raw fetch + cache, island boundary

**Files:**
- Create: `pipeline/pyproject.toml`, `pipeline/src/bitrails/fetch.py`, `pipeline/src/bitrails/boundary.py`, `pipeline/src/bitrails/cli.py`, `pipeline/tests/test_fetch.py`, `pipeline/tests/test_boundary.py`, `pipeline/tests/fixtures/`

**Interfaces:**
- Consumes: `data/sources.yaml`
- Produces: `fetch_source(src: Source, cache_dir: Path, offline: bool) -> RawResult(path: Path, fetched_at: str, from_cache: bool)`; `island_polygon() -> shapely.Polygon` (EPSG:4326); CLI `uv run bitrails fetch [--offline]`.

- [ ] **Step 1:** Tests: `test_fetch_uses_cache_when_endpoint_fails` (mock 503 → returns cached, `from_cache=True`); `test_fetch_fails_loudly_without_cache` (503 + no cache → raises `FetchError` naming source id); `test_island_polygon_contains_known_points` (Grand Forest ~47.649,-122.546 inside; Seattle 47.606,-122.332 outside; Agate Pass bridge midpoint ~47.7130,-122.5720 outside land).
- [ ] **Step 2:** Run `uv run pytest` → FAIL.
- [ ] **Step 3:** Implement. Overpass query: all `way[highway~"path|footway|track|bridleway|cycleway|steps|pedestrian"]` plus `route=hiking|foot` relations within island polygon area. ArcGIS: paged `query?where=1=1&outFields=*&f=geojson` with `resultOffset`.
- [ ] **Step 4:** `uv run pytest` → PASS. Run `uv run bitrails fetch` for real; raw files land in `data/raw/<source_id>/`.
- [ ] **Step 5:** Commit.

### Task 3: Normalize to segments with stable IDs

**Files:**
- Create: `pipeline/src/bitrails/segments.py`, `pipeline/src/bitrails/registry.py`, `pipeline/tests/test_segments.py`, `pipeline/tests/test_registry.py`, `data/registry/segments.json`

**Interfaces:**
- Produces: `Segment(id: str, geometry: LineString, source_refs: list[SourceRef], tags: dict)`; `split_at_junctions(lines: list[LineString]) -> list[LineString]`; `Registry.load(path)`, `Registry.assign(geom: LineString) -> str` (format `seg-000123`), `Registry.save()`; registry stores `{id, fingerprint, supersedes: [ids], retired: bool}`.

Fingerprint = endpoints rounded to 1e-5° (sorted, direction-independent) + length rounded to 5 m. Match existing ID when fingerprint equal, or Hausdorff distance < 8 m and length within 5%. Otherwise new ID; if it overlaps a retired-candidate segment > 50%, record `supersedes`.

- [ ] **Step 1:** Tests: `test_same_geometry_same_id_across_runs`; `test_reversed_line_same_id`; `test_new_geometry_gets_next_id_never_reused` (retire seg-000002, add new → seg-000004 not 000002); `test_changed_geometry_records_supersedes`; `test_split_at_junctions_t_intersection` (T-shape → 3 segments); `test_loop_way_yields_valid_segment`; `test_clip_to_island_drops_offisland_part`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit.

### Task 4: Reconcile across sources + conflict report

**Files:**
- Create: `pipeline/src/bitrails/reconcile.py`, `pipeline/tests/test_reconcile.py`, output `data/reports/conflicts.md`

**Interfaces:**
- Consumes: segments per source (Task 3).
- Produces: `reconcile(per_source: dict[str, list[Segment]]) -> ReconcileResult(segments: list[Segment], conflicts: list[Conflict])`; `Conflict(kind: name_mismatch|length_mismatch|missing_in_osm|missing_in_agency|geometry_offset, segment_ids, detail)`.

Match rule: buffer 10 m, overlap ≥ 70% of shorter → same segment; geometry from highest-ranked source per `sources.yaml` `geometry_rank` (default OSM first), names from agency sources when present. Agency mileage vs computed length differs > 10% → `length_mismatch`.

- [ ] **Step 1:** Tests: `test_duplicate_across_sources_merges_once` (island total unchanged when same trail added from 2nd source); `test_name_mismatch_reported`; `test_agency_only_trail_kept_and_flagged_missing_in_osm`; `test_deterministic_output_order`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit.

### Task 5: Classification, trails, inclusion rule, island total

**Files:**
- Create: `pipeline/src/bitrails/classify.py`, `pipeline/src/bitrails/trails.py`, `data/inclusion.yaml`, `data/trails_overrides.yaml`, `pipeline/tests/test_classify.py`, `pipeline/tests/test_trails.py`

**Interfaces:**
- Produces: segment properties `category ∈ {agency_trail, preserve_trail, multi_use_path, connector, beach_access, easement, informal, seasonal_closed}`, `manager`, `sources[]`, `confidence ∈ {high, medium, low}`, `public_access ∈ {yes, permit, unknown, no}`, `allowed_uses[]`; `Trail(id: "trail-<slug>", name, manager, segment_ids: list[str], official_length_mi: float|None, computed_length_mi: float, park: str|None, trailheads: list[Point], official_map_url: str|None)`; `island_total_m(segments, rule) -> float`; `is_included(segment, rule) -> bool`.

Trails grouped from: OSM route relations, agency trail names, then same-name contiguous segments; manual fixes in `trails_overrides.yaml`. Default rule per spec: count `agency_trail, preserve_trail, multi_use_path, connector(named)`; show but don't count others. `access=private|no` → `public_access: no`, not counted.

- [ ] **Step 1:** Tests: `test_shared_segment_counted_once_in_island_total`; `test_private_access_excluded_from_total`; `test_rule_change_changes_total_without_reprocessing`; `test_unnamed_informal_path_kept_with_null_name`; `test_trail_lengths_sum_segments`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Write canonical outputs `data/canonical/segments.geojson`, `data/canonical/trails.json`; `uv run bitrails build` twice → `git diff --exit-code data/canonical` clean on second run.
- [ ] **Step 6:** Commit.

### Task 6: Elevation + topography

**Files:**
- Create: `pipeline/src/bitrails/elevation.py`, `pipeline/tests/test_elevation.py`

**Interfaces:**
- Produces: per-segment `elev_profile: list[[dist_m, elev_m]]` sampled every 20 m, `gain_m`, `loss_m`; per-trail totals. Contour + hillshade tiles for map in `data/dist/terrain/` (from USGS 3DEP or DNR LiDAR DEM, per Task 1 license).

- [ ] **Step 1:** Tests: `test_profile_sample_spacing`; `test_gain_loss_from_synthetic_dem` (fixture DEM ramp 0→30 m → gain 30, loss 0; reversed → loss 30); `test_elevation_noise_threshold` (±0.5 m jitter → gain ≈ 0 with 2 m hysteresis).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5:** Commit.

### Task 7: Build distributables + data report

**Files:**
- Create: `pipeline/src/bitrails/build.py`, `scripts/build-tiles.sh`, `data/reports/summary.md`

**Interfaces:**
- Produces: `data/dist/trails.pmtiles` (layer `segments`, props `id, category, trail_ids, name, included`), `data/dist/trails.json` (trail metadata + island total + dataset `version` = ISO date + short hash), `data/dist/basemap.pmtiles` (Protomaps extract clipped to island bbox, zoom ≤ 16), `data/dist/ATTRIBUTION.md`.

- [ ] **Step 1:** `brew install tippecanoe pmtiles gdal`; script runs tippecanoe `-Z10 -z16` and `pmtiles extract` against latest Protomaps daily build, bbox `-122.62,47.57,-122.47,47.73`.
- [ ] **Step 2:** `summary.md`: total trails, segments, island total miles per inclusion rule, counts per category/manager, table trail → official vs computed miles, conflict counts linking `conflicts.md`.
- [ ] **Step 3:** Verify: `pmtiles show data/dist/trails.pmtiles` lists layer `segments`; sizes logged (expect trails < 5 MB, basemap < 40 MB).
- [ ] **Step 4:** Commit. **Owner checkpoint:** owner reviews `summary.md` + classified list, may edit `inclusion.yaml`.

---

## Spike 2 — Map render (throwaway shell, keep only what earns it)

### Task 8: KMP + Compose Multiplatform shell

**Files:**
- Create: `app/` (Gradle KMP project: `app/composeApp` with `commonMain`, `androidMain`, `iosMain`; `app/iosApp` Xcode project), `app/gradle/libs.versions.toml`

**Interfaces:**
- Produces: package `com.bainbridgetrails`; Android `applicationId com.bainbridgetrails.app`; iOS bundle `com.bainbridgetrails.app`; `App()` root composable in `commonMain`; bundled assets `composeResources/files/{trails.pmtiles,basemap.pmtiles,trails.json,styles/*.json}`.

- [ ] **Step 1:** Generate project from JetBrains KMP wizard template (Android + iOS, shared UI). Latest stable Kotlin/CMP.
- [ ] **Step 2:** `./gradlew :composeApp:assembleDebug` → BUILD SUCCESSFUL; iOS build via `xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build` → BUILD SUCCEEDED.
- [ ] **Step 3:** Commit.

### Task 9: MapLibre rendering of bundled PMTiles on both platforms (Mobile APIs expert)

**Files:**
- Create: `app/composeApp/src/commonMain/kotlin/com/bainbridgetrails/map/TrailMap.kt`, `.../map/MapStyle.kt`

**Interfaces:**
- Consumes: Task 7 artifacts copied into app resources.
- Produces: `@Composable fun TrailMap(style: MapStyle, modifier: Modifier = Modifier)`; `enum class MapStyle(val assetPath: String)`.

- [ ] **Step 1:** Add `org.maplibre.compose` dependency. Load style JSON whose sources point at local `pmtiles://` file URIs (copy bundled assets to app files dir on first launch if the SDK needs file paths). If local pmtiles unsupported on a platform, fall back to MBTiles or a local tile server shim; record which in spike notes.
- [ ] **Step 2:** Map opens centered on island (47.645, -122.535, zoom 12), bounds-limited to island bbox.
- [ ] **Step 3:** Verify: screenshots from Android emulator (`adb exec-out screencap -p`) and iOS simulator (`xcrun simctl io booted screenshot`) saved to `docs/spikes/map-render/`. Trails visible on both.
- [ ] **Step 4:** Commit.

### Task 10: Map styles (Mobile design expert)

**Files:**
- Create: `app/composeApp/src/commonMain/composeResources/files/styles/{topo,minimal-light,dark}.json`, `docs/spikes/map-render/styles.md`

**Interfaces:**
- Produces: three MapLibre styles; each styles `segments` by `category` and by placeholder status property `status ∈ {not_started, partway, nearly_done, complete}` (fake values injected for spike); contours/hillshade in `topo`.

- [ ] **Step 1:** Design agent (frontend-design skill) defines palette + typography; status colors checked with a color-blind simulator (deuteranopia, protanopia) — status also encoded by line pattern/width, not color alone.
- [ ] **Step 2:** In-app style switcher (simple segmented control) for spike.
- [ ] **Step 3:** Screenshots of each style on both platforms → `docs/spikes/map-render/`. `styles.md` recommends one default with reasons.
- [ ] **Step 4:** Review agent (adversarial-reviewer) checks branch; fix P0/P1.
- [ ] **Step 5:** Commit. **Owner checkpoint:** owner picks style direction.

---

## After Spikes 1–2

Write separate plans, informed by spike notes: Spike 3 (coverage/map-matching, threshold), Spike 4 (background location + trailhead geofences), Spike 5 (Cloudflare Worker + D1 backend), then v1 feature plan (tracking, completion celebration, stats, leaderboard, accounts, moderation, Play Store metadata in `metadata/`).
