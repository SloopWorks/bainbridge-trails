# Spike 3: trail coverage, parameter sweep

Status: **Provisional defaults set (owner chose option B, 2026-10-18).** The Task 9 decision rule still picks no config; defaults are option B, see "Decision (owner, 2026-10-18)". Re-check on real GPX walks before v1.

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

Option B, provisional: **buffer 30 m, heading off, margin 1 m, smooth 10 s, completion threshold 0.85** (`nearlyDoneThreshold` 0.75 and `headingBaselineM` 15 unchanged; the baseline still drives the stationary rule). Now the `CoverageParams` defaults; spec §2 says 85%.

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
   - Competitive matching: a segment is credited only if its nearest candidate is within d\* + `matchMarginM` of the best segment's nearest candidate d\*. It credits that tick and its ±1 neighbours (heading-checked, within the buffer).
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

- **Off-network walking is not measured.** Only `included` and trail segments are exported (1,614 of 7,537 canonical segments). Informal paths and connectors do not compete in matching, so walking a road shoulder or informal path within 30 m of a trail can credit that trail under option B (no heading check). Option B's fcr 0.066 is on-network only (the simulator walks exported trails). Fix before v1: export all redistributable segments as competitors (credit only `included` / trail ones) and add a sweep scenario that walks off-network paths next to trails.
- **Parallel trails < 12 m apart.** The guard passes everywhere with smoothing, but only because its noise is white; under correlated noise, heading off still credits neighbours (fcr 0.066–0.09).
- **Hairpin switchbacks** tighter than the buffer score 0.71–0.93 even at zero noise; smoothing cuts them further.
- **Speed-window leak (R5).** About 30 s of fast travel right after walking passes the speed check: 19–36 m per transition on average (smoothing reduces it). It is never 0.
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

Accuracy contract: a fix with `accuracyM < 0` is dropped (iOS reports negative `horizontalAccuracy` for an invalid fix); `accuracyM == null` means unknown and the fix is kept. Platform code must map "no accuracy" to null, never to 0 or a negative value.

## What Spike 5 receives

`CoverageState.encode()` produces a JSON object `{"seg-000001":"<base64>", ...}`:
- keys are sorted, and segments with no bits are omitted;
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
