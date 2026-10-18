#!/usr/bin/env bash
# Build terrain tiles from the cached USGS 3DEP island DEM (slow; separate from `bitrails build`).
#   data/dist/terrain/contours.pmtiles   vector, layer "contours", z11-16, props elev_m (m), index (bool, every 25 m)
#   data/dist/terrain/hillshade.pmtiles  raster PNG (gray+alpha), z10-15
# Prereqs: gdal (gdal_contour, ogr2ogr, gdaldem, gdalwarp, gdal_translate, gdaladdo), tippecanoe, pmtiles, jq.
# DEM first: (cd pipeline && uv run bitrails fetch --only usgs_3dep_dem_island)
# Data: USGS 3DEP, public domain. Data available from U.S. Geological Survey, National Geospatial Program.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEM="${DEM:-$ROOT/data/raw/usgs_3dep_dem_island/latest.tiff}"
OUT="${OUT:-$ROOT/data/dist/terrain}"
NODATA=-9999

[[ -f "$DEM" ]] || { echo "DEM missing: $DEM -- run: (cd pipeline && uv run bitrails fetch --only usgs_3dep_dem_island)" >&2; exit 1; }
for tool in gdal_contour ogr2ogr gdaldem gdalwarp gdal_translate gdaladdo tippecanoe pmtiles jq; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done

mkdir -p "$OUT"
WORK="$(mktemp -d "$OUT/.work.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "== contours (5 m, index every 25 m)"
gdal_contour -a elev_m -i 5 -snodata "$NODATA" -f GeoJSON "$DEM" "$WORK/contours_utm.geojson"
ogr2ogr -f GeoJSON -t_srs EPSG:4326 -lco COORDINATE_PRECISION=6 "$WORK/contours_wgs.geojson" "$WORK/contours_utm.geojson"
# index = elev_m % 25 == 0 (bool); minor contours appear from z13, index contours from z11.
jq -c '.features[] | del(.properties.ID) | .properties.elev_m |= (. * 10 | round / 10)
       | .properties.index = ((.properties.elev_m % 25) == 0)
       | .tippecanoe = {minzoom: (if .properties.index then 11 else 13 end)}' \
  "$WORK/contours_wgs.geojson" > "$WORK/contours.ndjson"
rm -f "$OUT/contours.pmtiles"
tippecanoe -o "$OUT/contours.pmtiles" -l contours -Z11 -z16 --simplification=4 --drop-densest-as-needed \
  --no-tile-size-limit -n "contours" -A "USGS 3DEP (public domain)" "$WORK/contours.ndjson" -q

echo "== hillshade (multidirectional)"
gdaldem hillshade -multidirectional -z 1 -compute_edges -of GTiff "$DEM" "$WORK/hs_utm.tif" -q
# DEM nodata -> alpha 0. Hillshade of nodata cells is 0; build gray+alpha via warp with dstalpha.
gdalwarp -q -t_srs EPSG:3857 -r bilinear -srcnodata 0 -dstalpha -overwrite "$WORK/hs_utm.tif" "$WORK/hs_3857.tif"
rm -f "$WORK/hillshade.mbtiles" "$OUT/hillshade.pmtiles"
gdal_translate -q -of MBTILES -co TILE_FORMAT=PNG -co ZOOM_LEVEL_STRATEGY=LOWER "$WORK/hs_3857.tif" "$WORK/hillshade.mbtiles"
gdaladdo -q -r average "$WORK/hillshade.mbtiles" 2 4 8 16 32
pmtiles convert "$WORK/hillshade.mbtiles" "$OUT/hillshade.pmtiles" >/dev/null

echo "== done"
ls -l "$OUT"/*.pmtiles
