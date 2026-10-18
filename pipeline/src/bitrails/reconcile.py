"""Reconcile per-source segments into one canonical segment set + a conflict list.

Sources are processed in geometry-rank order (``Source.geometry_rank``; OSM first by
default). The best-ranked source seeds the canonical set and its segmentation. Each
later source's segments are matched against the canonical set (in UTM, 10 m buffers):
a (canonical, incoming) pair matches when >= 70% of the shorter one lies inside the
other's buffer. Matched canonical segments keep their geometry and gain the incoming
source's provenance + names. An incoming segment counts as matched when >= 70% of its
length is covered by the union of its matched canonical segments; otherwise its
uncovered remainder (pieces > 20 m) becomes new canonical segments flagged
``missing_in_osm``. So adding a trail that already exists never changes the island total.

The 70% rule decides geometry/coverage only. Attribute transfer (names, provenance,
agency_matched) and the name/length/offset conflicts additionally need substantial mutual
overlap: the along-track overlap (summed over one agency trail's pieces) must be >= 20 m
and >= 50% of the canonical segment, so short crossing segments neither lend nor steal names.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
import re

import numpy as np
import shapely
from shapely.geometry import LineString
from shapely.geometry.base import BaseGeometry
from shapely.ops import unary_union
from shapely.strtree import STRtree

from .fetch import Source
from .geo import to_utm, to_wgs
from .segments import Segment, SourceRef, _lines_of, _natural

BUFFER_M = 10.0          # match buffer
MATCH_FRAC = 0.70        # share of the shorter segment inside the other's buffer
LENGTH_TOL = 0.10        # agency vs canonical length mismatch threshold
MIN_REMAINDER_M = 20.0   # uncovered agency pieces shorter than this are dropped
ALONGSIDE_M = 30.0       # partial overlap (both ways) that still counts as "same corridor"
OFFSET_MIN_M, OFFSET_MAX_M = 10.0, 30.0
PARK_INSIDE_FRAC = 0.5
ATTR_MIN_M = 2 * BUFFER_M  # attribute transfer: along-track overlap must exceed this ...
ATTR_CANON_FRAC = 0.5      # ... and cover this share of the canonical segment
DEFAULT_RANK = 1000
TRAIL_HIGHWAYS = {"path", "footway", "track", "bridleway"}
NON_TRAIL_FOOTWAYS = {"sidewalk", "crossing"}

KINDS = ("name_mismatch", "length_mismatch", "missing_in_osm", "missing_in_agency", "geometry_offset")

_NAME_KEYS = ("TRAILNAME", "TRAIL_NAME", "name", "NAME")
_PARK_KEYS = ("PARK", "Park")
_OPERATOR_KEYS = ("MANAGING_O", "OWNER", "operator")


@dataclass(frozen=True)
class Conflict:
    kind: str                     # one of KINDS
    segment_ids: tuple[str, ...]  # provisional canonical segment ids
    detail: str


@dataclass
class ReconcileResult:
    segments: list[Segment]
    conflicts: list[Conflict]


@dataclass
class _Canon:
    id: str
    wgs: LineString
    utm: LineString
    source_id: str
    redistributable: bool
    tags: dict
    refs: list[SourceRef]
    own_name: str | None
    agency: list[tuple] = field(default_factory=list)  # (-along_overlap_m, rank, seg id, attrs)

    def __post_init__(self):
        self.len = self.utm.length
        self.buf = self.utm.buffer(BUFFER_M)


def _rank(sid: str, sources: dict[str, Source]) -> int:
    s = sources.get(sid)
    if s is not None and s.geometry_rank is not None:
        return s.geometry_rank
    return 1 if sid.startswith("osm") else DEFAULT_RANK


def _redistributable(sid: str, sources: dict[str, Source]) -> bool:
    s = sources.get(sid)
    return s.redistribution == "yes" if s is not None else sid.startswith("osm")


def _clean(v) -> str | None:
    if v is None:
        return None
    v = str(v).strip()
    return None if not v or v.lower() in ("<null>", "null", "none") else v


def _first(tags: dict, keys) -> str | None:
    return next((c for c in (_clean(tags.get(k)) for k in keys) if c), None)


def _attrs(tags: dict) -> dict:
    return {"name": _first(tags, _NAME_KEYS), "park": _first(tags, _PARK_KEYS),
            "operator": _first(tags, _OPERATOR_KEYS)}


def normalize_name(name: str) -> str:
    s = re.sub(r"[^\w\s]", " ", name.casefold())
    return " ".join(w for w in s.split() if w not in ("trail", "trails"))


def _overlap(a_utm, a_buf, a_len, c: _Canon) -> tuple[float, float, float]:
    """(fraction of shorter inside the other's buffer, a-length inside c.buf, c-length inside a_buf)."""
    a_in = a_utm.intersection(c.buf).length
    c_in = c.utm.intersection(a_buf).length
    frac = (a_in / a_len) if a_len <= c.len else (c_in / c.len)
    return frac, a_in, c_in


def _span(c_utm: LineString, a_buf, a_utm: LineString) -> float:
    """Along-track overlap: extent, projected onto `a`, of the part of `c` inside a's buffer.

    ~equal to the overlap length for parallel lines, ~0 for perpendicular crossings."""
    total = 0.0
    for p in _lines_of(c_utm.intersection(a_buf)):
        total += abs(a_utm.project(shapely.Point(p.coords[0])) - a_utm.project(shapely.Point(p.coords[-1])))
    return total


def _directed_hausdorff(s: LineString, l: LineString) -> float:
    pts = shapely.points(np.asarray(shapely.segmentize(s, 2.0).coords))
    return float(shapely.distance(pts, l).max())


def _pieces(g: BaseGeometry) -> list[LineString]:
    if g.is_empty:
        return []
    merged = shapely.line_merge(g) if g.geom_type == "MultiLineString" else g
    return [p for p in _lines_of(merged) if p.length > MIN_REMAINDER_M]


def _ids(ids) -> tuple[str, ...]:
    return tuple(sorted(set(ids), key=_natural))


def reconcile(per_source: dict[str, list[Segment]], sources: dict[str, Source] | None = None,
              parks: list[tuple[str, BaseGeometry]] | None = None) -> ReconcileResult:
    """Merge per-source segments into canonical segments (see module docstring).

    sources: Source metadata by id (geometry_rank, redistribution); unknown ids fall back
    to OSM-first / OSM-redistributable. parks: (name, WGS84 polygon) for missing_in_agency;
    None -> report unmatched *named* OSM trail-type paths instead.
    """
    sources = sources or {}
    order = sorted(per_source, key=lambda sid: (_rank(sid, sources), sid))
    canon: list[_Canon] = []
    conflicts: list[Conflict] = []
    agency_matched: set[str] = set()
    base = order[0] if order else None

    for sid in order:
        segs = sorted(per_source[sid], key=lambda s: _natural(s.id))
        rank = _rank(sid, sources)
        redist = _redistributable(sid, sources)
        if sid == base:
            for s in segs:
                canon.append(_Canon(s.id, s.geometry, to_utm(s.geometry), sid, redist, dict(s.tags),
                                    list(s.source_refs), _attrs(s.tags)["name"]))
            continue
        tree = STRtree([c.buf for c in canon]) if canon else None
        names: dict[tuple[str, str], set[str]] = defaultdict(set)
        pending: dict[tuple[int, str], list] = defaultdict(list)  # (canon idx, agency trail key) -> pairs
        trails: dict[str, list] = {}  # norm name -> [label, agency utm lines, matched canon idx]
        new: list[tuple[Segment, dict, LineString]] = []
        for a in segs:
            a_utm = to_utm(a.geometry)
            a_len = a_utm.length
            if a_len <= 0:
                continue
            a_buf = a_utm.buffer(BUFFER_M)
            at = _attrs(a.tags)
            matched: list[tuple[int, float]] = []
            alongside: list[int] = []
            for i in (sorted(int(j) for j in tree.query(a_buf)) if tree is not None else []):
                c = canon[i]
                frac, a_in, c_in = _overlap(a_utm, a_buf, a_len, c)
                if frac >= MATCH_FRAC:
                    matched.append((i, a_in))
                elif a_in >= ALONGSIDE_M and c_in >= ALONGSIDE_M:
                    alongside.append(i)
            if at["name"]:
                trails.setdefault(normalize_name(at["name"]), [at["name"], [], set()])[1].append(a_utm)
            gkey = normalize_name(at["name"]) if at["name"] else f"#{a.id}"
            for i, a_in in matched:
                pending[(i, gkey)].append((a, at, a_utm, a_len, a_in, _span(canon[i].utm, a_buf, a_utm)))
            covered = (a_utm.intersection(unary_union([canon[i].buf for i, _ in matched])).length
                       if matched else 0.0)
            if covered / a_len >= MATCH_FRAC:
                continue
            cut = [canon[i].buf for i, _ in matched] + [canon[i].buf for i in alongside]
            rest = a_utm.difference(unary_union(cut)) if cut else a_utm
            new.extend((a, at, p) for p in _pieces(rest))

        # attribute transfer: only for substantial mutual (along-track) overlap, so crossings
        # that pass the 70%-of-shorter geometry test do not lend/steal names
        for (i, gkey), pairs in sorted(pending.items(), key=lambda kv: (kv[0][0], kv[0][1])):
            c = canon[i]
            along = sum(p[5] for p in pairs)
            if along < ATTR_MIN_M or along < ATTR_CANON_FRAC * c.len:
                continue
            at = pairs[0][1]
            agency_matched.add(c.id)
            if at["name"]:
                trails[gkey][2].add(i)
            c.agency.append((-along, rank, pairs[0][0].id, at))
            if (c.source_id == base and c.own_name and at["name"]
                    and normalize_name(c.own_name) != normalize_name(at["name"])):
                names[(c.own_name, at["name"])].add(c.id)
            for a, at_, a_utm, a_len, _, _ in pairs:
                c.refs.extend(r for r in a.source_refs if r not in c.refs)
                s_, l_ = (a_utm, c.utm) if a_len <= c.len else (c.utm, a_utm)
                hd = _directed_hausdorff(s_, l_)
                if OFFSET_MIN_M < hd <= OFFSET_MAX_M:
                    conflicts.append(Conflict("geometry_offset", (c.id,),
                                              f"{a.id} ({at_['name'] or 'unnamed'}) Hausdorff {hd:.1f} m"))

        # agency-only remainders: dedupe within this source, then append to canonical set
        added: list[_Canon] = []
        counter: dict[str, int] = defaultdict(int)
        for a, at, piece in new:
            p_buf = piece.buffer(BUFFER_M)
            cut = []
            for c in added:
                if not c.buf.intersects(piece):
                    continue
                frac, p_in, c_in = _overlap(piece, p_buf, piece.length, c)
                if frac >= MATCH_FRAC or (p_in >= ALONGSIDE_M and c_in >= ALONGSIDE_M):
                    cut.append(c.buf)
            for p in (_pieces(piece.difference(unary_union(cut))) if cut else [piece]):
                counter[a.id] += 1
                c = _Canon(f"{a.id}~{counter[a.id]}", to_wgs(p), p, sid, redist, dict(a.tags),
                           list(a.source_refs), at["name"], [(float("-inf"), rank, a.id, at)])
                added.append(c)
                conflicts.append(Conflict("missing_in_osm", (c.id,),
                                          f"{sid} '{at['name'] or 'unnamed'}' {p.length:.0f} m not in {base}"))
        canon.extend(added)

        for (own, ag), ids in names.items():
            conflicts.append(Conflict("name_mismatch", _ids(ids), f"{base} '{own}' vs {sid} '{ag}'"))
        for label, parts, idx in trails.values():  # named trails only
            if not idx:
                continue
            a_len = unary_union(parts).length  # dissolves duplicate features in the agency data
            c_len = sum(canon[i].len for i in idx)
            if abs(c_len - a_len) / a_len > LENGTH_TOL:
                conflicts.append(Conflict("length_mismatch", _ids(canon[i].id for i in idx),
                                          f"{sid} '{label}': agency {a_len:.0f} m vs matched canonical "
                                          f"{c_len:.0f} m ({(c_len - a_len) / a_len:+.0%})"))

    conflicts.extend(_missing_in_agency([c for c in canon if c.source_id == base], agency_matched, parks))
    segments = sorted((_finalize(c, base) for c in canon), key=lambda s: _natural(s.id))
    conflicts.sort(key=lambda c: (c.kind, c.segment_ids, c.detail))
    return ReconcileResult(segments, conflicts)


def _missing_in_agency(base_segs: list[_Canon], matched: set[str], parks) -> list[Conflict]:
    cands = [c for c in base_segs if c.id not in matched and c.tags.get("highway") in TRAIL_HIGHWAYS
             and c.tags.get("footway") not in NON_TRAIL_FOOTWAYS]
    groups: dict[str, list[_Canon]] = defaultdict(list)
    if parks is None:
        for c in cands:
            if c.own_name:
                groups[f"named OSM path '{c.own_name}' (no park polygons)"].append(c)
    else:
        polys = [to_utm(p) for _, p in parks]
        tree = STRtree(polys)
        for c in cands:
            best = max(((c.utm.intersection(polys[int(j)]).length, parks[int(j)][0])
                        for j in tree.query(c.utm)), default=(0.0, ""))
            if best[0] >= PARK_INSIDE_FRAC * c.len:
                groups[f"park '{best[1]}'"].append(c)
    return [Conflict("missing_in_agency", _ids(c.id for c in cs),
                     f"{label}: {len(cs)} segs, {sum(c.len for c in cs):.0f} m with no agency trail match")
            for label, cs in groups.items()]


def _finalize(c: _Canon, base: str | None) -> Segment:
    tags = dict(c.tags)
    tags["geometry_redistributable"] = c.redistributable
    if c.agency:
        ranked = sorted(c.agency, key=lambda t: t[:3])  # largest overlap, then best rank
        agency_srcs = sorted({r.source_id for r in c.refs if r.source_id != base})
        tags["agency:sources"] = ";".join(agency_srcs)
        for k in ("name", "park", "operator"):
            v = next((t[3][k] for t in ranked if t[3][k]), None)
            if v:
                tags[f"agency:{k}"] = v
        name = tags.get("agency:name")
        if name and c.source_id == base and c.own_name and c.own_name != name:
            tags["osm:name"] = c.own_name
        if name:
            tags["name"] = name
    return Segment(c.id, c.wgs, list(c.refs), tags)


def write_conflict_report(result: ReconcileResult, path: Path) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(conflict_report_text(result))
    return path


def conflict_report_text(result: ReconcileResult) -> str:
    """Markdown report: counts per kind, then one line per conflict grouped by kind."""
    by: dict[str, list[Conflict]] = defaultdict(list)
    for c in result.conflicts:
        by[c.kind].append(c)
    segs = result.segments
    km = sum(to_utm(s.geometry).length for s in segs) / 1000
    km_r = sum(to_utm(s.geometry).length for s in segs if s.tags.get("geometry_redistributable")) / 1000
    lines = ["# Reconcile conflict report", "",
             f"Canonical segments: {len(segs)} ({km:.1f} km; redistributable geometry {km_r:.1f} km)", "",
             "| kind | count |", "|---|---|"]
    lines += [f"| {k} | {len(by.get(k, []))} |" for k in KINDS]
    for k in KINDS:
        lines += ["", f"## {k}", ""]
        items = by.get(k, [])
        lines += [f"- {' '.join(f'`{i}`' for i in c.segment_ids)} — {c.detail}" for c in items] or ["(none)"]
    return "\n".join(lines) + "\n"
