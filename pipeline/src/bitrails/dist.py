"""`bitrails dist`: canonical outputs -> app distributables (offline, redistributable data only).

  data/dist/segments.tiles.geojson  tippecanoe input: segments.geojson with props whitelisted to TILE_PROPS
  data/dist/trails.json             app metadata (trails, island totals, dataset `version`)
  data/dist/ATTRIBUTION.md          attribution for every source whose data is used (+ copy at data/ATTRIBUTION.md)
Never reads data/canonical/private/. `trail_ids` is a comma-joined string in tiles (MVT has no arrays).
`scripts/build-tiles.sh` turns segments.tiles.geojson into trails.pmtiles and extracts the basemap.
"""
from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path

import shapely
import yaml
from shapely.geometry import Point

from .boundary import REPO_ROOT
from .elevation import trail_chain_totals
from .geo import to_utm

DATA = REPO_ROOT / "data"
MI = 1609.344
TRAILHEAD_M = 5.0
TILE_PROPS = ("id", "category", "trail_ids", "name", "included")
TRAIL_FIELDS = ("id", "name", "manager", "park", "segment_ids", "computed_length_mi", "gain_m", "loss_m",
                "climb_total_m", "trailheads", "official_map_url", "agency_only_length_mi")
TERRAIN_SOURCE = "usgs_3dep_dem_island"   # contours, hillshade, gain/loss all derive from it
BASEMAP_SOURCE = "protomaps_basemap"      # virtual: not in sources.yaml
BASEMAP_ATTRIBUTION = ("(c) OpenStreetMap contributors, ODbL 1.0 - https://www.openstreetmap.org/copyright; "
                       "basemap tiles from Protomaps (https://protomaps.com), an ODbL-licensed OSM derivative")


def _features(path: Path) -> list[dict]:
    return json.loads(Path(path).read_text())["features"]


def write_tile_input(segments: Path, out: Path) -> Path:
    """Stripped GeoJSON for tippecanoe: only TILE_PROPS, trail_ids joined, null/absent name omitted."""
    feats = []
    for f in _features(segments):
        p = f["properties"]
        if not p.get("geometry_redistributable"):
            raise ValueError(f"{p.get('id')}: geometry not redistributable; refusing to tile it")
        props = {"id": p["id"], "category": p["category"], "trail_ids": ",".join(p.get("trail_ids") or []),
                 "included": bool(p["included"])}
        if p.get("name"):
            props["name"] = p["name"]
        feats.append({"type": "Feature", "geometry": f["geometry"], "properties": dict(sorted(props.items()))})
    out.parent.mkdir(parents=True, exist_ok=True)
    body = ",\n".join(json.dumps(f, separators=(",", ":"), ensure_ascii=False) for f in feats)
    out.write_text('{"type":"FeatureCollection","features":[\n' + body + "\n]}\n")
    return out


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


def dist_trails_doc(canonical_doc: dict, info: dict[str, dict], version: str) -> dict:
    """App metadata. Everything per trail (segment_ids, length, gain/loss, trailheads) is recomputed from
    shippable segments only, so a trail is 100% completable in the app. `agency_only_length_mi` = what is missing.
    Trails with no shippable segment are dropped."""
    trails = []
    for t in canonical_doc["trails"]:
        ids = [i for i in t["segment_ids"] if i in info]
        if not ids:
            continue
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
    write_tile_input(seg, out_dir / "segments.tiles.geojson")
    info = shippable_info(seg, canonical / "elevation.json")
    version = dataset_version(seg, date or canonical_commit_date(canonical))
    doc = dist_trails_doc(json.loads((canonical / "trails.json").read_text()), info, version)
    (out_dir / "trails.json").write_text(json.dumps(doc, sort_keys=True, indent=2, ensure_ascii=False) + "\n")
    md = attribution_md(used_source_ids(seg), yaml.safe_load(sources_path.read_text())["sources"])
    (out_dir / "ATTRIBUTION.md").write_text(md)
    attribution_copy.write_text(md)
    return doc
