"""Island boundary polygon (EPSG:4326) built from the cached OSM relation."""
from __future__ import annotations

import json
from pathlib import Path

from shapely.geometry import LineString
from shapely.geometry.base import BaseGeometry
from shapely.ops import polygonize, unary_union

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_BOUNDARY = REPO_ROOT / "data" / "raw" / "osm_island_boundary" / "latest.json"


def _rings(members: list[dict], role: str) -> BaseGeometry:
    lines = [LineString([(p["lon"], p["lat"]) for p in m["geometry"]])
             for m in members if m.get("role") == role and m.get("geometry")]
    return unary_union(list(polygonize(unary_union(lines)))) if lines else LineString()


def island_polygon(path: Path | None = None) -> BaseGeometry:
    """Polygon (or MultiPolygon if the relation has islets) of the island, lon/lat."""
    data = json.loads(Path(path or DEFAULT_BOUNDARY).read_text())
    rel = next(e for e in data["elements"] if e["type"] == "relation")
    outer = _rings(rel["members"], "outer")
    inner = _rings(rel["members"], "inner")
    return outer.difference(inner) if not inner.is_empty else outer
