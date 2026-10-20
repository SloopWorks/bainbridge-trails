"""Trailhead candidates for geofences (`trailheads.json`), derived from redistributable geometry only.

(a) every dist trail trailhead point is an `agency` candidate;
(b) every endpoint of a dist trail segment within ACCESS_M of an endpoint of a non-trail road connector, or of a
    trail-less multi_use_path segment, is a `road_access` candidate;
candidates within CLUSTER_M are merged greedily in id order (position = agency point if any, else centroid).
"""
from __future__ import annotations

import hashlib
import re
from collections import Counter

import shapely
from shapely.geometry import shape

from .geo import to_utm, to_wgs

ACCESS_M = 15.0
CLUSTER_M = 100.0
FALLBACK_NAME = "Trail access"


def _th_id(lon: float, lat: float) -> str:
    return "th-" + hashlib.sha1(f"{lon:.6f},{lat:.6f}".encode()).hexdigest()[:6]


def _utm(lon: float, lat: float) -> tuple[float, float]:
    p = to_utm(shapely.Point(lon, lat))
    return (p.x, p.y)


def _wgs(x: float, y: float) -> tuple[float, float]:
    p = to_wgs(shapely.Point(x, y))
    return (p.x, p.y)


def _ends(f: dict) -> list[tuple[float, float]]:
    c = f["geometry"]["coordinates"]
    return [(c[0][0], c[0][1]), (c[-1][0], c[-1][1])]


def trailheads_doc(trails_doc: dict, seg_trails: dict[str, list[str]], features: list[dict],
                   road_name_pattern: str, version: str) -> dict:
    trails = {t["id"]: t for t in trails_doc["trails"]}
    cands = []  # dict(lon, lat, xy, trail_ids, source)
    for t in trails_doc["trails"]:
        for h in t["trailheads"]:
            lon, lat = h["coordinates"][:2]
            cands.append({"lon": lon, "lat": lat, "xy": _utm(lon, lat), "trails": {t["id"]}, "source": "agency"})

    road_pts, seg_by_id = [], {}
    for f in features:
        p = f["properties"]
        if not p.get("geometry_redistributable"):
            continue
        seg_by_id[p["id"]] = f
        tids = seg_trails.get(p["id"], [])
        is_road = (p["category"] == "connector" and not tids and road_name_pattern and p.get("name")
                   and re.search(road_name_pattern, p["name"], re.I))
        if is_road or (p["category"] == "multi_use_path" and not tids):
            road_pts += [_utm(*e) for e in _ends(f)]
    if road_pts:
        road_tree = shapely.STRtree([shapely.Point(*q) for q in road_pts])
        for sid, tids in seg_trails.items():
            f = seg_by_id.get(sid)
            if f is None or not tids:
                continue
            for lon, lat in _ends(f):
                xy = _utm(lon, lat)
                if len(road_tree.query(shapely.Point(*xy).buffer(ACCESS_M))) and any(
                        (xy[0] - q[0]) ** 2 + (xy[1] - q[1]) ** 2 <= ACCESS_M ** 2
                        for q in (road_pts[i] for i in road_tree.query(shapely.Point(*xy).buffer(ACCESS_M)))):
                    cands.append({"lon": lon, "lat": lat, "xy": xy, "trails": set(tids), "source": "road_access"})

    for c in cands:
        c["id"] = _th_id(c["lon"], c["lat"])
    cands.sort(key=lambda c: (c["id"], c["source"]))
    clusters: list[list[dict]] = []
    for c in cands:
        for cl in clusters:
            s = cl[0]["xy"]
            if (c["xy"][0] - s[0]) ** 2 + (c["xy"][1] - s[1]) ** 2 <= CLUSTER_M ** 2:
                cl.append(c)
                break
        else:
            clusters.append([c])

    out = []
    for cl in clusters:
        ag = [c for c in cl if c["source"] == "agency"]
        if ag:
            lon, lat, source = ag[0]["lon"], ag[0]["lat"], "agency"
        else:
            x = sum(c["xy"][0] for c in cl) / len(cl)
            y = sum(c["xy"][1] for c in cl) / len(cl)
            lon, lat = _wgs(x, y)
            lon, lat, source = round(lon, 6), round(lat, 6), "road_access"
        tids = sorted(set().union(*(c["trails"] for c in cl)))
        parks = Counter(trails[i]["park"] for i in tids if trails[i].get("park"))
        names = Counter(trails[i]["name"] for i in tids if trails[i].get("name"))
        pick = lambda cn: sorted(cn.items(), key=lambda kv: (-kv[1], kv[0]))[0][0] if cn else None
        out.append({"id": _th_id(lon, lat), "lon": lon, "lat": lat, "name": pick(parks) or pick(names) or FALLBACK_NAME,
                    "trail_ids": tids, "source": source})
    out.sort(key=lambda t: t["id"])
    return {"version": version, "trailheads": out}
