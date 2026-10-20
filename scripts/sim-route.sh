#!/usr/bin/env bash
# Replay a walk along one trail on a booted iOS simulator: `xcrun simctl location <device> start`, moving at
# 1.3 m/s along the trail's segments (data/dist/coverage_segments.json + trails.json), one update per second.
# Counterpart of scripts/emu-route.sh. Returns immediately: the simulator plays the route in the background
# (stop it early with `xcrun simctl location <device> clear`).
#
#   scripts/sim-route.sh <trail-id> [max-seconds]
#   e.g. SIM=<udid> scripts/sim-route.sh trail-gazzam-lake-main-trail 300
#   e.g. DIST=data/roads/dist scripts/sim-route.sh road-battle-point-drive-northeast 600  (the roads dataset)
#
# Segments are chained greedily (nearest unused endpoint next, reversed as needed), so a branching trail is walked
# as one continuous line with short straight hops between branches. Positions are simulated on a public trail;
# nothing here reads real tracks. SIM selects the device (default: booted).
set -euo pipefail

[[ $# -ge 1 ]] || { echo "usage: $0 <trail-id> [max-seconds]" >&2; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${DIST:-$ROOT/data/dist}"
SIM="${SIM:-booted}"
SPEED=1.3
WAYPOINTS="$(mktemp -t sim-route)"
trap 'rm -f "$WAYPOINTS"' EXIT

python3 -I - "$DIST" "$1" "${2:-0}" "$SPEED" > "$WAYPOINTS" <<'PY'
import json, math, os, sys

dist, trail_id, max_s, speed = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4])

# The roads dataset (data/roads/dist) is the same contract under other names: DIST=data/roads/dist works.
meta = next((f"{dist}/{n}" for n in ("trails.json", "roads.json") if os.path.isfile(f"{dist}/{n}")), None)
if meta is None:
    sys.exit(f"no trails.json or roads.json in {dist}")
doc = json.load(open(meta))
trails = {t["id"]: t for t in (doc.get("trails") or doc["roads"])}
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

# Drop repeated vertices (simctl needs distinct consecutive waypoints) and cut at max-seconds of walking.
out, walked = [chain[0]], 0.0
for p in chain[1:]:
    step = d(out[-1], p)
    if step < 0.01:
        continue
    if max_s and walked + step > max_s * speed:
        f = (max_s * speed - walked) / step
        out.append([out[-1][0] + (p[0] - out[-1][0]) * f, out[-1][1] + (p[1] - out[-1][1]) * f])
        walked = max_s * speed
        break
    out.append(p)
    walked += step

print(f"{trail_id}: {len(out)} waypoints, {walked:.0f} m, ~{walked / speed / 60:.1f} min at {speed} m/s", file=sys.stderr)
for lon, lat in out:
    print(f"{lat:.7f},{lon:.7f}")  # simctl wants lat,lon
PY
xcrun simctl location "$SIM" start --speed="$SPEED" --interval=1 - < "$WAYPOINTS"
echo "route started on $SIM"
