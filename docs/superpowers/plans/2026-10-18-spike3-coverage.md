# Spike 3: Coverage (map matching + completion) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn GPS fixes into per-segment coverage, then into per-trail % and island %. Choose the buffer width and the completion threshold from a parameter sweep over simulated tracks, and check them on the owner's real walks. Also surface each trail's official mileage when one is published.

**Architecture:**
- A pure-Kotlin KMP module `:coverage` (no UI, no platform APIs) holds the production logic:
  - local projection
  - fix filtering (anti-cheat)
  - segment "ticks" with a grid index
  - coverage bitsets that accumulate across sessions
  - trail/island progress
- A JVM-only `:coverage-eval` module simulates noisy walks/drives over the real dataset, sweeps the parameters, reads GPX files, and writes a report.
- The Python pipeline exports the segment geometry the coverage code needs (`coverage_segments.json`) and passes curated official mileage through to `trails.json`.

**Tech Stack:**
- Kotlin 2.4.21 Multiplatform (jvm, android, iosArm64, iosSimulatorArm64), kotlinx-serialization 1.11.0, kotlin-test, Gradle (AGP 9.4.1)
- Python pipeline `bitrails` via `uv` + pytest

**Spec:** `docs/superpowers/specs/2026-10-18-bainbridge-trails-design.md` — §2 Completion rules, §3 "Data review decisions", §5 Cheat protection / Privacy, §6 item 3.

## Global Constraints

- KMP shared code; the coverage logic lives in `commonMain` with no platform `expect`s. Platform location APIs belong to Spike 4.
- No new third-party dependencies beyond kotlinx-serialization (already in the catalog) and Kotlin's own Gradle plugins.
- Coverage is computed **on device** from GPS tracks. The server (Spike 5) will receive only per-segment coverage. Raw tracks never leave the device.
- **Real GPS tracks are never committed.** They live in gitignored `data/tracks/private/`. Simulated tracks are generated, not stored.
- Coverage accumulates across sessions, in any order or direction. Shared segments count once toward the island total and toward every trail that uses them.
- Walking/running speed only. Reject bike/car travel, large GPS jumps, and Android mock-location fixes.
- Island % denominator = `island_total_shippable_m` from `trails.json` (65.5 mi today).
- Per-trail % denominator = the sum of the trail's shippable segment lengths.
- Displayed trail length = `official_length_mi` when present, else `computed_length_mi`. Percentages always use computed geometry.
- Only redistribution:yes geometry is exported. Official lengths are facts with a cited source URL. No agency geometry.
- Never push or base work on the local branch `spikes/data-and-map`.
- Python: `cd pipeline && uv run pytest -q`. Kotlin: `cd app && ./gradlew :coverage:jvmTest :coverage-eval:test`.

## Review Focus

1. **Parallel trails closer than the buffer** (e.g. two Grand Forest paths 12 m apart): walking one must not complete the other. Expected: the neighbor stays < 50% → Task 6 test `parallelNeighborNotCredited`, plus the Task 9 sweep metric.
2. **Standing still at a junction** (GPS jitter for 10 minutes): must not accumulate coverage beyond the buffer radius → Task 6 test `stationaryJitterBounded`.
3. **Sparse background fixes** (one fix every 15–30 s, the OS throttling Spike 4 will meet): a straight walk must still cover the segment between fixes → Task 5 test `sparseFixesStayOnePiece` and Task 6 test `sparseFixesInterpolate`.
4. **Out-of-order or duplicate fixes** (Android batched delivery): the result must equal the sorted input → Task 5 test `unsortedAndDuplicateFixes`.
5. **Out-and-back walks / the same segment walked twice / walked in reverse**: no double counting, and direction doesn't matter → Task 6 test `repeatAndReverseIdempotent`.

---

## Data track

### Task 1: Export coverage geometry (`coverage_segments.json`)

**Files:**
- Modify: `pipeline/src/bitrails/dist.py` (new `coverage_segments_doc`, called from `run_dist`)
- Modify: `data/dist/README.md` (one table row)
- Test: `pipeline/tests/test_dist.py`

**Interfaces:**
- Produces `data/dist/coverage_segments.json`:
  ```json
  {"version": "<same as trails.json version>",
   "segments": [{"id": "seg-000001", "length_m": 66.5, "included": true,
                 "trail_ids": ["trail-x"], "coords": [[-122.512345, 47.623456], ...]}]}
  ```
- Which segments: every redistributable segment that is `included` **or** has non-empty `trail_ids`, sorted by id.
- Coords: lon/lat rounded to 6 decimals.
- `trail_ids`: only ids of trails present in dist `trails.json`.
- `coverage_segments_doc(segments: Path, trail_ids: set[str], version: str) -> dict`

- [ ] **Step 1: Write failing tests** in `test_dist.py`, using the existing `feat`/`canon` fixtures:
  - `test_coverage_segments_selects_included_or_trail_segments`: a feature with `included=False, trail_ids=[]` is absent; `included=True, trail_ids=[]` is present; `included=False, trail_ids=["trail-a"]` is present.
  - `test_coverage_segments_rounds_and_filters_trail_ids`: coordinate `-122.1234567` → `-122.123457`; a `trail_ids` entry not in the passed set is dropped; output is sorted by id.
  - `test_coverage_segments_refuses_non_redistributable`: `geometry_redistributable=False` → `ValueError`, same as `write_tile_input`.
  - `test_run_dist_writes_coverage_segments`: after `run_dist(..., date="2026-10-18T00:00:00Z")`, the file exists and its `version` equals the version in `trails.json`.
- [ ] **Step 2:** `cd pipeline && uv run pytest tests/test_dist.py -q` → the 4 new tests FAIL.
- [ ] **Step 3: Implement** `coverage_segments_doc`. Write the file in `run_dist` with `json.dumps(..., separators=(",", ":"))`. Add the README row: what the file is, its schema in brief, and its size.
- [ ] **Step 4:** `uv run pytest -q` → all pass. `uv run bitrails dist` → file written; record the size in the README row.
- [ ] **Step 5:** Sanity check:
  ```sh
  python3 -c "import json;d=json.load(open('data/dist/coverage_segments.json'));print(round(sum(s['length_m'] for s in d['segments'] if s['included'])))"
  ```
  This must equal `island_total_shippable_m` in `data/dist/trails.json` within 1 m. If it doesn't, stop and report; the two definitions disagree.
- [ ] **Step 6: Commit** `feat(dist): export coverage_segments.json for on-device coverage`

### Task 2: Official mileage plumbing

**Files:**
- Modify: `pipeline/src/bitrails/trails_overrides.py:17` (`EDITABLE` gains `official_length_source`)
- Modify: `pipeline/src/bitrails/trails.py` (Trail record gains `official_length_source: str | None`, serialized next to `official_length_mi`)
- Modify: `pipeline/src/bitrails/dist.py:28` (`TRAIL_FIELDS` gains `official_length_mi`, `official_length_source`)
- Modify: `pipeline/src/bitrails/summary.py` (official-vs-computed section)
- Modify: `data/trails_overrides.yaml` header comment (document `official_length_source`)
- Test: `pipeline/tests/test_trails.py`, `pipeline/tests/test_dist.py`

**Interfaces:**
- Produces: dist `trails.json` rows carry `official_length_mi: float | null` and `official_length_source: str | null`.
- Rule: an override with `official_length_mi` set and no `official_length_source` fails the build loudly (`ValueError` naming the trail id).
- The summary gets a section "Official vs computed length". It lists every trail with an official length, with columns `official_mi`, `computed_mi` (dist, shippable), `agency_only_mi`, `diff_%`, `source`. Rows where `|official − (computed + agency_only)| / official > 15%` are flagged with `⚠`.

- [ ] **Step 1: Failing tests:**
  - `test_override_official_length_requires_source` (raises)
  - `test_override_official_length_passes_through_dist`: the override sets 1.2 mi + source URL → the dist row has both.
  - `test_dist_official_length_null_by_default`
  - `test_dist_drops_unnamed_trails`: a canonical trail with `name: None` or `"  "` is absent from dist `trails.json`. This enforces the spec's "app lists only named trails"; today all 123 are named, so it's a guard. Implement it in `dist_trails_doc`.
  - `test_summary_flags_large_official_gap`: official 1.0, computed 0.5, agency-only 0.0 → the row contains `⚠`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `uv run pytest -q` → all pass. `uv run bitrails build && uv run bitrails dist` → canonical output is byte-identical except for the new `official_length_source: null` keys (check with `git diff --stat data/canonical`).
- [ ] **Step 5: Commit** `feat(data): official trail mileage with cited source`

### Task 3: Collect official mileage (research)

**Files:**
- Modify: `data/trails_overrides.yaml` (`trails:` entries with `official_length_mi` + `official_length_source` only)
- Modify: `data/sources.yaml` (one entry per new site, e.g. `bimprd_park_pages`, `redistribution: facts-only`, with its license note)
- Regenerate: `data/canonical/*`, `data/reports/summary.md`

**Interfaces:** consumes the Task 2 override fields.

- [ ] **Step 1: Research.** For each of the 123 named trails in dist `trails.json`, look for a published length on:
  - biparks.org park and trail pages
  - the 19 BIMPRD PDF maps listed in `summary.md`
  - City of Bainbridge Island trail pages
  - Bainbridge Island Land Trust preserve pages (read in a browser; automated fetches get 403, so record "not checked" rather than scraping around it)
  - trailhead signage text quoted on those pages

  Only a length stated for **that trail** counts. A park total ("2.5 miles of trails") is not a trail length; note it in the research log, not in the overrides. AllTrails/Trailforks may be consulted to cross-check, never as the source.
- [ ] **Step 2:** Write the findings to `.superpowers/sdd/<this-plan>/official-mileage.md` (local, gitignored), one row per trail:
  - trail id
  - found length or "none found"
  - URL
  - exact quoted text
- [ ] **Step 3:** Add an override for each trail with a found per-trail length. Set `official_length_source` to the URL. Convert km to mi if needed, rounded to 2 decimals.
- [ ] **Step 4:** `uv run bitrails build && uv run bitrails dist` succeeds. In the summary's "Official vs computed" table, every ⚠ row gets a one-line note in the research log (wrong trail matched? the agency counts an out-and-back?).
- [ ] **Step 5: Commit** `data: official mileage for N trails from agency pages`. Put N, and how many were not found, in the commit body. An honest result of "0 found" is acceptable: commit the sources.yaml note and stop.

---

## Coverage track (Kotlin)

All code is in package `com.bainbridgetrails.coverage`, module `app/coverage`, `commonMain`. Tests are in `commonTest` and run with `:coverage:jvmTest`.

### Task 4: `:coverage` module + geometry primitives

**Files:**
- Modify: `app/gradle/libs.versions.toml` (plugins `kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }`, `kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }`)
- Modify: `app/build.gradle.kts` (both plugins `apply false`), `app/settings.gradle.kts` (`include(":coverage")`)
- Create: `app/coverage/build.gradle.kts`. Targets: `jvm()`, `android { namespace = "com.bainbridgetrails.coverage" }` via `androidKotlinMultiplatformLibrary`, `iosArm64()`, `iosSimulatorArm64()`. Dependency: `kotlinx-serialization-json`. Test: `kotlin-test`.
- Create: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/Geometry.kt`
- Test: `app/coverage/src/commonTest/kotlin/com/bainbridgetrails/coverage/GeometryTest.kt`

**Interfaces (Produces):**
```kotlin
data class Xy(val x: Double, val y: Double)
class LocalProjection(originLon: Double = -122.53, originLat: Double = 47.63) {
    fun toXy(lon: Double, lat: Double): Xy        // metres, equirectangular about origin; R = 6_371_008.8
}
fun distanceToSegment(p: Xy, a: Xy, b: Xy): Double
fun bearingDeg(a: Xy, b: Xy): Double             // 0..360, 0 = north (+y)
fun axialDiffDeg(b1: Double, b2: Double): Double // undirected difference, 0..90
/** Points every [stepM] along [line] at the centre of each step: offsets stepM/2, 3stepM/2, ...; always ≥1 point. */
fun resample(line: List<Xy>, stepM: Double): List<Tick>
data class Tick(val p: Xy, val bearingDeg: Double, val lengthM: Double) // lengthM: share of the line this tick stands for
```

- [ ] **Step 1: Failing tests** in `GeometryTest`:
  - `projectionDistanceMatchesHaversine`: (−122.55, 47.60) to (−122.50, 47.68) differs from haversine by < 0.1%.
  - `distanceToSegmentEndpointsAndInterior`: a perpendicular foot inside the segment gives the perpendicular distance; outside it gives the endpoint distance.
  - `axialDiffIgnoresDirection`: `axialDiffDeg(10.0, 190.0) == 0.0`, `axialDiffDeg(0.0, 100.0) == 80.0`.
  - `resampleCountAndLengths`: a 23 m line at step 5 gives 5 ticks whose `lengthM` sums to 23.0 (±1e-9).
  - `resampleShortLineOneTick`: a 2 m line gives 1 tick with `lengthM == 2.0`.
- [ ] **Step 2:** `cd app && ./gradlew :coverage:jvmTest` → FAIL (compile errors count).
- [ ] **Step 3: Implement.** `resample`: n = max(1, ceil(L/stepM)); each tick gets `lengthM = L/n`, positioned at (i+0.5)·L/n along the line, with the bearing of the vertex segment it falls on.
- [ ] **Step 4:** `./gradlew :coverage:jvmTest :coverage:compileKotlinIosSimulatorArm64` → PASS / BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(coverage): KMP coverage module with geometry primitives`

### Task 5: Fix filtering (anti-cheat)

**Files:**
- Create: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/Fixes.kt`
- Create: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/CoverageParams.kt`
- Test: `app/coverage/src/commonTest/kotlin/com/bainbridgetrails/coverage/FixFilterTest.kt`

**Interfaces (Produces):**
```kotlin
data class Fix(val timeMs: Long, val lon: Double, val lat: Double, val accuracyM: Double? = null, val isMock: Boolean = false)
data class CoverageParams(
    val bufferM: Double = 20.0,
    val tickM: Double = 5.0,
    val headingToleranceDeg: Double? = 45.0,   // null = no heading check
    val maxAccuracyM: Double = 35.0,
    val maxSpeedMps: Double = 4.0,
    val speedWindowS: Double = 30.0,
    val maxGapS: Double = 60.0,
    val maxJumpM: Double = 100.0,
    val completionThreshold: Double = 0.90,
    val nearlyDoneThreshold: Double = 0.75,
)
/** Continuous runs of accepted fixes, in time order. Lines are drawn only within a piece. */
fun filterFixes(fixes: List<Fix>, params: CoverageParams, proj: LocalProjection): List<List<Xy>>
```

Rules, in this order:
1. Sort by `timeMs`. Drop exact-duplicate timestamps (keep the first).
2. Drop a fix if `isMock`, or if `accuracyM > maxAccuracyM`.
3. Break the piece between consecutive kept fixes when the time gap is > `maxGapS` or the distance is > `maxJumpM`.
4. Within a piece, for fix i take j = the latest fix with tᵢ − tⱼ ≥ `speedWindowS`. If no such j exists, use the piece's first fix, provided at least 10 s has elapsed. If the speed from j to i is > `maxSpeedMps`, drop fix i and break the piece there.
5. Drop pieces with fewer than 2 fixes.

- [ ] **Step 1: Failing tests:**
  - `walkKept`: 1.3 m/s for 5 min at 1 Hz → 1 piece, 300 points.
  - `driveDropped`: 13 m/s for 2 min → 0 pieces.
  - `bikeDropped`: 6 m/s → 0 pieces.
  - `runKept`: 3.5 m/s → 1 piece.
  - `jumpBreaks`: a walk with a 300 m teleport mid-way → 2 pieces.
  - `gapBreaks`: a 90 s gap → 2 pieces.
  - `mockAndInaccurateDropped`: every other fix `isMock` → those fixes are absent; `accuracyM = 50` → absent.
  - `unsortedAndDuplicateFixes`: a shuffled walk with 10 duplicated timestamps gives a result equal to the clean sorted input.
  - `sparseFixesStayOnePiece`: one fix every 20 s at 1.3 m/s → 1 piece.
  - `singleSpikeOnWalk`: one fix 60 m off-line in a walk → result has ≤ 2 pieces, total points ≥ 295. It must not wipe out the walk.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement** `filterFixes`.
- [ ] **Step 4:** `./gradlew :coverage:jvmTest` → PASS.
- [ ] **Step 5: Commit** `feat(coverage): GPS fix filtering (speed, jump, gap, mock, accuracy)`

### Task 6: Segment index + coverage state

**Files:**
- Create: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/SegmentIndex.kt`
- Create: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/CoverageState.kt`
- Test: `app/coverage/src/commonTest/kotlin/com/bainbridgetrails/coverage/CoverTest.kt`

**Interfaces:**
- Consumes: Task 4 `resample`/`Tick`/`distanceToSegment`/`axialDiffDeg`; Task 5 `filterFixes` and `CoverageParams`.
- Produces:
```kotlin
@Serializable data class SegmentGeom(val id: String, @SerialName("length_m") val lengthM: Double, val included: Boolean,
    @SerialName("trail_ids") val trailIds: List<String>, val coords: List<List<Double>>)   // matches coverage_segments.json
class SegmentIndex(segments: List<SegmentGeom>, params: CoverageParams, proj: LocalProjection = LocalProjection()) {
    val segments: Map<String, SegmentGeom>
    fun tickCount(segmentId: String): Int
    fun tickLengthM(segmentId: String): Double
    fun cover(pieces: List<List<Xy>>): CoverageState
    fun coverFixes(fixes: List<Fix>): CoverageState     // filterFixes + cover
}
/** Per-segment covered-tick bitsets. Immutable; union = OR. Serialized form is the sync/storage contract. */
@Serializable class CoverageState(/* segmentId -> LongArray bits */) {
    fun covered(segmentId: String): Set<Int>
    fun coveredM(index: SegmentIndex, segmentId: String): Double
    fun union(other: CoverageState): CoverageState
    fun encode(): String                                  // JSON {"seg-000001":"<base64 of little-endian bits>"}
    companion object { fun decode(s: String): CoverageState; val EMPTY: CoverageState }
}
```

Cover rule: a tick is covered by the line from p₁ to p₂ of a piece when
- `distanceToSegment(tick.p, p1, p2) ≤ bufferM`, and
- either `headingToleranceDeg == null`, or `|p1p2| < 2 m`, or `axialDiffDeg(bearing(p1,p2), tick.bearingDeg) ≤ headingToleranceDeg`.

Grid index: 50 m cells over tick positions. Each line queries the cells overlapping its bbox expanded by `bufferM`.

- [ ] **Step 1: Failing tests**, using synthetic segments built in the test (a local helper turns metres into lon/lat via the inverse projection):
  - `straightWalkCoversSegment`: a 200 m segment, a walk along it with no noise → 100% covered.
  - `parallelNeighborNotCredited`: segments A and B parallel, 12 m apart, each 200 m. Walk A with σ = 5 m noise (seeded `Random(1)`). A ≥ 90%. B < 50% with heading on (the noise makes B partly covered; this documents the limit). Assert both numbers and print them.
  - `perpendicularCrossingCreditsLittle`: walk across a segment at 90° → covered ≤ 2·bufferM/tickM + 1 ticks with heading off, and 0 with heading on.
  - `stationaryJitterBounded`: 600 fixes at 1 Hz within σ = 8 m of a junction of 3 segments → each segment's covered length ≤ 2·bufferM + tickM.
  - `sparseFixesInterpolate`: fixes every 20 s along a straight segment → ≥ 95% covered.
  - `repeatAndReverseIdempotent`: walk forward, then backward → state equals forward-only; `union` of the two states equals each.
  - `encodeDecodeRoundTrip` and `unionAccumulatesAcrossSessions`: first half and second half as separate sessions; the union equals the whole-walk state.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :coverage:jvmTest :coverage:compileKotlinIosSimulatorArm64` → PASS.
- [ ] **Step 5: Commit** `feat(coverage): segment tick index and accumulating coverage state`

### Task 7: Progress (trail %, island %, status, display length)

**Files:**
- Create: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/Progress.kt`
- Create: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/Dataset.kt` (parses both JSON files)
- Test: `app/coverage/src/commonTest/kotlin/com/bainbridgetrails/coverage/ProgressTest.kt`

**Interfaces:**
- Consumes: `SegmentIndex`, `CoverageState`, `CoverageParams`.
- Produces:
```kotlin
@Serializable data class TrailMeta(val id: String, val name: String,
    @SerialName("segment_ids") val segmentIds: List<String>,
    @SerialName("computed_length_mi") val computedLengthMi: Double,
    @SerialName("official_length_mi") val officialLengthMi: Double? = null) {
    val displayLengthMi: Double get() = officialLengthMi ?: computedLengthMi
}
class Dataset(val trails: List<TrailMeta>, val islandTotalM: Double, val segments: List<SegmentGeom>) {
    companion object { fun parse(trailsJson: String, coverageSegmentsJson: String): Dataset } // ignoreUnknownKeys; versions must match else IllegalArgumentException
}
enum class TrailStatus { NOT_STARTED, PARTWAY, NEARLY_DONE, COMPLETE }
class Progress(dataset: Dataset, index: SegmentIndex, params: CoverageParams) {
    fun trailFraction(state: CoverageState, trailId: String): Double     // covered m / trail m, 0..1
    fun status(fraction: Double): TrailStatus  // 0 covered → NOT_STARTED; ≥ completionThreshold → COMPLETE; ≥ nearlyDoneThreshold → NEARLY_DONE; else PARTWAY
    fun islandFraction(state: CoverageState): Double                     // covered m on included segments / islandTotalM
    fun trailsCompleted(state: CoverageState): Int
    fun trailsOn(state: CoverageState, segmentId: String): List<String>  // every trail using a covered segment (overlaps shown together)
}
```
`NOT_STARTED` means no covered tick at all. Any covered tick at all means at least `PARTWAY`.

- [ ] **Step 1: Failing tests:**
  - `sharedSegmentCountsForBothTrails`: trails T1 = [s1, s2] and T2 = [s2, s3]; covering s2 raises both.
  - `islandCountsSharedSegmentOnce`: island covered m = the sum over unique included segments.
  - `excludedSegmentNotInIsland`: covering an `included=false` segment in a trail → the trail % rises and the island % does not.
  - `statusThresholds`: fractions 0.0, 0.01, 0.74, 0.75, 0.899, 0.90 → NOT_STARTED, PARTWAY, PARTWAY, NEARLY_DONE, NEARLY_DONE, COMPLETE.
  - `displayLengthPrefersOfficial`
  - `parseRejectsVersionMismatch`
  - `realDatasetParses`: if `../../data/dist/coverage_segments.json` exists (relative to the module dir; skip with a println when it's missing), parse it with `trails.json`. Assert 123 trails, and that the included-segment length sum equals `islandTotalM` ±1 m.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :coverage:jvmTest` → PASS.
- [ ] **Step 5: Commit** `feat(coverage): trail and island progress with status and display length`

### Task 8: `:coverage-eval` — simulator, GPX reader, sweep runner

**Files:**
- Modify: `app/settings.gradle.kts` (`include(":coverage-eval")`)
- Modify: `.gitignore` (`data/tracks/private/`)
- Create: `app/coverage-eval/build.gradle.kts` (`kotlinJvm` + `application`, mainClass `com.bainbridgetrails.coverage.eval.MainKt`, depends on `project(":coverage")`)
- Create: `app/coverage-eval/src/main/kotlin/com/bainbridgetrails/coverage/eval/{Simulator.kt,Gpx.kt,Sweep.kt,Main.kt}`
- Test: `app/coverage-eval/src/test/kotlin/com/bainbridgetrails/coverage/eval/{SimulatorTest.kt,GpxTest.kt}`

**Interfaces:**
- Consumes: `Dataset`, `SegmentIndex`, `Progress`, `CoverageParams`, `Fix`.
- Produces:
```kotlin
data class NoiseModel(val sigmaM: Double, val rho: Double = 0.95, val outlierRate: Double = 0.01, val outlierM: Double = 50.0)
class Simulator(dataset: Dataset, seed: Long) {
    /** Walk every segment of the trail, one session per segment, in a seeded random direction; 1.3 m/s ± 0.2. */
    fun walkTrail(trailId: String, noise: NoiseModel, intervalS: Double, speedMps: Double = 1.3): List<Fix>
    fun traverse(segmentIds: List<String>, noise: NoiseModel, intervalS: Double, speedMps: Double): List<Fix> // used for drive/bike scenarios
}
fun readGpx(xml: String): List<Fix>      // <trkpt lat lon><time>; ISO-8601 → epoch ms; points without <time> are rejected with IllegalArgumentException
data class SweepRow(val bufferM: Double, val headingOn: Boolean, val sigmaM: Double, val intervalS: Double,
    val p5TrueFraction: Double, val meanTrueFraction: Double, val completeRate: Double,
    val falseCreditRatio: Double, val falseCompletes: Int, val driveCreditM: Double)
fun sweep(dataset: Dataset, buffers: List<Double>, sigmas: List<Double>, intervals: List<Double>, seeds: Int): List<SweepRow>
```
- The noise is a correlated error: on each axis, eₜ = ρ·eₜ₋₁ + √(1−ρ²)·σ·N(0,1). With probability `outlierRate`, a fix is displaced by `outlierM` in a random direction. `accuracyM = 1.5·σ`.
- Metrics, per (params, σ, interval), over all trails × `seeds`:
  - **true fraction**: the walked trail's fraction (p5 and mean)
  - **completeRate**: the share of walks reaching `completionThreshold`
  - **falseCreditRatio**: covered metres on segments not in the walked trail ÷ walked trail metres
  - **falseCompletes**: count of trails sharing no segment with the walked trail that reach COMPLETE
  - **driveCreditM**: covered metres from traversing all `multi_use_path` segments at 13 m/s (expected 0)
- CLI:
  - `./gradlew :coverage-eval:run --args="sweep --data ../data/dist --out ../docs/spikes/coverage/sweep.csv"`
  - `./gradlew :coverage-eval:run --args="gpx --data ../data/dist ../data/tracks/private/*.gpx"`: prints island %, then every trail with fraction > 0 (name, %, status), plus filter stats (kept/dropped fixes by reason).

- [ ] **Step 1: Failing tests:**
  - `simulatorIsDeterministic`: same seed → identical fixes.
  - `zeroNoiseWalkCompletes`: with σ = 0 and outliers 0, every trail's fraction is ≥ 0.99. Run it on a 3-trail synthetic `Dataset`.
  - `gpxParsesTrkpts`: a 3-point GPX string → 3 fixes with correct epoch ms.
  - `gpxWithoutTimeRejected`.
- [ ] **Step 2:** `./gradlew :coverage-eval:test` → FAIL.
- [ ] **Step 3: Implement.** The sweep runs trails in parallel with `java.util.stream` parallel or coroutines. Seeds derive from (trail index, seed index).
- [ ] **Step 4:** `./gradlew :coverage-eval:test` → PASS. Then a smoke run:
  ```sh
  ./gradlew :coverage-eval:run --args="sweep --data ../data/dist --out /tmp/s.csv --buffers 20 --sigmas 10 --intervals 1 --seeds 1"
  ```
  It writes one CSV row with no exceptions.
- [ ] **Step 5: Commit** `feat(coverage-eval): track simulator, GPX reader, parameter sweep`

### Task 9: Run the sweep, pick parameters, write up

**Files:**
- Create: `docs/spikes/coverage/notes.md`, `docs/spikes/coverage/sweep.csv`
- Modify: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/CoverageParams.kt` (defaults = chosen values)
- Modify: spec §2 "Completion rules": replace "~90%; final threshold set by Spike 3" with the chosen value and a link to the notes.

- [ ] **Step 1:** Full sweep: buffers {10, 15, 20, 25, 30} × heading {on, off} × σ {5, 10, 15} m × interval {1, 5, 15} s × 3 seeds. Write it to `docs/spikes/coverage/sweep.csv`.
- [ ] **Step 2: Pick the parameters** with this rule, at σ = 10 m and interval = 5 s (the expected typical case under tree cover with background throttling):
  1. Keep the configs with `falseCompletes == 0`, `driveCreditM == 0` and `falseCreditRatio ≤ 0.05`.
  2. Among them, pick the smallest buffer whose `completeRate ≥ 0.95` at threshold 0.90.
  3. If none reach it, lower the threshold in 0.05 steps, down to 0.80, and re-run. If still none reach it, stop and report to the owner rather than picking.
  4. Then check the chosen config at σ = 15 m / interval = 15 s, and report its `completeRate` as the worst case.
- [ ] **Step 3:** Write `notes.md`:
  - the algorithm in ≤ 1 page
  - the chosen `CoverageParams` and why
  - the sweep table, filtered to the rows that matter
  - known limits (parallel trails, switchbacks tighter than the buffer)
  - what Spike 4 must deliver (fix interval and accuracy in background)
  - what Spike 5 receives (the `CoverageState.encode()` format)
  - the owner real-track procedure: record a walk with any GPX logger, drop it in `data/tracks/private/`, run `gpx`, compare with what was walked
- [ ] **Step 4:** Update the `CoverageParams` defaults and the spec line. Run `./gradlew :coverage:jvmTest :coverage-eval:test` → PASS.
- [ ] **Step 5: Commit** `docs(spike3): coverage algorithm, parameter sweep, chosen buffer and threshold`
- [ ] **Step 6 (owner):** The owner records 2–3 walks (one along a known pair of parallel trails, one out-and-back), runs `gpx`, and the results get appended to `notes.md`. This is a gate before v1 planning, not before branch merge.
