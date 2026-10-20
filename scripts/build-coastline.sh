#!/usr/bin/env bash
# Build the Me hero's island outline: app/composeApp/src/commonMain/composeResources/files/coastline.json
#   {"rings": [[[lon, lat], ...], ...]}  -- Bainbridge Island's land polygon (outer ring first, then any holes),
#   simplified to ~20 m (web-mercator units), 5-decimal coordinates. ~10 KB, committed (unlike the tile assets).
# Source: the Protomaps basemap's "earth" layer at z12 (ODbL, OSM-derived; credited in ATTRIBUTION.md with the basemap).
# Tiles cut land into pieces; a +/-3 m buffer around the union welds them back into one polygon per landmass, and the
# one containing Winslow is the island.
# Prereqs: GDAL >= 3.8 (PMTiles driver, SQLite dialect with SpatiaLite), python3. Run after scripts/build-tiles.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${DIST:-$ROOT/data/dist}"
OUT="$ROOT/app/composeApp/src/commonMain/composeResources/files/coastline.json"
[[ -f "$DIST/basemap.pmtiles" ]] || { echo "missing $DIST/basemap.pmtiles -- run scripts/build-tiles.sh first" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
ogr2ogr -f GeoJSON "$TMP/earth.geojson" "$DIST/basemap.pmtiles" earth -oo ZOOM_LEVEL=12 -nlt MULTIPOLYGON
ogr2ogr -f GeoJSON "$TMP/union.geojson" "$TMP/earth.geojson" -dialect sqlite \
  -sql "SELECT ST_Buffer(ST_Union(ST_Buffer(geometry, 3)), -3) AS geometry FROM earth"
ogr2ogr -f GeoJSON "$TMP/parts.geojson" "$TMP/union.geojson" -explodecollections
# Winslow ferry terminal area, in EPSG:3857 (the tiles' CRS).
ogr2ogr -f GeoJSON "$TMP/island.geojson" "$TMP/parts.geojson" -dialect sqlite -lco COORDINATE_PRECISION=5 \
  -sql "SELECT ST_Transform(ST_SimplifyPreserveTopology(geometry, 20), 4326) AS geometry FROM earth
        WHERE ST_Contains(geometry, ST_Transform(MakePoint(-122.52, 47.625, 4326), 3857))"

python3 -I - "$TMP/island.geojson" "$OUT" <<'EOF'
import json, sys
features = json.load(open(sys.argv[1]))["features"]
if len(features) != 1:
    sys.exit(f"expected one island polygon, got {len(features)}")
rings = features[0]["geometry"]["coordinates"]
with open(sys.argv[2], "w") as f:
    json.dump({"rings": [[[round(x, 5), round(y, 5)] for x, y in r] for r in rings]}, f, separators=(",", ":"))
print(f"{sys.argv[2]}: {len(rings)} ring(s), {sum(len(r) for r in rings)} points")
EOF
