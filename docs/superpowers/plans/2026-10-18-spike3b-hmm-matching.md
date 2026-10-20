# Spike 3b: Connectivity-aware (HMM) map matching — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tell nearby trails apart by using how the network connects. Do not credit off-network walking. Compare against the provisional option B from Spike 3, and adopt the new matcher only if it beats B.

**Architecture:**
- **Export:** the pipeline exports **every** redistributable segment, with its `category`. Informal paths, connectors, and road shoulders become competitors in matching.
- **`TickGraph`** (new, in `:coverage` commonMain) connects the 5 m ticks along segments and across junctions, and answers bounded route-distance queries.
- **`HmmMatcher`:**
  - runs Viterbi per filtered piece over candidate ticks plus an OFF (off-network) state
  - emission: distance to the tick
  - transition: |route distance − straight distance| between consecutive observations (Newson & Krumm 2009)
  - credits only ticks along the matched route
- **Matcher choice:** `CoverageParams.matcher` picks COMPETITIVE (Spike 3) or HMM. The Spike 3 code is untouched.
- **Evaluation:** `:coverage-eval` gains a matcher dimension, HMM parameters, an off-network walking scenario, and a correlated-noise parallel-trail guard.

**Tech Stack:** Kotlin 2.4.21 KMP (jvm, android, iosArm64, iosSimulatorArm64), kotlinx-serialization 1.11.0, kotlin-test; Python `bitrails` via `uv` + pytest.

**Spec:** `docs/superpowers/specs/2026-10-18-bainbridge-trails-design.md`: §2 (trail recognition and completion), §5 (cheat protection), §8 anti-goals ("Credit given without actually walking"; "Battery drain").

**Builds on:** `spike3/coverage` (PR #3). The algorithm and option B numbers are in `docs/spikes/coverage/notes.md`.

## Global Constraints

- **Code placement:** coverage logic lives in `:coverage` `commonMain`, with no platform `expect`s. Only kotlinx-serialization is added (already in the catalog).
- **Don't break Spike 3:** the competitive matcher, `CoverageState` encoding, `Progress`, and the existing tests keep working unchanged. HMM is additive, selected by `CoverageParams.matcher`.
- **Licensing:** only `geometry_redistributable` geometry is exported. No agency geometry.
- **Privacy:** real GPS tracks are never committed (`*.gpx`, `*.fit`, `data/tracks/private/` are gitignored).
- **Contract:** the CoverageState contract is (segment id, `tickM`, fixed segment orientation) and is unchanged.
- **Denominators unchanged:** island % uses `island_total_shippable_m`. Per-trail % uses the trail's shippable segment lengths. Competitor segments (`included=false`, no trail) count toward neither.
- **Must-not-change tests:** do not edit `parallelNeighborNotCredited`. Do not change the default matcher until Task 5's decision.
- **Performance budget:** HMM on a 60 min, 1 Hz walk over the real dataset must finish in < 2 s on the JVM. That is a proxy for phone viability, since matching runs once per session end.
- **Commands:**
  - Python: `cd pipeline && uv run pytest -q`
  - Kotlin: `cd app && ./gradlew :coverage:jvmTest :coverage-eval:test :coverage:compileKotlinIosSimulatorArm64`

## Review Focus

1. **T-junction mid-segment.** A path that meets another segment's interior, not its endpoint, must connect. Otherwise a walker turning there is forced OFF and loses credit. Covered by Task 2 test `tJunctionConnects`.
2. **Small gaps in the network.** Two segments whose ends are 1–3 m apart in the data must connect, but parallel trails 12 m apart must not. Covered by Task 2 tests `nearEndsConnect` and `parallelNotConnected`.
3. **GPS drift onto a parallel trail mid-walk** (correlated 9 m drift for 30 s). The neighbor must stay ≤ 1 tick. Covered by Task 3 test `driftTowardParallelNotCredited`.
4. **Walking a non-trail path beside a trail** (road shoulder 15 m away). It must credit the competitor and not the trail. Covered by Task 3 test `competitorPathWinsOverTrail`.
5. **Standing still / slow jitter.** It must not creep credit along a segment. Covered by Task 3 test `stationaryJitterBounded` (≤ 2 ticks per segment).

---

### Task 1: Export every redistributable segment, with category

**Files:**
- Modify: `pipeline/src/bitrails/dist.py` (`coverage_segments_doc` selection + `category` field)
- Modify: `data/dist/README.md` (coverage_segments row: all segments, category, size)
- Modify: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/SegmentIndex.kt` (`SegmentGeom.category: String? = null`)
- Modify: `app/coverage-eval/src/main/kotlin/com/bainbridgetrails/coverage/eval/{Sweep.kt,Main.kt}` (`vehicleSegmentIds` reads `SegmentGeom.category`; drop the `segments.tiles.geojson` read)
- Test: `pipeline/tests/test_dist.py`, `app/coverage/src/jvmTest/.../RealDatasetTest.kt`

**Interfaces:**
- Produces:
  - `coverage_segments.json` segments now include every `geometry_redistributable` segment (7,537 today), each with `"category": "<category>"`.
  - The `included` / `trail_ids` semantics are unchanged; competitors have `included=false, trail_ids=[]`.
  - Orientation canonicalisation is unchanged.
  - `SegmentGeom(..., category: String? = null)` uses JSON key `category`.

- [ ] **Step 1: Failing tests.**
  - `test_coverage_segments_exports_competitors`: a feature with `included=False, trail_ids=[]` is now present with its category.
  - Update `test_coverage_segments_selects_included_or_trail_segments` to the new rule ("all redistributable").
  - Kotlin `RealDatasetTest`:
    - segment count equals the count of redistributable features
    - the included-length sum still equals `island_total_shippable_m` ±1 m
    - every segment has a non-null category
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.** `vehicleSegmentIds(dataset)` filters on `category == "multi_use_path"`. Remove the `categories` parameter and its CLI plumbing.
- [ ] **Step 4:** run `uv run pytest -q && uv run bitrails dist`, then the Kotlin tests → all pass. Record the new file size in the README.
- [ ] **Step 5: Commit** `feat(dist): export all redistributable segments with category as matching competitors`

### Task 2: `TickGraph` — tick connectivity and bounded route distance

**Files:**
- Modify: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/SegmentIndex.kt` (expose `internal` read access to ticks and the grid query; no behavior change)
- Create: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/TickGraph.kt`
- Test: `app/coverage/src/commonTest/kotlin/com/bainbridgetrails/coverage/TickGraphTest.kt`

**Interfaces:**
- Consumes: `SegmentIndex` ticks (positions per segment, in order).
- Produces:
```kotlin
/** Node = (segment slot, tick index), packed as Long like the index grid. */
class TickGraph(index: SegmentIndex, junctionM: Double = 3.0) {
    fun neighbors(node: Long): List<Pair<Long, Double>>          // (node, metres)
    /** Shortest route metres from [from] to every node within [maxM]; Dijkstra, bounded. */
    fun routesFrom(from: Long, maxM: Double): Map<Long, Double>
    fun node(segmentId: String, tick: Int): Long
}
```
- **Edges:**
  - Within a segment, ticks i and i+1 are joined, weighted by their distance.
  - **End joins:** the first and last tick of a segment join every other segment's first or last tick when the segment endpoints (the coords' first/last points) are within `junctionM`. The weight is the distance between the ticks.
  - **T-joins:** a segment endpoint that lies within `junctionM` of another segment's polyline interior joins the nearest tick of that segment.
  - The graph is undirected.

- [ ] **Step 1: Failing tests** (synthetic segments, built in metres and converted with `LocalProjection.toLonLat`):
  - `withinSegmentChain`: a 100 m segment; the route from tick 0 to the last tick ≈ 90 m ±1.
  - `endpointsJoin`: two collinear 100 m segments sharing an endpoint; the route from the first tick of A to the last tick of B ≈ 195 m ±2.
  - `nearEndsConnect`: as above with a 2.5 m gap; they are connected.
  - `parallelNotConnected`: two parallel segments 12 m apart and not joined at the ends; `routesFrom(A0, 500.0)` contains no B node.
  - `tJunctionConnects`: segment C ends 1 m from the middle of A; the route from C's end tick to A's middle tick is < 10 m.
  - `boundedSearch`: `routesFrom(node, 20.0)` returns no node farther than 20 m.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.** Find joins with the existing 50 m grid. Use a binary heap for Dijkstra; write it by hand, since `java.util.PriorityQueue` is not available in commonMain.
- [ ] **Step 4:** run → PASS, including the iOS compile.
- [ ] **Step 5: Commit** `feat(coverage): tick connectivity graph with bounded routing`

### Task 3: `HmmMatcher` + matcher selection

**Files:**
- Create: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/HmmMatcher.kt`
- Modify: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/CoverageParams.kt` (new fields below)
- Modify: `app/coverage/src/commonMain/kotlin/com/bainbridgetrails/coverage/SegmentIndex.kt` (`coverFixes` dispatches on `params.matcher`)
- Test: `app/coverage/src/commonTest/kotlin/com/bainbridgetrails/coverage/HmmMatcherTest.kt`, `app/coverage/src/jvmTest/.../HmmPerfTest.kt`

**Interfaces:**
- Consumes: `SegmentIndex`, `TickGraph`, `filterFixes` pieces (`List<List<Xy>>`), `CoverageState`.
- Produces:
```kotlin
enum class Matcher { COMPETITIVE, HMM }
// CoverageParams additions, appended after nearlyDoneThreshold:
val matcher: Matcher = Matcher.COMPETITIVE,
val hmmStepM: Double = 10.0,          // observation spacing along each piece
val emissionSigmaM: Double = 10.0,
val transitionBetaM: Double = 5.0,
val offNetworkM: Double = 25.0,       // OFF emission = emission at this distance
val offSwitchPenalty: Double = 3.0,   // nats, charged entering or leaving OFF
val junctionM: Double = 3.0,
val maxCandidates: Int = 8,           // nearest distinct segments per observation

class HmmMatcher(index: SegmentIndex, graph: TickGraph, params: CoverageParams) {
    fun cover(pieces: List<List<Xy>>): CoverageState
}
```
- **Observations:** each piece is resampled every `hmmStepM` of path length (first and last points included). A piece shorter than `headingBaselineM` is stationary: no credit, consistent with Spike 3.
- **Candidates:** per observation, the nearest tick of each segment within `bufferM`, keeping up to `maxCandidates` segments by distance, plus OFF.
- **Log emission:** −d²/(2·σ²). OFF = −offNetworkM²/(2·σ²).
- **Log transition** between candidates u → v:
  - −|route(u, v) − g| / β, where g is the straight distance between the two observations.
  - route is taken from `graph.routesFrom(u, 2·g + 50)`. A missing route means the transition is impossible.
  - OFF→OFF: −0.
  - candidate ↔ OFF: −offSwitchPenalty.
- **Viterbi** in log space. Ties go to the lower candidate index, so results are deterministic.
- **Crediting:** for consecutive on-network path states u → v, mark every tick on the shortest route between them; reconstruct it from the Dijkstra predecessors (keep predecessor maps). A single on-network observation between OFF states marks its own tick only.
- **Dispatch:** `SegmentIndex.coverFixes` uses `HmmMatcher` when `params.matcher == HMM`. The graph is built lazily once per index.

- [ ] **Step 1: Failing tests** (synthetic, seeded):
  - `straightWalkCovers`: 200 m segment, no noise → ≥ 0.98.
  - `driftTowardParallelNotCredited`: A and B parallel, 12 m apart, joined at both ends by 12 m cross segments. Walk A at 1 Hz with a +9 m sideways drift (toward B) held for 30 s mid-walk. Assert A ≥ 0.95 and B ≤ 1 tick. Also assert that the COMPETITIVE matcher credits B more than 5 ticks on the same input; this documents why HMM exists.
  - `turnAtJunctionCredited`: walk A to the junction, then turn onto C → both covered ≥ 0.9 along the walked parts.
  - `competitorPathWinsOverTrail`: trail T plus competitor path P (category `connector`, `included=false`) 15 m apart, unconnected. Walk P → T ≤ 1 tick and P ≥ 0.9.
  - `offNetworkCreditsNothing`: a walk 40 m from any segment → empty state.
  - `stationaryJitterBounded`: 10 min at 1 Hz, σ 8 m, at a 3-way junction → ≤ 2 ticks per segment.
  - `repeatAndReverseIdempotent`: forward, then reverse; the union equals forward.
  - `sparseFixes`: 20 s fix interval on a straight segment → ≥ 0.95.
  - `HmmPerfTest` (jvmTest): skip with a println if `../../data/dist` is missing. Otherwise simulate a 60 min, 1 Hz walk along the longest trail's chain (straight-line interpolation, σ 5 m white) and assert `cover` takes < 2 s.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** run all Kotlin commands → PASS. `parallelNeighborNotCredited` (COMPETITIVE default) is unchanged and passing.
- [ ] **Step 5: Commit** `feat(coverage): HMM map matcher with off-network state`

### Task 4: Eval — matcher dimension, off-network scenario, correlated guard

**Files:**
- Modify: `app/coverage-eval/src/main/kotlin/com/bainbridgetrails/coverage/eval/{Sweep.kt,Main.kt,Simulator.kt}`
- Test: `app/coverage-eval/src/test/kotlin/com/bainbridgetrails/coverage/eval/{SweepMetricsTest.kt,ParallelGuardTest.kt}`

**Interfaces:**
- **`sweep(...)` gains:**
  - `matchers: List<Matcher> = listOf(Matcher.COMPETITIVE)`
  - `hmmSigmas: List<Double>`, `hmmBetas: List<Double>`, `offNetworkMs: List<Double>`
  - These three are ignored for COMPETITIVE rows, which use the defaults.
- **CLI flags:** `--matchers competitive,hmm`, `--hmm-sigmas`, `--hmm-betas`, `--off-network`.
- **`SweepRow` gains:** `matcher`, `emissionSigmaM`, `transitionBetaM`, `offNetworkM`, and `offNetCreditRatio`.
  - **CSV columns** appended in that order: `matcher,emission_sigma_m,transition_beta_m,off_network_m,off_net_credit_ratio`.
- **Off-network scenario:**
  - Pick competitor chains (`included=false`, empty `trail_ids`, category in {connector, informal}). For each chain, ≥ 50% of its ticks must be within 30 m of some trail segment's tick.
  - Walk each such chain with the row's noise.
  - `offNetCreditRatio` = covered metres on trail segments ÷ walked chain metres, pooled over chains and seeds.
- **`ParallelGuardTest`:**
  - Synthetic A/B parallel, 12 m apart, joined at the ends.
  - Correlated noise: `NoiseModel(sigmaM = 10.0, tauS = 20.0, outlierRate = 0.0)`, 5 seeds, 5 s interval.
  - Report the mean B fraction for COMPETITIVE (option B params) and for HMM (defaults).
  - Assert HMM mean B < COMPETITIVE mean B, and HMM mean A ≥ 0.85.

- [ ] **Step 1: Failing tests:**
  - `ParallelGuardTest` (above).
  - `offNetScenarioMeasuresTrailCredit`: a synthetic trail with a competitor 15 m away. COMPETITIVE gives `offNetCreditRatio` > 0.5 (documents the gap). HMM gives < 0.1.
  - `matcherDimensionRowsAndCsv`: 2 matchers × 1 → 2 rows. The CSV has the new columns, empty HMM fields for COMPETITIVE.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3: Implement.** The HMM index and graph are built once per parameter set, the same as today's index reuse.
- [ ] **Step 4:** run → PASS. Smoke-run the medium grid and record the wall time in the commit body:
  `--matchers competitive,hmm --buffers 30 --headings off --margins 1 --smooth 10 --sigmas 10 --intervals 5 --seeds 1`
- [ ] **Step 5: Commit** `feat(coverage-eval): matcher dimension, off-network scenario, correlated parallel guard`

### Task 5: Sweep, decide, write up

**Files:**
- Modify: `docs/spikes/coverage/notes.md` (new section "Spike 3b: HMM matching")
- Create: `docs/spikes/coverage/sweep-3b.csv`
- Modify (only if HMM wins): `CoverageParams.kt` defaults (`matcher = HMM` plus the chosen HMM values); spec §2 line (append "matcher: HMM, Spike 3b")

- [ ] **Step 1: Sweep at threshold 0.85, written to `sweep-3b.csv`.**
  - **COMPETITIVE rows:** option B only (30/off/1/10).
  - **HMM rows:**
    - buffers {20, 30} × emissionSigmaM {5, 10, 15} × transitionBetaM {3, 5, 10} × offNetworkM {20, 30}
    - smoothing {0, 10}, heading off
  - **Both:** σ {5, 10, 15} × intervals {1, 5, 15} × 3 seeds.
- [ ] **Step 2: Decision rule** (at σ10/5 s, threshold 0.85). HMM is adopted when one HMM config has all of:
  - `completeRate ≥ 0.839` (option B's)
  - `falseCompletes < 3`
  - `falseCreditRatio < 0.066`
  - `offNetCreditRatio` ≤ half of option B's
  - `ParallelGuardTest` still passing

  Among qualifying configs, pick the highest `completeRate`; break ties by lowest `falseCreditRatio`. If none qualifies: keep option B, record the best HMM row and why it lost, and make no default change.
- [ ] **Step 3: Notes** (≤ 1.5 pages). Cover:
  - the algorithm
  - the parameters
  - an A / B / C / HMM comparison table at σ10/5 s and σ15/15 s
  - option B's `offNetCreditRatio`, which is now measurable
  - the HMM runtime from `HmmPerfTest`
  - the decision
  - the remaining limits
  - what Spike 4 changes: activity gating, and a step-count cross-check that adds a second anti-cheat signal
- [ ] **Step 4:** run all Kotlin and Python commands → PASS. If HMM won, `parallelNeighborNotCredited` must still pass on the new defaults. Do not edit it; if it fails, keep option B and record why.
- [ ] **Step 5: Commit** `docs(spike3b): HMM vs option B sweep and decision`
