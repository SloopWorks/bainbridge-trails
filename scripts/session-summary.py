#!/usr/bin/env python3
"""Summarise a recorded session .jsonl without printing coordinates.

usage: session-summary.py <session>.jsonl

Prints duration, fix count, battery %/h (from diag events), activity mix (time-weighted),
and dropped counts. Dropped counts follow Gating.kt:
  - activity: a fix is dropped when the latest activity sample at or before it is VEHICLE or
    BICYCLE (>= MEDIUM); a sample governs until the next one, capped at 10 min.
  - steps (only if the last "steps" source state is available): per 60 s window [ws, we) from
    the first kept fix, judged by an evidence span [ws - 60 s, we + 60 s] clamped to the kept
    fixes' time range (step counters stall for ~1 min while walking). Cumulative steps at the
    span edges are interpolated between the nearest samples and held after the last sample;
    the window is skipped when no sample is at or before the span start, or the counter
    decreased (reset) inside the span. Its fixes are dropped when the span moved > 180 m
    (3 x 60 m) and span steps * 2.5 m cannot cover it. Moved distance adds nothing between two
    kept fixes > 60 s apart (coverage maxGapS) or with an activity-dropped fix between them.
Remaining differences from Gating.kt (labelled in the output):
  - mock fixes are counted separately and excluded before gating (Gating.kt gates them too;
    coverage drops them later), so vehicle/steps counts can differ when mock fixes exist;
  - distance is haversine here vs a local planar projection in Gating.kt (sub-metre difference).
"""
import json
import math
import sys
from collections import Counter

ACT_GOVERNS_MS, WINDOW_MS, MIN_MOVE_M, STRIDE_M = 600_000, 60_000, 60.0, 2.5
PAD_MS, SPAN_MIN_MOVE_M, MAX_GAP_MS = 60_000, 3 * MIN_MOVE_M, 60_000
CONF = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}


def dist(a, b):
    r = 6371000.0
    p1, p2 = math.radians(a["lat"]), math.radians(b["lat"])
    dl = math.radians(b["lon"] - a["lon"])
    dp = p2 - p1
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def cumulative_at(steps, t):
    """Gating.cumulativeAt: interpolate between neighbours; hold the last value after the last sample."""
    if t <= steps[0]["timeMs"]:
        return float(steps[0]["cumulativeSteps"])
    if t >= steps[-1]["timeMs"]:
        return float(steps[-1]["cumulativeSteps"])
    for a, b in zip(steps, steps[1:]):
        if b["timeMs"] >= t:
            if b["timeMs"] == t:
                return float(b["cumulativeSteps"])
            frac = (t - a["timeMs"]) / (b["timeMs"] - a["timeMs"])
            return a["cumulativeSteps"] + frac * (b["cumulativeSteps"] - a["cumulativeSteps"])
    return float(steps[-1]["cumulativeSteps"])


def main(path):
    ev = []
    with open(path) as f:
        for line in f:
            try:
                ev.append(json.loads(line))
            except ValueError:
                pass  # truncated tail
    if not ev:
        sys.exit("no events")
    times = []
    for e in ev:
        t = e.get("timeMs", e.get("sample", {}).get("timeMs"))
        if t is not None:
            times.append(t)
    start, end = min(times), max(times)
    dur_h = (end - start) / 3_600_000
    fixes = sorted((e for e in ev if e["t"] == "fix"), key=lambda e: e["timeMs"])
    acts = sorted((e["sample"] for e in ev if e["t"] == "act"), key=lambda s: s["timeMs"])
    steps = sorted((e["sample"] for e in ev if e["t"] == "steps"), key=lambda s: s["timeMs"])
    diags = [e for e in ev if e["t"] == "diag"]
    finished = any(e["t"] == "stop" for e in ev)
    source = {}
    for e in ev:
        if e["t"] == "source":
            source[e["source"]] = e["available"]
    steps_available = source.get("steps") is True

    print(f"file: {path.split('/')[-1]}  finished: {finished}")
    print(f"duration: {dur_h * 60:.1f} min")
    print(f"fixes: {len(fixes)}  diag events: {len(diags)}")

    batt = [(d["timeMs"], d["batteryPct"]) for d in diags if d.get("batteryPct") is not None]
    if len(batt) >= 2 and batt[-1][0] > batt[0][0]:
        drop = batt[0][1] - batt[-1][1]
        hrs = (batt[-1][0] - batt[0][0]) / 3_600_000
        if any(b[1] > a[1] for a, b in zip(batt, batt[1:])):
            print(f"battery: {batt[0][1]}% -> {batt[-1][1]}% over {hrs:.2f} h; WARNING battery rose (charging?), %/h omitted")
        else:
            print(f"battery: {batt[0][1]}% -> {batt[-1][1]}% over {hrs:.2f} h = {drop / hrs:.1f} %/h")
    else:
        print("battery: not enough diag readings")
    accs = [d["meanAccuracyM"] for d in diags if d.get("meanAccuracyM") is not None]
    if accs:
        print(f"mean accuracy across diags: {sum(accs) / len(accs):.1f} m")

    mix = Counter()
    for i, a in enumerate(acts):
        nxt = acts[i + 1]["timeMs"] if i + 1 < len(acts) else end
        mix[a["kind"]] += max(0, nxt - a["timeMs"])
    total = sum(mix.values())
    if total:
        print("activity mix: " + ", ".join(f"{k} {v / total * 100:.0f}%" for k, v in mix.most_common()))
    else:
        print("activity mix: no samples")

    mock = sum(1 for f in fixes if f["isMock"])
    veh = 0
    live = []
    brk = []  # brk[i]: an activity-dropped fix lies between live[i-1] and live[i]
    dropped_since = False
    for f in fixes:
        if f["isMock"]:
            continue
        a = None
        for s in acts:
            if s["timeMs"] <= f["timeMs"]:
                a = s
            else:
                break
        if a and f["timeMs"] - a["timeMs"] <= ACT_GOVERNS_MS and a["kind"] in ("VEHICLE", "BICYCLE") and CONF[a["confidence"]] >= 1:
            veh += 1
            dropped_since = True
        else:
            live.append(f)
            brk.append(dropped_since)
            dropped_since = False
    stp = 0
    if steps_available and steps and live:
        t0 = live[0]["timeMs"]
        t_end = live[-1]["timeMs"]
        cum = [0.0]  # path length to live[i], skipping activity drops and GPS gaps
        for i in range(1, len(live)):
            counts = not brk[i] and live[i]["timeMs"] - live[i - 1]["timeMs"] <= MAX_GAP_MS
            cum.append(cum[-1] + (dist(live[i - 1], live[i]) if counts else 0.0))
        wins = {}
        for f in live:
            wins.setdefault((f["timeMs"] - t0) // WINDOW_MS, []).append(f)
        for k, w in sorted(wins.items()):
            ws = t0 + k * WINDOW_MS
            we = ws + WINDOW_MS
            ss, se = max(ws - PAD_MS, t0), min(we + PAD_MS, t_end)
            if steps[0]["timeMs"] > ss:
                continue  # no step evidence at the span start
            if any(b["timeMs"] > ss and a["timeMs"] < se and b["cumulativeSteps"] < a["cumulativeSteps"]
                   for a, b in zip(steps, steps[1:])):
                continue  # counter reset: no usable step data
            idx = [i for i, f in enumerate(live) if ss <= f["timeMs"] <= se]
            moved = cum[idx[-1]] - cum[idx[0]] if idx else 0.0
            n = cumulative_at(steps, se) - cumulative_at(steps, ss)
            if n >= 0 and moved > SPAN_MIN_MOVE_M and n * STRIDE_M < moved:
                stp += len(w)
    print(f"dropped: mock {mock}, vehicle/bicycle {veh}, steps {stp}"
          " (differs from Gating.kt: mock fixes excluded before gating here; haversine distance)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
