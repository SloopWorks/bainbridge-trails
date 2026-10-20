#!/usr/bin/env bash
# Build the road tile distributable (run `scripts/bitroads build` then `scripts/bitroads dist` first).
#   data/roads/dist/roads.pmtiles   vector, layer "segments", z10-16, props id, category, ownership,
#                                   road_ids (comma-joined string, MVT has no arrays), name (omitted
#                                   when null), included (bool). Input: shippable segments only.
# Basemap and terrain are NOT rebuilt here: a roads app uses the same data/dist/basemap.pmtiles and
# data/dist/terrain/* as the trail app (scripts/build-tiles.sh, scripts/build-terrain.sh).
# Prereqs: tippecanoe.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${DIST:-$ROOT/data/roads/dist}"
command -v tippecanoe >/dev/null || { echo "missing tool: tippecanoe" >&2; exit 1; }
[[ -f "$DIST/segments.tiles.geojson" ]] || {
  echo "missing $DIST/segments.tiles.geojson -- run: scripts/bitroads dist" >&2; exit 1; }

echo "== roads.pmtiles"
rm -f "$DIST/roads.pmtiles"
tippecanoe -o "$DIST/roads.pmtiles" -l segments -Z10 -z16 --no-tile-size-limit --simplification=2 \
  -n "Bainbridge roads" -A "(c) OpenStreetMap contributors, ODbL; see ATTRIBUTION.md" -q \
  "$DIST/segments.tiles.geojson"

echo "== done"
ls -l "$DIST/roads.pmtiles"
