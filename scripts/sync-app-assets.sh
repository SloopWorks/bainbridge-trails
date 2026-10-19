#!/usr/bin/env bash
# Copy built map data from data/dist/ into the app's Compose resources (app/composeApp/src/commonMain/composeResources/files/).
# Run after scripts/build-tiles.sh (and `bitrails dist`). The copied files are gitignored; styles/*.json stay committed.
# At runtime the app copies these into its private files dir when the bundled trails.json "version" changes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${DIST:-$ROOT/data/dist}"
DEST="$ROOT/app/composeApp/src/commonMain/composeResources/files"

# source (relative to DIST) -> destination name (flat in DEST)
ASSETS=(
  "trails.pmtiles:trails.pmtiles"
  "basemap.pmtiles:basemap.pmtiles"
  "trails.json:trails.json"
  "coverage_segments.json:coverage_segments.json"
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
