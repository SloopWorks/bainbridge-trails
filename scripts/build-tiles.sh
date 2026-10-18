#!/usr/bin/env bash
# Build app tile distributables into data/dist/ (run `bitrails build` then `bitrails dist` first).
#   data/dist/trails.pmtiles    vector, layer "segments", z10-16, props id, category, trail_ids (comma-joined string,
#                               MVT has no arrays), name (omitted when null), included (bool). Input: public segments only.
#   data/dist/basemap.pmtiles   Protomaps basemap extract (ODbL, OSM-derived), island bbox, maxzoom 16
#   data/dist/terrain/*         built by build-terrain.sh only if missing
# Env: BASEMAP_BUILD=YYYYMMDD pins the Protomaps daily build (default: newest of the last 7 days, skipping today's
#      possibly-incomplete one); BASEMAP_SKIP=1 skips the (network) basemap step.
# Prereqs: tippecanoe, pmtiles, jq (+ gdal for terrain).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${DIST:-$ROOT/data/dist}"
BBOX="-122.62,47.57,-122.47,47.73"
for tool in tippecanoe pmtiles; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done
[[ -f "$DIST/segments.tiles.geojson" ]] || { echo "missing $DIST/segments.tiles.geojson -- run: (cd pipeline && uv run bitrails dist)" >&2; exit 1; }

echo "== trails.pmtiles"
rm -f "$DIST/trails.pmtiles"
tippecanoe -o "$DIST/trails.pmtiles" -l segments -Z10 -z16 --no-tile-size-limit --simplification=2 \
  -n "Bainbridge trails" -A "(c) OpenStreetMap contributors, BIMPRD, others; see ATTRIBUTION.md" -q "$DIST/segments.tiles.geojson"

if [[ ! -f "$DIST/terrain/contours.pmtiles" || ! -f "$DIST/terrain/hillshade.pmtiles" ]]; then
  echo "== terrain missing; building"
  "$ROOT/scripts/build-terrain.sh"
fi

if [[ "${BASEMAP_SKIP:-0}" != 1 ]]; then
  echo "== basemap.pmtiles"
  build="${BASEMAP_BUILD:-}"
  if [[ -z "$build" ]]; then
    for back in 1 2 3 4 5 6 7; do
      d="$(python3 -c "import datetime as d;print((d.datetime.now(d.timezone.utc)-d.timedelta(days=$back)).strftime('%Y%m%d'))")"
      if [[ "$(curl -s -o /dev/null -w '%{http_code}' -I "https://build.protomaps.com/$d.pmtiles")" == 200 ]]; then build="$d"; break; fi
    done
  fi
  [[ -n "$build" ]] || { echo "no Protomaps daily build found in the last 7 days" >&2; exit 1; }
  echo "Protomaps build $build"
  rm -f "$DIST/basemap.pmtiles"
  pmtiles extract "https://build.protomaps.com/$build.pmtiles" "$DIST/basemap.pmtiles" --bbox="$BBOX" --maxzoom=16
fi

echo "== done"
ls -l "$DIST"/*.pmtiles "$DIST"/terrain/*.pmtiles
