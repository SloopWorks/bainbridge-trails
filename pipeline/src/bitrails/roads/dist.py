"""`bitroads dist`: canonical road outputs -> app distributables (offline, redistributable data only).

  data/roads/dist/roads.json              app metadata (roads, island totals, dataset `version`)
  data/roads/dist/segments.tiles.geojson  tippecanoe input: TILE_PROPS only, road_ids comma-joined
  data/roads/dist/coverage_segments.json  per-segment geometry (6-dp lon/lat) for on-device coverage
  data/roads/dist/road_ends.json          public road ends at the water, derived from shipping geometry
  data/roads/dist/ATTRIBUTION.md          credits for every source used
Never reads data/roads/canonical/private/. Road metadata is recomputed from shippable segments only,
so every road the app lists can actually be completed in the app; `agency_only_length_mi` is what is
missing. Road ends come from OSM geometry + the island boundary (both ODbL), with the City's road-end
layer used only to corroborate - its coordinates never ship.
"""
from __future__ import annotations

import json
from pathlib import Path

import shapely
import yaml
from shapely.geometry import Point
from shapely.strtree import STRtree

from ..boundary import island_polygon
from ..dist import attribution_md, canonical_commit_date, dataset_version, _features
from ..fetch import FetchError, Source, _from_cache
from ..geo import to_utm
from .build import DEFAULT_CACHE, DEFAULT_CANONICAL, DEFAULT_SOURCES, ROADS, SHARED_CACHE
from .names import clean

MI = 1609.344
TILE_PROPS = ("id", "category", "ownership", "road_ids", "name", "included")
ROAD_FIELDS = ("id", "name", "category", "ownership", "maintainer", "segment_ids", "computed_length_mi",
               "gain_m", "loss_m", "climb_total_m", "surface", "speed_mph", "public", "counted")
TERRAIN_SOURCE = "usgs_3dep_dem_island"
BASEMAP_SOURCE = "protomaps_basemap"
ROAD_ENDS_SOURCE = "cobi_rd_road_ends"
SHORE_M = 40.0          # endpoint this close to the island boundary is a road end at the water
JUNCTION_M = 1.0        # endpoints this close to another segment's are the same junction
CORROBORATE_M = 120.0   # distance within which a City road-end point confirms a derived road end


def segment_road_map(roads_doc: dict) -> dict[str, list[str]]:
    out: dict[str, set[str]] = {}
    for r in roads_doc["roads"]:
        for i in r["segment_ids"]:
            out.setdefault(i, set()).add(r["id"])
    return {i: sorted(rs) for i, rs in out.items()}


def shippable_info(segments: Path, elevation: Path | None) -> dict[str, dict]:
    """{id: {length_m, geometry (UTM), profile, props}} for every shippable segment."""
    prof = json.loads(elevation.read_text()) if elevation and elevation.exists() else {}
    out = {}
    for f in _features(segments):
        p = f["properties"]
        out[p["id"]] = {"length_m": p["length_m"], "props": p,
                        "geometry": to_utm(shapely.from_geojson(json.dumps(f["geometry"]))),
                        "profile": prof.get(p["id"])}
    return out


def write_tile_input(segments: Path, out: Path, seg_roads: dict[str, list[str]]) -> Path:
    feats = []
    for f in _features(segments):
        p = f["properties"]
        if not p.get("geometry_redistributable"):
            raise ValueError(f"{p.get('id')}: geometry not redistributable; refusing to tile it")
        props = {"id": p["id"], "category": p["category"], "ownership": p["ownership"],
                 "road_ids": ",".join(seg_roads.get(p["id"], [])), "included": bool(p["included"])}
        if p.get("name"):
            props["name"] = p["name"]
        feats.append({"type": "Feature", "geometry": f["geometry"], "properties": dict(sorted(props.items()))})
    out.parent.mkdir(parents=True, exist_ok=True)
    body = ",\n".join(json.dumps(f, separators=(",", ":"), ensure_ascii=False) for f in feats)
    out.write_text('{"type":"FeatureCollection","features":[\n' + body + "\n]}\n")
    return out


def coverage_segments_doc(segments: Path, seg_roads: dict[str, list[str]], version: str) -> dict:
    """Geometry for on-device coverage and map matching: same contract as the trail dataset's
    coverage_segments.json, with `road_ids` in place of `trail_ids`. Fixed orientation: coords run so
    that the first (lon, lat), after 6-dp rounding, is <= the last, lexicographically."""
    out = []
    for f in _features(segments):
        p = f["properties"]
        if not p.get("geometry_redistributable"):
            raise ValueError(f"{p.get('id')}: geometry not redistributable; refusing to export it")
        coords = [[round(c[0], 6), round(c[1], 6)] for c in f["geometry"]["coordinates"]]
        if tuple(coords[0]) > tuple(coords[-1]):
            coords.reverse()
        out.append({"id": p["id"], "length_m": p["length_m"], "included": bool(p["included"]),
                    "road_ids": list(seg_roads.get(p["id"], [])), "coords": coords,
                    "category": p["category"], "ownership": p["ownership"]})
    return {"version": version, "segments": sorted(out, key=lambda s: s["id"])}


def _road_row(r: dict, ids: list[str], info: dict[str, dict]) -> dict:
    mi = sum(info[i]["length_m"] for i in ids) / MI
    row = {k: r.get(k) for k in ROAD_FIELDS}
    gain = sum(info[i]["props"].get("gain_m") or 0.0 for i in ids)
    loss = sum(info[i]["props"].get("loss_m") or 0.0 for i in ids)
    row.update(segment_ids=ids, computed_length_mi=round(mi, 3), gain_m=round(gain, 1),
               loss_m=round(loss, 1), climb_total_m=round(gain + loss, 1),
               agency_only_length_mi=round(max(0.0, r["computed_length_mi"] - mi), 3))
    return row


def dist_roads_doc(canonical_doc: dict, info: dict[str, dict], version: str) -> dict:
    """App metadata. Roads with no shippable segment are dropped (nothing to draw or complete)."""
    roads = []
    for r in canonical_doc["roads"]:
        ids = [i for i in r["segment_ids"] if i in info]
        if ids:
            roads.append(_road_row(r, ids, info))
    return {"version": version, "island_total_m": canonical_doc["island_total_m"],
            "island_total_mi": canonical_doc["island_total_mi"],
            "island_total_shippable_m": canonical_doc["island_total_shippable_m"],
            "island_total_shippable_mi": canonical_doc["island_total_shippable_mi"],
            "rule": canonical_doc["rule"], "roads": sorted(roads, key=lambda r: r["id"])}


def _city_road_ends(cache_dir: Path) -> list[tuple[Point, dict]]:
    """City road-end / shore-access points from the cache, for corroboration only (never shipped)."""
    try:
        raw = _from_cache(Source(ROAD_ENDS_SOURCE, ROAD_ENDS_SOURCE, "arcgis_rest", None),
                          Path(cache_dir) / ROAD_ENDS_SOURCE, "offline")
    except FetchError:
        return []
    out = []
    for f in json.loads(raw.path.read_text()).get("features", []):
        g = f.get("geometry") or {}
        if g.get("type") == "Point":
            out.append((to_utm(Point(g["coordinates"][:2])), f.get("properties") or {}))
    return out


def road_ends_doc(segments: Path, seg_roads: dict[str, list[str]], roads_doc: dict, version: str,
                  boundary: Path | None = None, cache_dir: Path = DEFAULT_CACHE, island=None) -> dict:
    """Dead-end ends of counted public roads that reach the shoreline.

    Derived from shipping geometry only: an endpoint of an included segment that no other road segment
    shares (within JUNCTION_M) and that lies within SHORE_M of the island boundary. The City's
    road-end layer sets `city_confirmed` and may supply `city_name`/`city_access`; its coordinates
    are never used for the position.
    """
    feats = _features(segments)
    ends: list[tuple[tuple[float, float], dict]] = []
    all_ends: list[tuple[float, float]] = []
    for f in feats:
        c = f["geometry"]["coordinates"]
        all_ends += [tuple(c[0][:2]), tuple(c[-1][:2])]
    counts: dict[tuple[float, float], int] = {}
    for p in all_ends:
        counts[p] = counts.get(p, 0) + 1
    shore = to_utm(island if island is not None else island_polygon(boundary)).boundary
    names = {r["id"]: r["name"] for r in roads_doc["roads"]}
    for f in feats:
        p = f["properties"]
        if not p.get("included"):
            continue
        c = f["geometry"]["coordinates"]
        for pt in (tuple(c[0][:2]), tuple(c[-1][:2])):
            if counts.get(pt, 0) > 1:
                continue                       # a junction, not an end
            utm = to_utm(Point(pt))
            d = shore.distance(utm)
            if d > SHORE_M:
                continue
            rid = next(iter(seg_roads.get(p["id"], [])), None)
            ends.append((pt, {"segment_id": p["id"], "road_id": rid,
                              "name": p.get("name") or (names.get(rid) if rid else None),
                              "shore_distance_m": round(d, 1)}))
    city = _city_road_ends(cache_dir)
    tree = STRtree([g for g, _ in city]) if city else None
    out = []
    for pt, rec in sorted(ends, key=lambda e: (e[1]["road_id"] or "", e[0])):
        utm = to_utm(Point(pt))
        match = None
        if tree is not None:
            for j in tree.query(utm.buffer(CORROBORATE_M)):
                g, props = city[int(j)]
                if g.distance(utm) <= CORROBORATE_M:
                    match = props
                    break
        rec = dict(rec, coordinates=[round(pt[0], 6), round(pt[1], 6)], city_confirmed=match is not None)
        if match is not None:
            rec["city_name"] = clean(match.get("name"))
            rec["city_access"] = clean(match.get("ex_access"))
            rec["city_type"] = clean(match.get("type"))
        out.append(rec)
    return {"version": version, "notes": ("public road ends within "
            f"{SHORE_M:.0f} m of the island shoreline, derived from shipping geometry; city_* fields are "
            "attributes from the City's road-end layer (geometry not used)"), "road_ends": out}


def used_source_ids(segments: Path, road_ends: dict) -> set[str]:
    used = {s for f in _features(segments) for s in f["properties"].get("sources", [])}
    if any(r.get("city_confirmed") for r in road_ends["road_ends"]):
        used.add(ROAD_ENDS_SOURCE)
    return used | {TERRAIN_SOURCE, BASEMAP_SOURCE}


def run_dist(canonical: Path = DEFAULT_CANONICAL, out_dir: Path = ROADS / "dist",
             sources_path: Path = DEFAULT_SOURCES, cache_dir: Path = DEFAULT_CACHE,
             boundary: Path | None = None, date: str | None = None) -> dict:
    seg = canonical / "segments.geojson"
    out_dir.mkdir(parents=True, exist_ok=True)
    info = shippable_info(seg, canonical / "elevation.json")
    version = dataset_version(seg, date or canonical_commit_date(canonical))
    canon = json.loads((canonical / "roads.json").read_text())
    doc = dist_roads_doc(canon, info, version)
    (out_dir / "roads.json").write_text(json.dumps(doc, sort_keys=True, indent=2, ensure_ascii=False) + "\n")
    seg_roads = segment_road_map(doc)
    write_tile_input(seg, out_dir / "segments.tiles.geojson", seg_roads)
    cov = coverage_segments_doc(seg, seg_roads, version)
    (out_dir / "coverage_segments.json").write_text(json.dumps(cov, separators=(",", ":")))
    ends = road_ends_doc(seg, seg_roads, doc, version, boundary, cache_dir)
    (out_dir / "road_ends.json").write_text(json.dumps(ends, sort_keys=True, indent=2, ensure_ascii=False) + "\n")
    sources = yaml.safe_load(Path(sources_path).read_text())["sources"]
    md = attribution_md(used_source_ids(seg, ends), sources).replace("`bitrails dist`", "`bitroads dist`")
    (out_dir / "ATTRIBUTION.md").write_text(md)
    return doc
