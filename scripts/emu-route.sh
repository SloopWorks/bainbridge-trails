#!/usr/bin/env bash
# Replay a walk along one trail on a running Android emulator: `adb emu geo fix` every <interval-s>
# seconds, moving at 1.3 m/s along the trail's segments (data/dist/coverage_segments.json + trails.json).
#
#   scripts/emu-route.sh <trail-id> <interval-s> [max-seconds]
#   e.g. scripts/emu-route.sh trail-gazzam-lake-main-trail 2 300
#
# Segments are chained greedily (nearest unused endpoint next, reversed as needed), so a branching
# trail is walked as one continuous line with short straight hops between branches. Positions are
# simulated on a public trail; nothing here reads real tracks. ADB=/path/to/adb and ANDROID_SERIAL work as usual.
set -euo pipefail

[[ $# -ge 2 ]] || { echo "usage: $0 <trail-id> <interval-s> [max-seconds]" >&2; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${DIST:-$ROOT/data/dist}"
ADB="${ADB:-adb}"

python3 -I - "$DIST" "$1" "$2" "${3:-0}" "$ADB" <<'PY'
import json, math, subprocess, sys, time

dist, trail_id, interval, max_s, adb = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
SPEED = 1.3  # m/s

trails = {t["id"]: t for t in json.load(open(f"{dist}/trails.json"))["trails"]}
if trail_id not in trails:
    close = [t for t in trails if trail_id.split("-")[1] in t] if "-" in trail_id else []
    sys.exit(f"unknown trail {trail_id!r}; similar: {close[:8]}")
segs = {s["id"]: s["coords"] for s in json.load(open(f"{dist}/coverage_segments.json"))["segments"]}
lines = [segs[i] for i in trails[trail_id]["segment_ids"] if i in segs]
if not lines:
    sys.exit("trail has no segments")

def d(a, b):
    k = math.cos(math.radians((a[1] + b[1]) / 2))
    return math.hypot((b[0] - a[0]) * 111_320 * k, (b[1] - a[1]) * 110_574)

chain = list(lines.pop(0))
while lines:
    end = chain[-1]
    best = min(range(len(lines)), key=lambda i: min(d(end, lines[i][0]), d(end, lines[i][-1])))
    ln = lines.pop(best)
    if d(end, ln[-1]) < d(end, ln[0]):
        ln = ln[::-1]
    chain += ln

total = sum(d(a, b) for a, b in zip(chain, chain[1:]))
print(f"{trail_id}: {len(chain)} vertices, {total:.0f} m, ~{total / SPEED / 60:.1f} min at {SPEED} m/s", flush=True)

def at(s):
    for a, b in zip(chain, chain[1:]):
        l = d(a, b)
        if s <= l and l > 0:
            f = s / l
            return a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
        s -= l
    return chain[-1]

t0 = time.monotonic()
n = 0
while True:
    s = n * interval * SPEED
    if s > total or (max_s and n * interval > max_s):
        break
    lon, lat = at(s)
    subprocess.run([adb, "emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}"], check=True, stdout=subprocess.DEVNULL)
    if n % 15 == 0:
        print(f"t={n * interval:5.0f}s  s={s:6.0f} m", flush=True)
    n += 1
    time.sleep(max(0.0, t0 + n * interval - time.monotonic()))
print(f"done: {n} fixes, {min(total, (n - 1) * interval * SPEED):.0f} m", flush=True)
PY
