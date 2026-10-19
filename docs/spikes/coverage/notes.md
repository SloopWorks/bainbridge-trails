# Spike 3: trail coverage, parameter sweep

Status: **BLOCKED, owner decision needed.** The Task 9 decision rule picks no config. `CoverageParams` defaults stay interim (buffer 20, heading 30°, margin 1, threshold 0.90). The spec §2 completion line is unchanged.

Data: `sweep.csv` (full grid, threshold 0.90, 540 rows), `sweep-t0.85.csv` and `sweep-t0.80.csv` (σ {10, 15} × interval {5, 15}, re-run for rule step 3). All simulated, no real tracks. Reproduce from `app/`:

```
./gradlew :coverage-eval:run --args="sweep --data ../data/dist --out ../docs/spikes/coverage/sweep.csv \
  --buffers 10,15,20,25,30 --headings off,30,45,60 --margins 0,1,3 --sigmas 5,10,15 --intervals 1,5,15 --seeds 3 --threshold 0.90"
```

Each row has 369 walks (123 trails × 3 seeds) and 162 multi-use-path vehicle chains (transitions) per seed.

## Algorithm (module `:coverage`)

1. **Segments → ticks.** Each coverage segment is resampled every `tickM` (5 m), with ticks at the step centres. Each tick has a bearing smoothed over `headingBaselineM` (15 m) along the segment. Ticks go in a 50 m grid.
2. **Fix filter** (`filterFixes`), in order:
   - sort and drop duplicate timestamps;
   - drop mock fixes and fixes with accuracy > `maxAccuracyM` (35 m);
   - drop single-fix spikes: both legs > `spikeM` (30 m) while the neighbours are < 15 m apart;
   - split into pieces on a gap > `maxGapS` (60 s) or a jump > `maxJumpM` (100 m);
   - speed check: drop a fix if its speed from the fix ≥ `speedWindowS` (30 s) earlier exceeds `maxSpeedMps` (4 m/s), and split there;
   - drop pieces with < 2 fixes.
3. **Matching.** Each piece is densified every `tickM`. Each point q gets a heading from a displacement baseline ≥ `headingBaselineM`. A point with no such baseline is stationary and credits nothing.
   - Candidates are ticks within `bufferM` of q that pass the heading check (axial difference ≤ `headingToleranceDeg`; skipped when the tolerance is null).
   - Competitive matching: a segment is credited only if its nearest candidate is within d\* + `matchMarginM` of the best segment's nearest candidate d\*. It credits that tick and its ±1 neighbours (heading-checked, within the buffer).
4. **State.** `CoverageState` holds one bitset of covered ticks per segment. Union is OR, so it is order-independent and idempotent.
5. **Progress.** A trail's fraction is the covered metres over the trail's distinct segment metres. It is COMPLETE at ≥ `completionThreshold`, NEARLY_DONE at ≥ `nearlyDoneThreshold` (0.75). The island fraction is covered metres over `islandTotalM`.

## Decision trail (σ = 10 m, interval = 5 s)

**Rule 1** keeps configs with `falseCompletes == 0`, `driveCreditM` ≤ 60 m per transition (R14) and `falseCreditRatio` ≤ 0.05.
- The drive criterion passes everywhere. Drive credit runs 8.6–47.6 m per transition across all 540 rows, about 36 m at the candidate configs, so it never binds. Bike credit runs 17–53 m per transition.
- No config has `falseCompletes == 0` at σ10/5 s. The minimum is 2, at buffer 10–20, heading off or 30°, margin 0.
- Only buffer 10 with heading 30 or 45 meets `falseCreditRatio` ≤ 0.05.
- **Rule 1 leaves an empty set.**

**Rules 2–3** need `completeRate` ≥ 0.95, with the threshold lowered from 0.90 down to 0.80. Ignoring rule 1, the best `completeRate` is 0.821 at 0.90, 0.883 at 0.85 and 0.916 at 0.80 (all at buffer 30, heading off, margin 3, which has 10 false completes). Even under ideal noise (σ5/1 s, threshold 0.90) no config passes; the maximum is 0.940. **Per the rule: stop and report to the owner.**

**Guard test** `parallelNeighborNotCredited` (trails 12 m apart, σ5, 1 s; needs A ≥ 0.90 and B < 0.50). I probed it over the whole grid with a scratch test that was not committed. It passes **only with heading 30° and margin 0 or 1**, at every buffer: B = 0.40 at margin 0, B = 0.425 at margin 1. Heading off gives B = 0.73–0.90, and heading 45/60 gives B = 0.68–0.85.

## Trade-off table (σ10/5 s unless noted; cr = completeRate, fc = falseCompletes)

| buffer/heading/margin | guard | cr @0.90 | cr @0.85 | cr @0.80 | fcr | fc @0.90 | mean frac | stand m | drive m/transition | worst σ15/15 s cr @0.90 / @0.80 |
|---|---|---|---|---|---|---|---|---|---|---|
| 10/30/0 | pass | 0.033 | 0.054 | 0.089 | **0.040** | 3 | 0.59 | 41 | 26 | 0.008 / 0.027 |
| **20/30/1 (interim)** | pass | 0.176 | 0.425 | 0.640 | 0.065 | 3 | 0.78 | 44 | 36 | 0.038 / 0.130 |
| 30/30/1 | pass | 0.276 | 0.564 | 0.726 | 0.078 | 4 | 0.80 | 47 | 37 | 0.049 / 0.222 |
| 15/off/0 | fail | 0.547 | 0.737 | 0.827 | 0.082 | **2** | 0.85 | 36 | 33 | 0.119 / 0.409 |
| 20/off/0 | fail | 0.718 | 0.821 | 0.870 | 0.086 | **2** | 0.88 | 36 | 36 | 0.266 / 0.658 |
| 20/off/1 | fail | 0.751 | 0.832 | 0.881 | 0.093 | 3 | 0.89 | 35 | 37 | 0.285 / 0.680 |
| 20/60/1 | fail | 0.748 | 0.835 | 0.892 | 0.084 | 5 | 0.89 | 38 | 36 | 0.220 / 0.626 |
| 25/60/3 | fail | 0.808 | 0.867 | 0.913 | 0.101 | 10 | 0.91 | 38 | 38 | 0.431 / 0.761 |
| 30/off/3 | fail | **0.821** | **0.883** | **0.916** | 0.116 | 10 | 0.91 | 35 | 38 | 0.564 / 0.802 |

`fc` rises as the threshold drops (for example, 20/off/0 goes from 2 to 3), so lowering the threshold cannot satisfy rule 1 either.

### Why: diagnosis from a scratch per-trail run (σ10/5 s, own seeds)

- **False completes are tiny trails, not real leakage.** Examples are Waterfront Trail (2 m) from walking West Loop Trail, Gideon Trail (11 m) from Gideon Park Trail, and Rotary Park Trails (31 m) from Rotary Trail. A 2–31 m trail lies wholly inside the buffer of the trail next to it. `falseCompletes == 0` is unreachable at any buffer ≥ 10 m while these stay first-class trails.
- **The ~0.96 ceiling.** Four or five trails are shorter than the 15 m heading baseline (Dock Street road end 3 m, Waterfront 2 m, Gideon 11 m, plus another short entry). Walked alone they score 0 under every config, which removes about 4% of walks. That puts `completeRate` ≥ 0.95 at the edge of what is reachable even with zero noise.
- **Heading 30° at σ10 under-credits every trail.** A 15 m baseline under 10 m correlated noise gives a heading error often above 30°. Long trails then land at 0.72–0.88 instead of about 0.88–0.92. That is the gap between 20/30/1 (cr 0.18) and 20/off/0 (cr 0.72). Heading 30° is also the only setting that passes the parallel-trail guard. **This is the core conflict.**
- **Heading off sits right at the 0.90 line.** Mean fraction is 0.88–0.91, so a 0.90 threshold splits walks roughly in half. Lowering it to 0.80 lifts heading-off configs to 0.87–0.92.

## Options for the owner (none applied)

1. **Filter or merge tiny trails** (< ~35 m, or < 15 m at minimum) into their neighbour, or leave them out of "trails complete". This removes most false completes and the zero-score ceiling, and is likely needed under any option.
2. **Relax the parallel-trail guard** (accept B ≈ 0.75 at 12 m spacing). Then 20/off/0 at threshold 0.80 gives cr 0.870, fcr 0.086 and fc 3 (worst case cr 0.658). This does not meet 0.95 or fcr ≤ 0.05.
3. **Keep the guard (heading 30°) and fix the heading noise.** Options are a longer baseline (`headingBaselineM` 25–30 m) or a heading taken from the smoothed track, then re-sweep. This is untested and most likely to close the gap without giving up the anti-goal. It needs code.
4. **Loosen the targets.** For example: cr ≥ 0.85 at 0.80, fcr ≤ 0.10, and `falseCompletes` counted only on trails ≥ 35 m.
5. `spikeM` costs a little `completeRate` and is a follow-up knob, not a fix for this gap.

## Known limits (for any config)

- **Parallel trails < 12 m apart.** At the defaults the guard gives A = 1.0 and B = 0.425. Heading off credits the neighbour at 73–90%.
- **Hairpin switchbacks** tighter than the buffer score 0.71–0.93 even at zero noise.
- **Trails shorter than the 15 m heading baseline** (4–5 trails) score 0 when walked alone. Combining or filtering trails is an owner option (see 1).
- **Speed-window leak (R5).** About 30 s of fast travel right after walking passes the speed check. That is about 36 m per transition at the candidate configs (5.9 km driving, 7.6 km biking over 162 multi-use-path chains). It is never 0.
- **Spike filter** costs a little `completeRate`.
- **Foot only in v1** (owner, 2026-10-18). Riding a bike on a bike-allowed trail does not count. The 4 m/s speed check is what enforces this.

## What Spike 4 must deliver

Background location at **σ ≤ 10 m effective accuracy and a fix interval ≤ 5 s** while walking. At 15 s / σ15, every config collapses: cr @0.90 is 0.01–0.56, and even 30/off/3 only reaches 0.80 at threshold 0.80.

Spike 4 should report, on iOS and Android with the screen off:
- the real interval distribution;
- the `horizontalAccuracy` / `accuracy` distribution under tree cover;
- whether throttling causes gaps > 60 s, which split pieces.

## What Spike 5 receives

`CoverageState.encode()` produces a JSON object `{"seg-000001":"<base64>", ...}`:
- keys are sorted, and segments with no bits are omitted;
- the payload is a little-endian bitset: tick i is bit (i % 8) of byte (i / 8), with trailing zero bytes dropped;
- merging is bitwise OR (`union`), commutative and idempotent, so it is safe for sync conflicts;
- `decode` is the inverse.

Bit indices depend on `tickM` (5 m) and segment geometry. Changing `tickM` or re-cutting segments invalidates stored bits, so the dataset version and `tickM` are part of the contract.

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
