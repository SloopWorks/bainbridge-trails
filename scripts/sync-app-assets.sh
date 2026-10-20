#!/usr/bin/env bash
# Copy built map data from data/dist/ into the app's Compose resources (app/composeApp/src/commonMain/composeResources/files/).
# Run after scripts/build-tiles.sh (and `bitrails dist`). The copied files are gitignored; styles/*.json stay committed.
# At runtime the app copies these into its private files dir when the bundled metadata document's "version" changes.
#
# The roads dataset (data/roads/dist, docs/roads/plan.md) is installed alongside the trail one when it has been built,
# under names a debug build selects at runtime (DatasetProfile): roads.json, roads_coverage_segments.json,
# roads.pmtiles, roads_dataset.bin, roads_road_ends.json. Both datasets then sit in the APK, so switching needs no rebuild. Skip it with
# ROADS=0; ROADS=1 makes a missing roads build an error instead of a skip.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${DIST:-$ROOT/data/dist}"
ROADS_DIST="${ROADS_DIST:-$ROOT/data/roads/dist}"
DEST="$ROOT/app/composeApp/src/commonMain/composeResources/files"

# source (relative to DIST) -> destination name (flat in DEST)
ASSETS=(
  "trails.pmtiles:trails.pmtiles"
  "basemap.pmtiles:basemap.pmtiles"
  "trails.json:trails.json"
  "coverage_segments.json:coverage_segments.json"
  "trailheads.json:trailheads.json"
  "ATTRIBUTION.md:ATTRIBUTION.md"
  "terrain/contours.pmtiles:contours.pmtiles"
  "terrain/hillshade.pmtiles:hillshade.pmtiles"
)

for pair in "${ASSETS[@]}"; do
  src="$DIST/${pair%%:*}"
  [[ -f "$src" ]] || { echo "missing $src -- run scripts/build-tiles.sh and bitrails dist (in pipeline/) first" >&2; exit 1; }
done

mkdir -p "$DEST"
for pair in "${ASSETS[@]}"; do
  src="$DIST/${pair%%:*}"
  dst="$DEST/${pair##*:}"
  cp "$src" "$dst"
  echo "$(du -h "$dst" | cut -f1)	${pair##*:}"
done

# dataset.bin: the app's fast-loading copy of trails.json + coverage_segments.json (DatasetBinary; the app falls back
# to the JSON if it is missing, stale or unreadable). Verified against the JSON by the packer and by :coverage:jvmTest.
"$ROOT/app/gradlew" -p "$ROOT/app" -q :coverage-eval:run --args="pack-dataset $DEST $DEST/dataset.bin"
echo "$(du -h "$DEST/dataset.bin" | cut -f1)	dataset.bin"

# --- roads dataset (debug-only second profile) ----------------------------------------------------
ROADS="${ROADS:-auto}"
ROAD_ASSETS=(
  "roads.json:roads.json"
  "coverage_segments.json:roads_coverage_segments.json"
  "roads.pmtiles:roads.pmtiles"
  "road_ends.json:roads_road_ends.json"
)
if [[ "$ROADS" == "0" ]]; then
  echo "roads: skipped (ROADS=0)"
else
  missing=()
  for pair in "${ROAD_ASSETS[@]}"; do
    [[ -f "$ROADS_DIST/${pair%%:*}" ]] || missing+=("${pair%%:*}")
  done
  if (( ${#missing[@]} )); then
    msg="roads: ${missing[*]} not in $ROADS_DIST -- run scripts/bitroads dist and scripts/build-roads-tiles.sh"
    [[ "$ROADS" == "1" ]] && { echo "$msg" >&2; exit 1; }
    echo "$msg (skipped; ROADS=1 to require it)"
  else
    echo "== roads"
    for pair in "${ROAD_ASSETS[@]}"; do
      cp "$ROADS_DIST/${pair%%:*}" "$DEST/${pair##*:}"
      echo "$(du -h "$DEST/${pair##*:}" | cut -f1)	${pair##*:}"
    done
    "$ROOT/app/gradlew" -p "$ROOT/app" -q :coverage-eval:run \
      --args="pack-dataset $DEST $DEST/roads_dataset.bin --meta roads.json --segments roads_coverage_segments.json"
    echo "$(du -h "$DEST/roads_dataset.bin" | cut -f1)	roads_dataset.bin"
  fi
fi
