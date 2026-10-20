# Spike 3: trail coverage, parameter sweep

Status: **Provisional defaults set (owner chose option B, 2026-10-18; matcher switched to HMM by Spike 3b).** The Task 9 decision rule still picks no config; defaults are option B's buffer / smoothing / threshold with the HMM matcher, see "Decision (owner, 2026-10-18)" and "Spike 3b: HMM matching". Re-check on real GPX walks before v1.

Data: `sweep.csv` (full grid, threshold 0.90, 1296 rows), `sweep-t0.85.csv` and `sweep-t0.80.csv` (σ {10, 15} × interval {5, 15}, 576 rows each, re-run for rule step 3). All simulated, no real tracks. Reproduce from `app/`:

```
./gradlew :coverage-eval:run --args="sweep --data ../data/dist --out ../docs/spikes/coverage/sweep.csv \
  --buffers 15,20,25,30 --headings off,30,45,60 --margins 0,1,3 --smooth 0,10,20 --sigmas 5,10,15 --intervals 1,5,15 --seeds 3 --threshold 0.90"
./gradlew :coverage-eval:run --args="sweep --data ../data/dist --out ../docs/spikes/coverage/sweep-t0.85.csv \
  --buffers 15,20,25,30 --headings off,30,45,60 --margins 0,1,3 --smooth 0,10,20 --sigmas 10,15 --intervals 5,15 --seeds 3 --threshold 0.85"
# same with --threshold 0.80 --out ../docs/spikes/coverage/sweep-t0.80.csv
```

Each row has 342 walks (114 trails × 3 seeds) and 162 multi-use-path vehicle chains (transitions) per seed. The full grid takes ~107 s.
CSV `bike_credit_m` and `drive_credit_m` are per-seed totals over all 162 chains (mean over seeds), not per transition; divide by 162 for the per-transition figure (option B at σ10/5 s: 7243 / 162 = 44.7 m bike, 4254 / 162 = 26.3 m drive). All "per transition" figures in this file are already divided.
Config labels below are buffer/heading/margin/smooth (m/°/m/s).

## Decision (owner, 2026-10-18)

Option B, provisional: **buffer 30 m, heading off, margin 1 m, smooth 10 s, completion threshold 0.85** (`nearlyDoneThreshold` 0.75 and `headingBaselineM` 15 unchanged; the baseline still drives the stationary rule). Now the `CoverageParams` defaults; spec §2 says 85%. Spike 3b keeps buffer 30 / smooth 10 s / threshold 0.85 and switches `matcher` to HMM (heading and margin then unused).

Options shown to the owner (30 m buffer; σ10/5 s; false-credit ratio and false completes from the sweep CSVs):

| option | config (buffer/heading/margin/smooth) | cr @0.90 | cr @0.85 | cr @0.80 | fcr | fc @0.90 / 0.85 / 0.80 |
|---|---|---|---|---|---|---|
| A strict | 30 / 30° / 1 / 20 s | 0.383 | 0.597 | 0.725 | 0.029 | 0 / 0 / 0 |
| **B balanced (chosen)** | 30 / off / 1 / 10 s | 0.754 | 0.839 | 0.898 | 0.066 | 2 / 3 / 3 |
| C generous | 30 / 60° / 3 / off | 0.868 | 0.933 | 0.944 | 0.104 | 6 / 7 / 7 |

Numbers for option B at threshold 0.85:
- **σ10/5 s:** completeRate 0.839, falseCreditRatio 0.066, falseCompletes 3 (@0.85; 2 @0.90, 3 @0.80). Drive credit ~26 m per transition. p5 true fraction 0.677 (5% of walked trails get under ~68% credit).
- **σ15/15 s (worst case):** completeRate 0.693 @0.85 (0.500 @0.90, 0.792 @0.80), falseCreditRatio 0.097, falseCompletes 1 (@0.85, @0.90 and @0.80).

It misses the rule targets (cr >= 0.95, fcr <= 0.05, fc = 0); accepted as a provisional trade-off for completing walked trails.

Next step: the owner records 2-3 GPX walks into `data/tracks/private/` (procedure at the end of this file). From `app/`, run under A, B and C, then adjust:

```
./gradlew :coverage-eval:run --args="gpx --data ../data/dist <files>"
```

Follow-up: a correlated-noise variant of the parallel guard (the current guard uses white noise, which smoothing removes almost entirely).

## Spike 3b: HMM matching

Data: `sweep-3b.csv` (threshold 0.85, 1305 rows: 9 COMPETITIVE option B rows + 1296 HMM rows). The dataset now exports all 7,537 segments; the 5,923 competitor segments (not `included`, no trail) take part in matching but their credit is never counted. Reproduce from `app/` (HMM run 373 s locally), then concatenate with one header:

```
./gradlew :coverage-eval:run --args="sweep --data ../data/dist --out comp.csv --matchers competitive --buffers 30 --headings off \
  --margins 1 --smooth 10 --sigmas 5,10,15 --intervals 1,5,15 --seeds 3 --threshold 0.85"
./gradlew :coverage-eval:run --args="sweep --data ../data/dist --out hmm.csv --matchers hmm --buffers 20,30 --smooth 0,10 \
  --hmm-sigmas 5,10,15 --hmm-betas 3,5,10 --off-network 12,15,20,30 --sigmas 5,10,15 --intervals 1,5,15 --seeds 3 --threshold 0.85"
```

**Algorithm** (`HmmMatcher`, Newson & Krumm 2009, per filtered piece). Observations every `hmmStepM` (10 m) of path. States: the nearest tick of each segment within `bufferM` (up to 8) plus one OFF (off-network) state. Emission −d²/2σ²; OFF scores as a candidate at `offNetworkM`. Transition −|route − straight| / β, with routes over the tick graph (`TickGraph`, junctions within 3 m), so a parallel trail is reachable only through a real junction. Entering or leaving OFF costs 3 nats. Viterbi; credit = matched ticks plus every tick on the route between consecutive matches. A piece with < 15 m extent credits nothing.

**New metric.** `offNetCreditRatio`: walk every connector / informal competitor chain lying ≥ 50% within 30 m of a scored segment (each its own recording); credited scored metres / walked metres. 423 chains. Option B's is now measurable: **0.255 at σ10/5 s, 0.358 at σ15/15 s**.

**Decision rule** (controller R1: against option B re-measured in this sweep, σ10/5 s, threshold 0.85). Adopt HMM if one config has cr ≥ B's, fc < B's, fcr < B's and offNet ≤ ½ B's; pick max cr, tie → min fcr. Re-measured B: cr 0.822, fcr 0.065, fc 2, offNet 0.255 (Spike 3 numbers were cr 0.839, fcr 0.066, fc 3; the drop comes from competitors now taking candidates). **2 of 144 HMM configs qualify; HMM adopted**: buffer 30, smooth 10 s, emissionSigmaM 15, transitionBetaM 10, offNetworkM 30. Runner-up: same with σe 10 (cr 0.827, fcr 0.027). The highest-cr HMM configs are smooth 0 (cr up to 0.883): all 16 with cr ≥ 0.822 fail on offNet (0.180–0.216 > ½ B = 0.1275); 9 of them also fail fc (fc ≥ 2).

| matcher / option (buffer/heading/margin/smooth) | σ10/5 s cr | fcr | fc | offNet | σ15/15 s cr | fcr | fc | offNet |
|---|---|---|---|---|---|---|---|---|
| A 30/30°/1/20 (Spike 3) | 0.597 | 0.029 | 0 | n/a | 0.102 | 0.072 | 1 | n/a |
| B 30/off/1/10 (Spike 3) | 0.839 | 0.066 | 3 | n/a | 0.693 | 0.097 | 1 | n/a |
| C 30/60°/3/0 (Spike 3) | 0.933 | 0.104 | 7 | n/a | 0.705 | 0.107 | 2 | n/a |
| B re-measured (3b) | 0.822 | 0.065 | 2 | 0.255 | 0.614 | 0.096 | 1 | 0.358 |
| **HMM 30/10 s, σe 15, β 10, off 30 (chosen)** | **0.830** | **0.026** | **1** | **0.119** | **0.667** | **0.050** | **1** | **0.215** |

All @0.85. A and C were not re-measured; their Spike 3 numbers predate the competitor segments.

**Re-measured B vs chosen HMM, all 9 noise cells** (@0.85, 342 walks per cell; bold = better of the two):

| σ / interval | B cr | HMM cr | B fcr | HMM fcr | B fc | HMM fc | B offNet | HMM offNet |
|---|---|---|---|---|---|---|---|---|
| σ5 / 1 s | **0.962** | 0.950 | 0.038 | **0.010** | 1 | **0** | 0.189 | **0.064** |
| σ5 / 5 s | **0.947** | 0.930 | 0.038 | **0.010** | 0 | 0 | 0.181 | **0.087** |
| σ5 / 15 s | **0.939** | 0.924 | 0.047 | **0.016** | 0 | 0 | 0.219 | **0.095** |
| σ10 / 1 s | 0.845 | **0.851** | 0.066 | **0.025** | 1 | **0** | 0.269 | **0.111** |
| σ10 / 5 s | 0.822 | **0.830** | 0.065 | **0.026** | 2 | **1** | 0.255 | **0.119** |
| σ10 / 15 s | 0.787 | **0.816** | 0.071 | **0.031** | 1 | **0** | 0.295 | **0.165** |
| σ15 / 1 s | 0.646 | **0.675** | 0.096 | **0.052** | **1** | 2 | 0.339 | **0.181** |
| σ15 / 5 s | 0.646 | **0.708** | 0.090 | **0.046** | **2** | 3 | 0.304 | **0.176** |
| σ15 / 15 s | 0.614 | **0.667** | 0.096 | **0.050** | 1 | 1 | 0.358 | **0.215** |

Where B beats HMM: **cr at σ5** (all three intervals, by 0.012–0.017) and **fc at σ15/1 s and σ15/5 s** (1 vs 2, 2 vs 3). HMM wins fcr and offNet in every cell. The margins at the decision cell are thin: the **fc win is 1 walk of 342** (1 vs 2), and the **offNet margin is 0.119 against the ½ B limit of 0.1275**. The runner-up (σe 10) sits at 0.123. Chosen config, σ10/5 s: mean true fraction 0.906 (B 0.896), p5 0.600 (B 0.598), stand credit 25 m (B 32 m). σ5/1 s: cr 0.950, fcr 0.010, fc 0.

**offNetworkM trade-off (R6)**, 30/10 s/σe 15/β 10 at σ10/5 s:

| offNetworkM | cr | fcr | fc | offNet |
|---|---|---|---|---|
| 12 | 0.146 | 0.009 | 0 | 0.068 |
| 15 | 0.447 | 0.019 | 1 | 0.100 |
| 20 | 0.772 | 0.025 | 1 | 0.117 |
| 30 | 0.830 | 0.026 | 1 | 0.119 |

A small `offNetworkM` makes OFF beat the trail itself under σ10 noise, so walked trails stop completing. What `offNetworkM` buys is **not measured by this metric**: `offNetCreditRatio` walks mapped competitor chains, which are themselves HMM candidates, so the matcher can already explain those walks without OFF (0.119 → 0.068 from 30 → 12 is the side effect on that case only). The case OFF exists for (walking on an unmapped road shoulder or across a field beside a trail, with no competitor to match) is unmeasured. The owner's GPX walks should include one deliberate beside-trail walk along a road.

**What the owner should know (plain terms):**
- **Beside-trail credit distance: ~30 m.** With `offNetworkM` 30 = `bufferM`, walking alongside a lone trail anywhere within 30 m of it credits the trail (`besideTrailThresholdDocumented`: 27.5 m → all 60 ticks, 30 m → 0). Only a mapped competitor path nearer than the trail (`competitorPathWinsOverTrail`) or a distance > 30 m stops it. A road shoulder or unmapped path 20 m from a trail counts as walking the trail.
- **Remaining off-network credit: 12%** of off-network walking is credited to scored segments at σ10/5 s (21% at σ15/15 s), against 25% / 36% for option B.
- **Bike and drive credit are unchanged**: ~42 m per transition riding a bike and ~24 m driving, at σ10/5 s (B: 41 m / 25 m). That is real credit for riding scored multi-use paths in the ~30 s before the 4 m/s speed check catches up; HMM does not touch it. Spike 4's activity gating is the planned fix.

**Parallel guards** (unedited assertions): `ParallelGuardTest` (correlated σ10, 12 m apart) COMPETITIVE A 0.834 / B 0.338, HMM A 0.996 / B 0.005; `parallelNeighborNotCredited` on the new defaults A 1.0 / B 0.0. Both pass. `ParallelGuardTest`'s baseline line changed from `CoverageParams()` to `CoverageParams(matcher = Matcher.COMPETITIVE)` (same option B values) so it still compares against option B now that the default is HMM.

**Runtime** (`HmmPerfTest`, **JVM only, one trail corridor**: longest trail, 60 min at 1 s): index 42 ms, tick graph 116 ms (built once, lazily), Viterbi cover 20 ms; `coverFixes` including the lazy graph 179 ms, under the 2 s budget. Heap: the tick graph retains ~28 MB (reviewer's probe; boxed `HashMap<Long, MutableList<Pair<Long, Double>>>` adjacency). The matcher keeps only route lengths per step (≤ 8 × 8, dropped after the step) and recomputes each chosen route after the backtrack; it no longer retains per-observation predecessor maps (the 60 min walk computes 6,824 route-length entries in total, where the old code retained 72,906 predecessor entries). Open items: Android / iOS heap and time in a dense area; run `coverFixes` off the main thread (its first call builds the graph); compact the graph to CSR arrays before v1.

**Privacy:** `SegmentIndex.coverFixes` returns scored segments only (`SegmentGeom.isScored`: included, or on a trail). Competitor credit is used inside matching and never leaves it, so a stored or synced `CoverageState` never names a competitor segment. Raw `cover` / `HmmMatcher.cover` stay unfiltered for the eval, whose metrics count scored segments only.

**Limits that remain:**
- beside-trail credit within 30 m (above). **Owner sign-off needed**: `offNetworkM` 30 = `bufferM`, so any walk within 30 m of a lone trail credits it;
- vehicle credit per transition (above);
- σ15/15 s still collapses (cr 0.667);
- parallel trails joined by frequent junctions are untested (topology cannot separate them);
- single-segment switchbacks may credit the unwalked apex: each segment has one candidate tick per observation, so a route between two legs of the same segment runs through the apex ticks;
- crossing trails rely on the pipeline splitting segments at crossings (not verified); an unsplit X-crossing has no junction in the tick graph;
- real-GPS validation still pending (owner's GPX walks, same procedure as below).

## Tiny-trail fold (Task 9b)

`bitrails dist` now merges trails under 30 m into the longest touching trail (segments within 1 m), or hides them when nothing touches them. Segments and island totals are unchanged. 123 → 114 trails.

| trail | folded into |
|---|---|
| Allan's Alley | hidden |
| Dock Street road end | hidden |
| Fairview Avenue Path | hidden |
| Farm Trail | Farm Trail South |
| Fletcher Landing Road End | hidden |
| Gideon Trail | Gideon Park Trail |
| Howley Cove Trail | hidden |
| To The Learning Tree | hidden |
| Waterfront Trail | West Loop Trail |

Effect: the zero-score ceiling is gone (σ5/1 s best `completeRate` @0.90 rose from 0.940 to 0.985), and the minimum `falseCompletes` at σ10/5 s fell from 2 to 0. The remaining false completes were not re-diagnosed per trail; trails just over 30 m (e.g. Rotary Park Trails, 31 m, beside Rotary Trail) are the likely source.

## Algorithm (module `:coverage`)

1. **Segments → ticks.** Each coverage segment is resampled every `tickM` (5 m), with ticks at the step centres. Each tick has a bearing smoothed over `headingBaselineM` (15 m) along the segment. Ticks go in a 50 m grid.
2. **Fix filter** (`filterFixes`), in order:
   - sort and drop duplicate timestamps;
   - drop mock fixes and fixes with accuracy > `maxAccuracyM` (35 m) or < 0 (invalid); null accuracy (unknown) is kept;
   - drop single-fix spikes: both legs > `spikeM` (30 m) while the neighbours are < 15 m apart;
   - split into pieces on a gap > `maxGapS` (60 s) or a jump > `maxJumpM` (100 m);
   - speed check: drop a fix if its speed from the fix ≥ `speedWindowS` (30 s) earlier exceeds `maxSpeedMps` (4 m/s), and split there;
   - drop pieces with < 2 fixes;
   - **smoothing (Task 9c, R16):** within each piece, each fix moves to the mean of the piece's original fixes within ±`smoothWindowS` / 2 (default 10 s). No cascade, never across a break; a fix with no neighbour in the window is unchanged (so 15–20 s recordings are untouched at 10 s); 0 = off.
3. **Matching.** Each piece is densified every `tickM`. Each point q gets a heading from a displacement baseline ≥ `headingBaselineM`. A point with no such baseline is stationary and credits nothing.
   - Candidates are ticks within `bufferM` of q that pass the heading check (axial difference ≤ `headingToleranceDeg`; skipped when the tolerance is null).
   - Competitive matching (`matcher = COMPETITIVE`; the default is HMM since Spike 3b, see above): a segment is credited only if its nearest candidate is within d\* + `matchMarginM` of the best segment's nearest candidate d\*. It credits that tick and its ±1 neighbours (heading-checked, within the buffer).
4. **State.** `CoverageState` holds one bitset of covered ticks per segment. Union is OR, so it is order-independent and idempotent.
5. **Progress.** A trail's fraction is the covered metres over the trail's distinct segment metres. It is COMPLETE at ≥ `completionThreshold`, NEARLY_DONE at ≥ `nearlyDoneThreshold` (0.75). The island fraction is covered metres over `islandTotalM`.

## Smoothing: what it does in the sweep

- **It trades true credit for false credit; it does not move the frontier.** At σ10/5 s, heading off or 60°: `completeRate` @0.90 drops ~0.07–0.09 per 10 s of window (e.g. 30/off/1: 0.830 → 0.754 → 0.658) while `falseCreditRatio` drops ~0.02–0.03 per step (0.092 → 0.066 → 0.050).
- **At heading 30° it helps a little on both axes.** 20/30/1: cr 0.231 → 0.275 → 0.313, fcr 0.064 → 0.039 → 0.027, fc 4 → 3 → 0. But mean fraction stays ~0.81–0.83, so cr stays far below 0.95.
- **Why it cannot fix the heading-30° gap:** the simulator's noise is Gauss–Markov with τ = 20 s (R10). A 10–20 s mean barely shrinks a 20 s-correlated offset, so the 15 m baseline heading stays noisy. It mostly removes per-fix jitter, which was crediting extra ticks on both the walked and neighbouring trails.
- At σ0 it costs only ~1% (heading off cr 0.991 / 0.983 / 0.977 for 0 / 10 / 20 s): corner cutting and piece-end pull-in.
- It cuts the speed-window leak: drive credit per transition averages 36 / 25 / 19 m at 0 / 10 / 20 s (σ10/5 s grid mean).
- At σ15/15 s it changes little (cr −0.01 to −0.03): at 15 s spacing most windows hold a single fix.

## Decision trail (σ = 10 m, interval = 5 s)

**Rule 1** keeps configs with `falseCompletes == 0`, `driveCreditM` ≤ 60 m per transition (R14) and `falseCreditRatio` ≤ 0.05.
- Drive passes everywhere: 7.7–47.6 m per transition across all 1296 rows. It never binds. Bike runs 22.8–53.3 m per transition.
- 8 of 144 configs pass, at every threshold (0.90 / 0.85 / 0.80): **heading 30°, smooth 20 s, margin 0 or 1, buffer 15–30.**

**Rule 2** needs `completeRate` ≥ 0.95 among them. The best survivor, 30/30/1/20, gives 0.383 @0.90.

**Rule 3** lowers the threshold in 0.05 steps. The same 8 survive; best `completeRate` is 0.597 @0.85 and 0.725 @0.80 (30/30/1/20). **No config qualifies. Per the rule: stop, defaults and spec unchanged, report to the owner.**

Ignoring rule 1, the best `completeRate` is 0.868 / 0.933 / 0.944 at 0.90 / 0.85 / 0.80 (30/60/3/0: fcr 0.104, fc 6 / 7 / 7). Even under ideal noise (σ5/1 s, @0.90) the maximum is 0.985, so the target is reachable in principle; σ10 noise is the problem.

## Guard test A/B (`parallelNeighborNotCredited`, unedited)

Trails 12 m apart, white noise σ5, 1 s, seed 1; needs A ≥ 0.90 and B < 0.50. At the interim defaults (20/30/1, pre-decision):

| smoothWindowS | A | B | result |
|---|---|---|---|
| 0 (before) | 1.0 | 0.425 | pass |
| 10 (new default) | 1.0 | 0.0 | pass |

Unverified: a scratch probe over the whole grid (not committed, not reproducible from this repo; seeds 1–5) showed that **with any smoothing the guard passes at every buffer/heading/margin** (B ≤ 0.075), while without smoothing it passes only at heading 30° with margin ≤ 1 (B 0.40–0.45; others 0.63–0.93).

Caveat: the guard uses white noise, which a 10 s mean removes almost entirely (σ5 → ~1.5 m). Real GPS noise is correlated, so this guard now overstates how well smoothing separates parallel trails. The sweep's fcr (correlated noise) is the more honest measure: 30/off/1/10 still has fcr 0.066 and 2 false completes.

## Trade-off table (σ10/5 s unless noted; cr = completeRate, fcr = falseCreditRatio @0.90, fc = falseCompletes)

Top 5:

| config | guard (B) | cr @0.90 | cr @0.85 | cr @0.80 | fcr | fc @0.90 / 0.85 / 0.80 | mean frac | stand m | drive m/transition | worst σ15/15 s cr @0.90 / @0.80 |
|---|---|---|---|---|---|---|---|---|---|---|
| 30/30/1/20 (best rule-1 survivor) | pass (0.0) | 0.383 | 0.597 | 0.725 | **0.029** | **0 / 0 / 0** | 0.83 | 40 | 21 | 0.067 / 0.208 (fc 1) |
| 30/off/1/10 (current default, option B) | pass (0.0) | 0.754 | 0.839 | 0.898 | 0.066 | 2 / 3 / 3 | 0.91 | 32 | 26 | 0.500 / 0.792 (fc 1) |
| 30/60/3/10 | pass (0.075) | 0.810 | 0.877 | 0.909 | 0.070 | 4 / 4 / 5 | 0.93 | 36 | 27 | 0.526 / 0.807 (fc 2) |
| 30/off/3/10 | pass (0.075) | 0.789 | 0.883 | 0.915 | 0.087 | 4 / 4 / 5 | 0.92 | 32 | 27 | 0.579 / 0.830 (fc 4) |
| 30/60/3/0 (best cr) | fail (0.85) | **0.868** | **0.933** | **0.944** | 0.104 | 6 / 7 / 7 | 0.94 | 39 | 38 | 0.532 / 0.816 (fc 2) |

Reference rows:

| config | guard (B) | cr @0.90 | cr @0.85 | cr @0.80 | fcr | fc @0.90 / 0.85 / 0.80 | mean frac | stand m | drive m/transition | worst σ15/15 s cr @0.90 / @0.80 |
|---|---|---|---|---|---|---|---|---|---|---|
| 20/30/1/10 (interim defaults, pre-decision) | pass (0.0) | 0.275 | 0.520 | 0.714 | 0.039 | 3 / 3 / 3 | 0.81 | 38 | 24 | 0.044 / 0.102 (fc 1) |
| 20/30/1/0 (interim defaults, pre-decision, pre-9c) | pass (0.425) | 0.231 | 0.491 | 0.708 | 0.064 | 4 / 5 / 5 | 0.82 | 43 | 36 | 0.041 / 0.099 (fc 1) |
| 20/off/0/0 | fail (0.75) | 0.757 | 0.851 | 0.906 | 0.082 | 2 / 3 / 4 | 0.91 | 36 | 36 | 0.260 / 0.643 (fc 1) |

Worst case (σ15/15 s) fc is at threshold 0.80.

## Options for the owner (option B applied as the provisional default)

1. **Accept heading off (or 60°) + smoothing 10 s + threshold 0.80**, e.g. 30/off/1/10: cr 0.898, fcr 0.066, fc 3 (all @0.80). Guard passes (white noise only). Misses cr ≥ 0.95 and fcr ≤ 0.05, and has 3 false completes; needs a looser rule.
2. **Keep the strict anti-false-credit rule** (30/30/1/20): fc 0 and fcr 0.029, but only ~73% of walked trails complete at 0.80. Users would routinely walk a trail and not get it.
3. **Fix the heading noise properly** (still untested): a longer `headingBaselineM` (25–30 m) or a heading from a longer/smoothed track, then re-sweep heading 30°. Smoothing alone did not close the gap.
4. **Loosen the targets** (e.g. cr ≥ 0.85 @0.80, fcr ≤ 0.07, fc counted only for trails ≥ 50 m) and re-apply the rule; option 1's configs might then qualify.
5. **Fold more**: raise the fold limit from 30 m to ~50 m to remove the remaining small-trail false completes (needs a per-trail diagnosis first).

## Known limits (for any config)

- **Off-network walking** (Spike 3b): walking mapped competitor paths is now measured (`offNetCreditRatio`) and all 7,537 segments compete. HMM credits 12% of it at σ10/5 s (option B 25%). Walking where nothing is mapped (road shoulder, field) is not measured; any walk within ~30 m of a lone trail with no nearer mapped path is credited to it (owner sign-off needed). Unmapped paths cannot compete.
- **Switchbacks on one segment** (HMM): one candidate tick per segment per observation, so the route between the two legs may credit the unwalked apex.
- **Crossing trails** (HMM): the tick graph joins only endpoints, so an X-crossing is a junction only if the pipeline split the segments there (not verified).
- **Parallel trails joined by frequent junctions** (HMM): untested.
- **Parallel trails < 12 m apart.** COMPETITIVE credits neighbours under correlated noise (fcr 0.065–0.096; correlated guard B 0.338). HMM separates them through topology (guard B 0.005, fcr 0.026 at σ10/5 s) unless they share frequent junctions.
- **Hairpin switchbacks** tighter than the buffer score 0.71–0.93 even at zero noise; smoothing cuts them further.
- **Speed-window leak (R5).** About 30 s of fast travel right after walking passes the speed check: 19–36 m per transition on average (smoothing reduces it). It is never 0. HMM does not change it (~42 m bike / ~24 m drive per transition at σ10/5 s).
- **`spikeM`** (30 m) costs a little `completeRate` and is a follow-up knob, not swept.
- **Foot only in v1** (owner, 2026-10-18). Riding a bike on a bike-allowed trail does not count. The 4 m/s speed check enforces this.
- **Tiny trails** (< 30 m) are now folded or hidden (table above); trails of 30–50 m can still be auto-completed from a neighbour. A folded receiver's `agency_only_length_mi` is understated by up to 0.02 mi (the folded trail's < 30 m), since it is computed against the receiver's own canonical length.
- **Smoothing** pulls each piece's first and last fix inward by up to ~half a window of travel (~6.5 m at 10 s) and cuts corners; it does nothing for sparse (≥ 15 s) recordings.

## What Spike 4 must deliver

Background location at **σ ≤ 10 m effective accuracy and a fix interval ≤ 5 s** while walking. At 15 s / σ15, every config collapses: cr @0.90 is at most 0.59, and even 30/off/3 only reaches 0.83 at threshold 0.80.

Spike 4 should report, on iOS and Android with the screen off:
- the real interval distribution;
- the `horizontalAccuracy` / `accuracy` distribution under tree cover;
- whether throttling causes gaps > 60 s, which split pieces.

It should also prototype what Spike 3b leaves open:
- **activity gating**: credit only while the platform activity recogniser says walking / running (iOS `CMMotionActivity`, Android Activity Recognition), to remove the bike / drive credit per transition;
- **step-count cross-check**: compare pedometer steps against the matched distance per piece (~1.3–1.5 steps per m); a piece with far too few steps credits nothing. A second, independent anti-cheat signal next to the speed check.

Accuracy contract: a fix with `accuracyM < 0` is dropped (iOS reports negative `horizontalAccuracy` for an invalid fix); `accuracyM == null` means unknown and the fix is kept. Platform code must map "no accuracy" to null, never to 0 or a negative value.

## What Spike 5 receives

`CoverageState.encode()` produces a JSON object `{"seg-000001":"<base64>", ...}`:
- keys are sorted, and segments with no bits are omitted;
- only scored segments appear (included, or on a trail); competitor segments never do (privacy, Spike 3b);
- the payload is a little-endian bitset: tick i is bit (i % 8) of byte (i / 8), with trailing zero bytes dropped;
- merging is bitwise OR (`union`), commutative and idempotent, so it is safe for sync conflicts;
- `decode` is the inverse.

The contract is **(segment id, `tickM` = 5 m, fixed orientation)**. Bit i is the i-th tick from the segment's first coord in `coverage_segments.json`; `bitrails dist` orients every segment so its first (lon, lat), rounded to 6 dp, is lexicographically ≤ its last.

The dataset version is **not** part of the contract: a new dataset keeps stored bits valid as long as these three hold. The segment registry keeps an id across small geometry changes (≤ 8 m / ≤ 5%), so a segment id's tick count can change between datasets; the consumer must then rescale that segment's stored bits proportionally (e.g. tick i of n → tick floor(i·n′/n) of n′). A new id is a new segment (old bits for a retired id are dropped). Changing `tickM` invalidates all stored bits.

A versioned envelope (e.g. `{"v":1,"tickM":5,"bits":{…}}`) is a Spike 5 decision; not implemented.

## Official mileage candidates (owner to verify in a browser)

From web-search snippets only; none adopted. biparks.org returns 403 to automated fetches, so each page must be opened by hand. If confirmed, set `official_length_mi` + `official_length_source` in `trails_overrides.yaml`.

- Forest to Sky Trail: https://www.biparks.org/Home/Components/FacilityDirectory/FacilityDirectory/87/58?widgetId=540
- Close Trail: https://biparks.org/map-location/close-trail/?mpfy_map=1654&mpfy-pin=1979 (unstable map-pin URL; find a stable page)
- Hawley Cove Trail: https://www.biparks.org/Home/Components/FacilityDirectory/FacilityDirectory/99/58?widgetId=540
- Gazzam Lake Main Trail: https://www.biparks.org/Home/Components/FacilityDirectory/FacilityDirectory/93/58?widgetId=540
- Gazzam Lake Loop Trail: https://www.biparks.org/Home/Components/FacilityDirectory/FacilityDirectory/93/58?widgetId=540
- Main Loop Trail: https://www.biparks.org/Home/Components/FacilityDirectory/FacilityDirectory/95/58?widgetId=540

## Owner real-track procedure (Step 6, gate before v1 planning)

1. Record 2–3 walks with any GPX logger (for example GPSLogger, OsmAnd or Gaia), 1–5 s interval if the app allows:
   - one along a known pair of parallel trails;
   - one out-and-back;
   - one normal loop.
2. Copy the `.gpx` files to `data/tracks/private/`. That path is gitignored, so never commit real tracks.
3. From `app/`, run: `./gradlew :coverage-eval:run --args="gpx --data ../data/dist ../data/tracks/private/<file>.gpx"`
4. Compare against what was actually walked:
   - each walked trail should reach ≥ the threshold;
   - parallel and unwalked neighbours should stay low;
   - note the dropped-fix counts (inaccurate, speed).
5. Append the results to this file under "Real tracks". Report fractions per trail only, never coordinates.
