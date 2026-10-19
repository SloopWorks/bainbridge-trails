"""`bitrails dist`: canonical outputs -> app distributables (offline, redistributable data only).

  data/dist/segments.tiles.geojson  tippecanoe input: segments.geojson with props whitelisted to TILE_PROPS
  data/dist/trails.json             app metadata (trails, island totals, dataset `version`)
  data/dist/coverage_segments.json  per-segment geometry (6-dp lon/lat) for on-device coverage
  data/dist/trailheads.json         geofence trailhead candidates (agency points + road-access endpoints, clustered)
  data/dist/ATTRIBUTION.md          attribution for every source whose data is used (+ copy at data/ATTRIBUTION.md)
Never reads data/canonical/private/. `trail_ids` is a comma-joined string in tiles (MVT has no arrays).
`trail_ids` in tiles and coverage_segments come from dist trails.json (after folding), not canonical props.
`scripts/build-tiles.sh` turns segments.tiles.geojson into trails.pmtiles and extracts the basemap.
"""
from __future__ import annotations

import hashlib
import json
import math
import subprocess
from pathlib import Path

import shapely
import yaml
from shapely.geometry import Point

from .boundary import REPO_ROOT
from .elevation import trail_chain_totals
from .classify import load_rule
from .geo import to_utm
from .trailheads import trailheads_doc

DATA = REPO_ROOT / "data"
MI = 1609.344
TRAILHEAD_M = 5.0
TINY_TRAIL_M = 30.0  # shorter trails are folded into a touching trail or hidden (ruling R15)
TOUCH_M = 1.0
TILE_PROPS = ("id", "category", "trail_ids", "name", "included")
TRAIL_FIELDS = ("id", "name", "manager", "park", "segment_ids", "computed_length_mi", "gain_m", "loss_m",
                "climb_total_m", "trailheads", "official_map_url", "agency_only_length_mi",
                "official_length_mi", "official_length_source")
TERRAIN_SOURCE = "usgs_3dep_dem_island"   # contours, hillshade, gain/loss all derive from it
BASEMAP_SOURCE = "protomaps_basemap"      # virtual: not in sources.yaml
BASEMAP_ATTRIBUTION = ("(c) OpenStreetMap contributors, ODbL 1.0 - https://www.openstreetmap.org/copyright; "
                       "basemap tiles from Protomaps (https://protomaps.com), an ODbL-licensed OSM derivative")


def _features(path: Path) -> list[dict]:
    return json.loads(Path(path).read_text())["features"]


def segment_trail_map(trails_doc: dict) -> dict[str, list[str]]:
    """{segment id: sorted dist trail ids} inverted from dist trails' `segment_ids` (so folded segments name
    their receiver, and trails dropped from dist are absent)."""
    out: dict[str, set[str]] = {}
    for t in trails_doc["trails"]:
        for i in t["segment_ids"]:
            out.setdefault(i, set()).add(t["id"])
    return {i: sorted(ts) for i, ts in out.items()}


def write_tile_input(segments: Path, out: Path, seg_trails: dict[str, list[str]]) -> Path:
    """Stripped GeoJSON for tippecanoe: only TILE_PROPS, trail_ids (from `seg_trails`) joined, null/absent name omitted."""
    feats = []
    for f in _features(segments):
        p = f["properties"]
        if not p.get("geometry_redistributable"):
            raise ValueError(f"{p.get('id')}: geometry not redistributable; refusing to tile it")
        props = {"id": p["id"], "category": p["category"], "trail_ids": ",".join(seg_trails.get(p["id"], [])),
                 "included": bool(p["included"])}
        if p.get("name"):
            props["name"] = p["name"]
        feats.append({"type": "Feature", "geometry": f["geometry"], "properties": dict(sorted(props.items()))})
    out.parent.mkdir(parents=True, exist_ok=True)
    body = ",\n".join(json.dumps(f, separators=(",", ":"), ensure_ascii=False) for f in feats)
    out.write_text('{"type":"FeatureCollection","features":[\n' + body + "\n]}\n")
    return out


def coverage_segments_doc(segments: Path, seg_trails: dict[str, list[str]], version: str) -> dict:
    """Geometry for on-device coverage and map matching: every redistributable segment, with its `category`
    (non-included ones are matching competitors; `seg_trails` (see segment_trail_map) supplies `trail_ids`). Fixed orientation: coords run so that the first (lon, lat), after
    6-dp rounding, is <= the last, lexicographically (part of the Spike 5 sync contract)."""
    out = []
    for f in _features(segments):
        p = f["properties"]
        if not p.get("geometry_redistributable"):
            raise ValueError(f"{p.get('id')}: geometry not redistributable; refusing to export it")
        tids = list(seg_trails.get(p["id"], []))
        coords = [[round(c[0], 6), round(c[1], 6)] for c in f["geometry"]["coordinates"]]
        if tuple(coords[0]) > tuple(coords[-1]):
            coords.reverse()
        out.append({"id": p["id"], "length_m": p["length_m"], "included": bool(p["included"]), "trail_ids": tids,
                    "coords": coords, "category": p["category"]})
    return {"version": version, "segments": sorted(out, key=lambda s: s["id"])}


def canonical_commit_date(canonical: Path) -> str:
    """ISO date of the latest git commit touching canonical (reproducible; no wall clock)."""
    r = subprocess.run(["git", "log", "-1", "--format=%cI", "--", str(canonical)], cwd=REPO_ROOT,
                       capture_output=True, text=True)
    if r.returncode != 0 or not r.stdout.strip():
        raise RuntimeError(f"no git commit date for {canonical}; commit data/canonical first")
    return r.stdout.strip()


def dataset_version(segments: Path, commit_iso: str) -> str:
    return f"{commit_iso[:10]}+{hashlib.sha256(Path(segments).read_bytes()).hexdigest()[:8]}"


def shippable_info(segments: Path, elevation: Path | None) -> dict[str, dict]:
    """{id: {length_m, geometry (UTM), profile}} for every shippable segment."""
    prof = json.loads(elevation.read_text()) if elevation and elevation.exists() else {}
    out = {}
    for f in _features(segments):
        p = f["properties"]
        out[p["id"]] = {"length_m": p["length_m"], "geometry": to_utm(shapely.from_geojson(json.dumps(f["geometry"]))),
                        "profile": prof.get(p["id"])}
    return out


def _trail_row(t: dict, ids: list[str], info: dict[str, dict]) -> dict:
    """One dist trail row from `t` (canonical trail) and its shippable segment ids."""
    mi = sum(info[i]["length_m"] for i in ids) / MI
    items = []
    for i in ids:
        c = info[i]["geometry"].coords
        items.append(((c[0][0], c[0][1]), (c[-1][0], c[-1][1]), info[i]["profile"] or [[0, None]]))
    geoms = [info[i]["geometry"] for i in ids]
    heads = []
    for h in t["trailheads"]:
        pt = to_utm(Point(h["coordinates"]))
        if any(g.distance(pt) <= TRAILHEAD_M for g in geoms):
            heads.append(h)
    row = {k: t.get(k) for k in TRAIL_FIELDS}
    row.update(segment_ids=ids, computed_length_mi=round(mi, 3), trailheads=heads,
               agency_only_length_mi=round(max(0.0, t["computed_length_mi"] - mi), 3),
               **trail_chain_totals(items))
    return row


def _endpoints(ids: list[str], info: dict[str, dict]) -> list[tuple[float, float]]:
    pts = []
    for i in ids:
        c = info[i]["geometry"].coords
        pts += [(c[0][0], c[0][1]), (c[-1][0], c[-1][1])]
    return pts


def _touch(a: list[tuple[float, float]], b: list[tuple[float, float]]) -> bool:
    return any(math.hypot(p[0] - q[0], p[1] - q[1]) <= TOUCH_M for p in a for q in b)


def dist_trails_doc(canonical_doc: dict, info: dict[str, dict], version: str) -> dict:
    """App metadata. Everything per trail (segment_ids, length, gain/loss, trailheads) is recomputed from
    shippable segments only, so a trail is 100% completable in the app. `agency_only_length_mi` = what is missing.
    Trails with no shippable segment, or no name, are dropped. Trails shorter than TINY_TRAIL_M are folded into
    the longest touching trail (receiver gets `folded_from`) or hidden when nothing touches them."""
    cands = []  # (canonical trail, shippable ids, length_m)
    for t in canonical_doc["trails"]:
        if not (t.get("name") or "").strip():
            continue  # the app lists only named trails
        ids = [i for i in t["segment_ids"] if i in info]
        if ids:
            cands.append((t, ids, sum(info[i]["length_m"] for i in ids)))
    keep = {t["id"]: (t, list(ids), m) for t, ids, m in cands if m >= TINY_TRAIL_M}
    folded: dict[str, list[str]] = {}
    for t, ids, m in sorted((c for c in cands if c[2] < TINY_TRAIL_M), key=lambda c: c[0]["id"]):
        ends = _endpoints(ids, info)
        touching = [k for k, (_, kids, _m) in keep.items() if _touch(ends, _endpoints(kids, info))]
        if not touching:
            continue  # hidden
        best = min(touching, key=lambda k: (-keep[k][2], k))
        bt, bids, bm = keep[best]
        bids += [i for i in ids if i not in bids]
        keep[best] = (bt, bids, bm)  # length stays the pre-fold length so later ties/ordering are stable
        folded.setdefault(best, []).append(t["id"])
    trails = []
    for t, _, _ in cands:
        if t["id"] not in keep:
            continue
        row = _trail_row(t, keep[t["id"]][1], info)
        if t["id"] in folded:
            row["folded_from"] = folded[t["id"]]
        trails.append(row)
    return {"version": version, "island_total_m": canonical_doc["island_total_m"],
            "island_total_shippable_m": canonical_doc["island_total_shippable_m"], "trails": trails}


def used_source_ids(segments: Path) -> set[str]:
    used = {s for f in _features(segments) for s in f["properties"].get("sources", [])}
    return used | {TERRAIN_SOURCE, BASEMAP_SOURCE}


def attribution_md(used: set[str], sources: list[dict]) -> str:
    """Geometry sources (redistribution: yes, plus the basemap) vs sources used for names/attributes only."""
    by_id = {s["id"]: s for s in sources}
    groups: dict[str, dict[str, list]] = {"geometry": {}, "attributes": {}}
    for sid in sorted(used):
        src = by_id.get(sid) or {}
        text = BASEMAP_ATTRIBUTION if sid == BASEMAP_SOURCE else src.get("attribution")
        if not text or text == "n/a":
            raise ValueError(f"no attribution for used source {sid}")
        kind = "geometry" if sid == BASEMAP_SOURCE or src.get("redistribution") == "yes" else "attributes"
        groups[kind].setdefault(text, []).append(sid)
    out = ["# Attribution", "",
           "Required credits for data shipped in the app (generated by `bitrails dist`; do not edit).", "",
           "## Geometry and map data", ""]
    out += [f"- {t}  \n  (sources: {', '.join(i)})" for t, i in sorted(groups["geometry"].items())]
    out += ["", "## Names and attributes only (geometry not redistributed)", ""]
    out += [f"- {t}  \n  (sources: {', '.join(i)})" for t, i in sorted(groups["attributes"].items())]
    return "\n".join(out) + "\n"


def run_dist(canonical: Path = DATA / "canonical", out_dir: Path = DATA / "dist",
             sources_path: Path = DATA / "sources.yaml", attribution_copy: Path = DATA / "ATTRIBUTION.md",
             date: str | None = None) -> dict:
    seg = canonical / "segments.geojson"
    out_dir.mkdir(parents=True, exist_ok=True)
    info = shippable_info(seg, canonical / "elevation.json")
    version = dataset_version(seg, date or canonical_commit_date(canonical))
    doc = dist_trails_doc(json.loads((canonical / "trails.json").read_text()), info, version)
    (out_dir / "trails.json").write_text(json.dumps(doc, sort_keys=True, indent=2, ensure_ascii=False) + "\n")
    seg_trails = segment_trail_map(doc)
    write_tile_input(seg, out_dir / "segments.tiles.geojson", seg_trails)
    cov = coverage_segments_doc(seg, seg_trails, version)
    (out_dir / "coverage_segments.json").write_text(json.dumps(cov, separators=(",", ":")))
    th = trailheads_doc(doc, seg_trails, _features(seg), load_rule(DATA / "inclusion.yaml").road_name_pattern, version)
    (out_dir / "trailheads.json").write_text(json.dumps(th, sort_keys=True, indent=2, ensure_ascii=False) + "\n")
    md = attribution_md(used_source_ids(seg), yaml.safe_load(sources_path.read_text())["sources"])
    (out_dir / "ATTRIBUTION.md").write_text(md)
    attribution_copy.write_text(md)
    return doc
