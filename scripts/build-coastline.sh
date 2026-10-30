#!/usr/bin/env bash
# Build the hero and map island outlines in app/composeApp/src/commonMain/composeResources/files/.
#   {"rings": [[[lon, lat], ...], ...]}  -- Bainbridge Island's land polygon (outer ring first, then any holes),
# coastline.json: z12, simplified 20m for the hero and Play geometry (preserved).
# map-coastline.json: z15 (archive maximum), simplified 0.5m for clean close-up clipping on the live map.
# Source: Protomaps "earth" (ODbL, OSM-derived; credited in ATTRIBUTION.md with the basemap).
# Tiles cut land into pieces; a small buffer welds floating-point seams in the union. The hero keeps its original
# 3m welding; the map uses 0.01m to preserve narrow real inlets. Select the landmass containing Winslow.
# Prereqs: GDAL >= 3.8 (PMTiles driver, SQLite dialect with SpatiaLite), python3. Run after scripts/build-tiles.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${DIST:-$ROOT/data/dist}"
OUT="$ROOT/app/composeApp/src/commonMain/composeResources/files"
[[ -f "$DIST/basemap.pmtiles" ]] || { echo "missing $DIST/basemap.pmtiles -- run scripts/build-tiles.sh first" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
build_outline() {
local zoom="$1" simplify="$2" output="$3" precision=7 weld=0.01
if [[ "$zoom" == "12" ]]; then precision=5; weld=3; fi
rm -f "$TMP/earth.geojson" "$TMP/union.geojson" "$TMP/parts.geojson" "$TMP/island.geojson"
ogr2ogr -f GeoJSON "$TMP/earth.geojson" "$DIST/basemap.pmtiles" earth -oo ZOOM_LEVEL="$zoom" -nlt MULTIPOLYGON
ogr2ogr -f GeoJSON "$TMP/union.geojson" "$TMP/earth.geojson" -dialect sqlite \
  -sql "SELECT ST_Buffer(ST_Union(ST_Buffer(geometry, $weld)), -$weld) AS geometry FROM earth"
ogr2ogr -f GeoJSON "$TMP/parts.geojson" "$TMP/union.geojson" -explodecollections
# Winslow ferry terminal area, in EPSG:3857 (the tiles' CRS).
ogr2ogr -f GeoJSON "$TMP/island.geojson" "$TMP/parts.geojson" -dialect sqlite -lco COORDINATE_PRECISION="$precision" \
  -sql "SELECT ST_Transform(ST_SimplifyPreserveTopology(geometry, $simplify), 4326) AS geometry FROM earth
        WHERE ST_Contains(geometry, ST_Transform(MakePoint(-122.52, 47.625, 4326), 3857))"

python3 -I - "$TMP/island.geojson" "$OUT/$output" "$zoom" <<'EOF'
import json, sys
features = json.load(open(sys.argv[1]))["features"]
if len(features) != 1:
    sys.exit(f"expected one island polygon, got {len(features)}")
rings = features[0]["geometry"]["coordinates"]
precision = 5 if sys.argv[3] == "12" else 7
with open(sys.argv[2], "w") as f:
    json.dump({"rings": [[[round(x, precision), round(y, precision)] for x, y in r] for r in rings]}, f, separators=(",", ":"))
print(f"{sys.argv[2]}: {len(rings)} ring(s), {sum(len(r) for r in rings)} points")
EOF
}

build_outline 12 20 coastline.json
build_outline 15 0.5 map-coastline.json
