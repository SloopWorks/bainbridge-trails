"""Normalize raw line data into segments: explode, clip to island, split at junctions."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field, replace
from pathlib import Path

import shapely
from shapely.geometry import LineString, MultiLineString, Point
from shapely.geometry.base import BaseGeometry
from shapely.ops import substring
from shapely.strtree import STRtree

from .fetch import Source
from .geo import to_utm, to_wgs

SNAP_M = 1.0       # junction snap tolerance
MIN_LEN_M = 0.5    # drop degenerate parts shorter than this


@dataclass(frozen=True)
class SourceRef:
    source_id: str
    feature_id: str
    tags: dict = field(default_factory=dict)


@dataclass(frozen=True)
class Segment:
    id: str
    geometry: LineString
    source_refs: list[SourceRef] = field(default_factory=list)
    tags: dict = field(default_factory=dict)


def _lines_of(g: BaseGeometry) -> list[LineString]:
    if g is None or g.is_empty:
        return []
    if isinstance(g, LineString):
        return [g]
    if hasattr(g, "geoms"):
        return [p for sub in g.geoms for p in _lines_of(sub)]
    return []


def _ok(line: LineString) -> bool:
    return len(line.coords) >= 2 and to_utm(line).length >= MIN_LEN_M


def _cut_positions(a: LineString, others: list[LineString]) -> list[float]:
    """Distances along `a` (UTM) at which it must be split."""
    length = a.length
    pts: list[Point] = []
    for b in others:
        pts.extend(Point(c) for c in (b.coords[0], b.coords[-1]))
        pts.extend(Point(c) for c in b.coords[1:-1] if a.distance(Point(c)) <= SNAP_M)
        inter = a.intersection(b)
        pts.extend(g for g in (inter.geoms if hasattr(inter, "geoms") else [inter]) if isinstance(g, Point))
    pos = sorted(a.project(p) for p in pts if a.distance(p) <= SNAP_M)
    out: list[float] = []
    for d in pos:
        if d < SNAP_M or d > length - SNAP_M:
            continue
        if out and d - out[-1] < SNAP_M:
            continue
        out.append(d)
    return out


def _split_origin(lines: list[LineString]) -> list[tuple[int, LineString]]:
    """Split WGS84 lines at junctions; returns (index of source line, piece) pairs."""
    utm = [to_utm(l) for l in lines]
    tree = STRtree(utm)
    out: list[tuple[int, LineString]] = []
    for i, a in enumerate(utm):
        near = sorted(j for j in tree.query(a.buffer(SNAP_M)) if j != i)
        cuts = _cut_positions(a, [utm[j] for j in near]) if near else []
        bounds = [0.0, *cuts, a.length]
        if len(bounds) == 2:
            out.append((i, lines[i]))
            continue
        for s, e in zip(bounds, bounds[1:]):
            piece = substring(a, s, e)
            if isinstance(piece, LineString) and len(piece.coords) >= 2 and piece.length >= MIN_LEN_M:
                out.append((i, to_wgs(piece)))
    return out


def split_at_junctions(lines: list[LineString]) -> list[LineString]:
    """Split lines where another line's endpoint/vertex touches or crosses it (1 m snap)."""
    return [p for _, p in _split_origin(lines)]


def _natural(s: str):
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", s)]


def _read_features(src: Source, raw_path: Path) -> list[tuple[str, dict, list[LineString]]]:
    data = json.loads(Path(raw_path).read_text())
    feats: list[tuple[str, dict, list[LineString]]] = []
    if src.kind == "overpass":
        for e in data.get("elements", []):
            if e.get("type") != "way" or not e.get("geometry") or len(e["geometry"]) < 2:
                continue
            line = LineString([(p["lon"], p["lat"]) for p in e["geometry"]])
            feats.append((f"way/{e['id']}", e.get("tags") or {}, [line]))
    else:
        for n, f in enumerate(data.get("features", [])):
            g = f.get("geometry")
            if not g or g.get("type") not in ("LineString", "MultiLineString"):
                continue
            props = f.get("properties") or {}
            fid = next((props[k] for k in ("OBJECTID", "OBJECTID_1", "FID") if props.get(k) is not None), None)
            if fid is None:
                fid = f.get("id")
            if fid is None:
                fid = f"idx{n}"
            geom = shapely.from_geojson(json.dumps(g))
            feats.append((str(fid), props, _lines_of(geom)))
    return feats


def normalize_source(src: Source, raw_path: Path, island: BaseGeometry) -> list[Segment]:
    """Raw source file -> junction-split line segments clipped to the island."""
    feats = sorted(_read_features(src, raw_path), key=lambda f: _natural(f[0]))
    lines: list[LineString] = []
    owner: list[SourceRef] = []
    for fid, tags, parts in feats:
        ref = SourceRef(src.id, fid, dict(tags))
        for part in parts:
            for clipped in _lines_of(part.intersection(island)):
                if _ok(clipped):
                    lines.append(clipped)
                    owner.append(ref)
    segs = []
    for n, (i, piece) in enumerate(_split_origin(lines), start=1):
        segs.append(Segment(f"{src.id}:{n}", piece, [owner[i]], dict(owner[i].tags)))
    return segs


def assign_ids(segments: list[Segment], registry, finalize: bool = True) -> list[Segment]:
    """Replace provisional IDs with stable registry IDs; retire IDs unseen this run.

    Call exactly once per build with the full reconciled canonical segment set:
    every active registry ID not in `segments` is retired (never reused). Calling it
    per source would retire other sources' IDs; a second call raises RuntimeError.
    Segments tagged geometry_redistributable=False are stored as private registry entries
    (no coordinates in the committed file). Caller persists with registry.save().
    """
    ids = registry.assign_many([s.geometry for s in segments],
                               [s.tags.get("geometry_redistributable") is False for s in segments])
    if finalize:  # False = degraded build (a source is missing): retire nothing
        registry.finalize_run()
    return [replace(s, id=i) for s, i in zip(segments, ids)]
