#!/usr/bin/env python3
"""Summarise a recorded session .jsonl without printing coordinates.

usage: session-summary.py <session>.jsonl

Prints duration, fix count, battery %/h (from diag events), activity mix (time-weighted),
and dropped counts. Dropped counts mirror Gating.kt: mock fixes, fixes governed by a
VEHICLE/BICYCLE (>= MEDIUM) activity sample, and fixes in 60 s windows that moved > 60 m
with steps * 2.5 m too short to cover it.
"""
import json
import math
import sys
from collections import Counter

ACT_GOVERNS_MS, WINDOW_MS, MIN_MOVE_M, STRIDE_M = 120_000, 60_000, 60.0, 2.5
CONF = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}


def dist(a, b):
    r = 6371000.0
    p1, p2 = math.radians(a["lat"]), math.radians(b["lat"])
    dl = math.radians(b["lon"] - a["lon"])
    dp = p2 - p1
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


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
        else:
            live.append(f)
    stp = 0
    if len(steps) >= 2 and live:
        t0 = live[0]["timeMs"]
        wins = {}
        for f in live:
            wins.setdefault((f["timeMs"] - t0) // WINDOW_MS, []).append(f)
        for k, w in wins.items():
            ws = t0 + k * WINDOW_MS
            if not (steps[0]["timeMs"] <= ws and steps[-1]["timeMs"] >= ws + WINDOW_MS):
                continue
            moved = sum(dist(w[i], w[i + 1]) for i in range(len(w) - 1))
            n = [s["cumulativeSteps"] for s in steps if ws <= s["timeMs"] <= ws + WINDOW_MS]
            if moved > MIN_MOVE_M and (not n or (max(n) - min(n)) * STRIDE_M < moved):
                stp += len(w)
    print(f"dropped: mock {mock}, vehicle/bicycle {veh}, steps {stp} (approximation of Gating.kt)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
