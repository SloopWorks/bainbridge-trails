"""Group classified segments into named trails.

Grouping order (each segment joins at most one group in step 2; relations may overlap):
  1. OSM route relations (route=hiking|foot): member ways -> segments. Relations fetched
     without members (``out tags``) fall back to same-name segments inside the relation bbox.
     Same-name pieces from step 2 within GAP_M of a relation trail extend it (agency-only
     gap fills and renamed OSM pieces next to the route).
  2. Remaining named segments by normalized name, preferring the agency trail name
     (``agency:name``) over the OSM name; one trail per set of segments linked by
     contiguity (within GAP_M = 50 m; shared endpoints are 0 m, but agency matching and
     junction splitting leave short unnamed gaps) or by lying in the same agency park. (Agency names and OSM
     names are grouped in one pass, and an OSM name that reconcile replaced by exactly
     one agency name (``osm:name``) is an alias of it: doing them in separate passes split trails wherever
     agency matching skipped short pieces, e.g. at junctions.)
Names matching the rule's road-name pattern are never grouped. Trail ids are
``trail-<slug>``; same-slug trails get the park slug appended, then ``-2``, ``-3`` ...
(deterministic, by first segment id). ``trails_overrides.yaml`` merges near-duplicate
trails, renames/drops/edits trails, or adds custom trails from segment ids.
"""
from __future__ import annotations

import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

import shapely
from shapely.geometry import Point, box
from shapely.strtree import STRtree

from .classify import ClassifiedSegment
from .geo import to_utm, to_wgs
from .reconcile import normalize_name
from .trails_overrides import apply_overrides

MI = 1609.344
SNAP_M = 1.0       # endpoints closer than this are the same junction (walking order)
GAP_M = 50.0       # same-name pieces this close belong to one trail (agency matching leaves gaps)
TRAILHEAD_M = 2.0
ROAD_PROXY_SUBTYPES = {"sidewalk", "crossing", "walkway"}  # no roads in the OSM extract
OSM_SOURCE = "osm_overpass_trails"
MAP_STOPWORDS = {"park", "trail", "trails", "map", "nature", "preserve", "the"}


@dataclass(frozen=True)
class Relation:
    id: int
    name: str | None
    way_ids: tuple[str, ...] = ()        # "way/<id>", matching SourceRef.feature_id
    bounds: tuple[float, float, float, float] | None = None  # minlon, minlat, maxlon, maxlat


@dataclass
class Trail:
    id: str
    name: str
    manager: str | None
    segment_ids: list[str]
    official_length_mi: float | None
    computed_length_mi: float
    park: str | None
    trailheads: list[Point] = field(default_factory=list)
    official_map_url: str | None = None

    def to_dict(self) -> dict:
        return {"id": self.id, "name": self.name, "manager": self.manager, "segment_ids": self.segment_ids,
                "official_length_mi": self.official_length_mi,
                "computed_length_mi": round(self.computed_length_mi, 3), "park": self.park,
                "trailheads": [{"type": "Point", "coordinates": [round(p.x, 6), round(p.y, 6)]}
                               for p in self.trailheads],
                "official_map_url": self.official_map_url}


def slugify(s: str) -> str:
    s = re.sub(r"['’]", "", s.casefold())
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")


def map_key(s: str | None) -> str:
    if not s:
        return ""
    words = re.sub(r"[^\w\s]", " ", s.casefold()).split()
    return " ".join(w for w in words if w not in MAP_STOPWORDS)


def load_relations(osm_raw: Path) -> list[Relation]:
    data = json.loads(Path(osm_raw).read_text())
    out = []
    for e in data.get("elements", []):
        if e.get("type") != "relation" or (e.get("tags") or {}).get("route") not in ("hiking", "foot"):
            continue
        b = e.get("bounds")
        ways = tuple(f"way/{m['ref']}" for m in e.get("members") or [] if m.get("type") == "way")
        out.append(Relation(e["id"], (e.get("tags") or {}).get("name"), ways,
                            (b["minlon"], b["minlat"], b["maxlon"], b["maxlat"]) if b else None))
    return sorted(out, key=lambda r: r.id)


def map_urls_from_sources(sources) -> dict[str, str]:
    """{map_key(label): url} for per-park BIMPRD printable maps in sources.yaml."""
    out = {}
    for s in sorted(sources, key=lambda s: s.id):
        prefix = "BIMPRD printable map - "
        if s.kind == "pdf_map" and s.endpoint and s.name.startswith(prefix) and "Fold-Out" not in s.name:
            out[map_key(s.name[len(prefix):])] = s.endpoint
    return out


# -- graph helpers -------------------------------------------------------------------------

def _ends(s: ClassifiedSegment):
    u = to_utm(s.geometry)
    return Point(u.coords[0]), Point(u.coords[-1])


def _nodes(group: list[ClassifiedSegment]) -> list[tuple[int, int]]:
    """(start node, end node) per segment; endpoints within SNAP_M share a node."""
    pts = [p for s in group for p in _ends(s)]
    parent = list(range(len(pts)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    tree = STRtree(pts)
    for i, p in enumerate(pts):
        for j in sorted(int(j) for j in tree.query(p.buffer(SNAP_M))):
            if j != i and pts[j].distance(p) <= SNAP_M:
                parent[find(j)] = find(i)
    roots = [find(i) for i in range(len(pts))]
    return [(roots[2 * k], roots[2 * k + 1]) for k in range(len(group))]


def _components(group: list[ClassifiedSegment]) -> list[list[ClassifiedSegment]]:
    """Linked sets: geometries within GAP_M of each other, or in the same agency park."""
    utm = [to_utm(s.geometry) for s in group]
    parent = list(range(len(group)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    tree = STRtree(utm)
    for i, g in enumerate(utm):
        for j in (int(j) for j in tree.query(g.buffer(GAP_M))):
            if j != i and utm[j].distance(g) <= GAP_M:
                parent[find(j)] = find(i)
    first_in_park: dict[str, int] = {}
    for i, s in enumerate(group):
        if s.park:
            parent[find(i)] = find(first_in_park.setdefault(s.park, i))
    comps = defaultdict(list)
    for i, s in enumerate(group):
        comps[find(i)].append(s)
    return sorted((sorted(c, key=lambda s: s.id) for c in comps.values()), key=lambda c: c[0].id)


def _near(a: list[ClassifiedSegment], b: list[ClassifiedSegment]) -> bool:
    ub = shapely.union_all([to_utm(s.geometry) for s in b])
    return any(to_utm(s.geometry).distance(ub) <= GAP_M for s in a)


def _order(group: list[ClassifiedSegment]) -> tuple[list[ClassifiedSegment], list[Point]]:
    """Segments in walking order (greedy, smallest id first) + dangling endpoints (UTM)
    of redistributable segments."""
    group = sorted(group, key=lambda s: s.id)
    ends = _nodes(group)
    inc = defaultdict(list)
    for k, (a, b) in enumerate(ends):
        inc[a].append(k)
        inc[b].append(k)
    pts = {}
    for k, s in enumerate(group):
        a, b = _ends(s)
        pts.setdefault(ends[k][0], a)
        pts.setdefault(ends[k][1], b)
    # only endpoints of shippable geometry may surface (trailheads go into trails.json)
    dangling = [pts[n] for n in sorted(inc) if len(inc[n]) == 1 and group[inc[n][0]].geometry_redistributable]
    left = set(range(len(group)))
    order: list[int] = []
    while left:
        starts = [(min(e for e in inc[n] if e in left), n) for n in inc
                  if sum(e in left for e in inc[n]) == 1]
        k0, node = min(starts) if starts else (min(left), ends[min(left)][0])
        while True:
            cand = sorted(e for e in inc[node] if e in left)
            if not cand:
                break
            k = cand[0]
            left.discard(k)
            order.append(k)
            a, b = ends[k]
            node = b if node == a else a
    return [group[k] for k in order], dangling


# -- main ----------------------------------------------------------------------------------

def _road_like(name: str | None, pattern: str) -> bool:
    return bool(name and pattern and re.search(pattern, name, re.I))


def _label(names: list[str]) -> str:
    c = Counter(names)
    return sorted(c, key=lambda n: (-c[n], n))[0]


def build_trails(segments: list[ClassifiedSegment], relations: list[Relation] = (),
                 map_urls: dict[str, str] | None = None, overrides: dict | None = None,
                 road_name_pattern: str = "") -> list[Trail]:
    """Group segments into trails (see module docstring); sets each segment's trail_ids."""
    segs = sorted(segments, key=lambda s: s.id)
    by_id = {s.id: s for s in segs}
    groups: list[tuple[str, list[ClassifiedSegment]]] = []
    used: set[str] = set()
    rel_groups: list[tuple[str, int]] = []  # (name key, index into groups)

    # 1. OSM route relations
    ref_index = defaultdict(list)
    for s in segs:
        for fid in s.osm_ways:
            ref_index[fid].append(s)
    for rel in sorted(relations, key=lambda r: r.id):
        if not rel.name:
            continue
        if rel.way_ids:
            members = {s.id: s for w in rel.way_ids for s in ref_index.get(w, [])}
        else:
            bb = box(*rel.bounds) if rel.bounds else None
            key = normalize_name(rel.name)
            members = {s.id: s for s in segs
                       if any(normalize_name(n) == key for n in (s.name, s.tags.get("osm:name")) if n)
                       and (bb is None or s.geometry.intersects(bb))}
        if members:
            groups.append((rel.name, sorted(members.values(), key=lambda s: s.id)))
            rel_groups.append((normalize_name(rel.name) or rel.name.casefold(), len(groups) - 1))
            used.update(members)

    # 2+3. named segments (agency:name preferred) by normalized name; one trail per set of
    # segments linked by contiguity (GAP_M) or by lying in the same agency park
    def name_key(n: str) -> str:
        return normalize_name(n) or n.casefold()

    alias = defaultdict(set)  # OSM name -> agency names it was renamed to (reconcile osm:name)
    for s in segs:
        if s.tags.get("osm:name") and s.tags.get("agency:name"):
            alias[name_key(s.tags["osm:name"])].add(name_key(s.tags["agency:name"]))
    named = defaultdict(list)
    for s in segs:
        n = s.tags.get("agency:name") or s.name
        if s.id not in used and n and not _road_like(n, road_name_pattern):
            k = name_key(n)
            k = next(iter(alias[k])) if len(alias.get(k, ())) == 1 else k
            named[k].append(s)
    for nk in sorted(named):
        for comp in _components(named[nk]):
            # same-name pieces within GAP_M of a relation trail (e.g. agency-only gap fills)
            rel = next((gi for k, gi in rel_groups if k == nk and _near(comp, groups[gi][1])), None)
            if rel is not None:
                groups[rel] = (groups[rel][0], sorted(groups[rel][1] + comp, key=lambda s: s.id))
                continue
            agency = [s.tags["agency:name"] for s in comp if s.tags.get("agency:name")]
            groups.append((_label(agency or [s.name for s in comp]), comp))

    # custom trails from overrides
    overrides = overrides or {}
    for c in overrides.get("custom") or []:
        missing = [i for i in c["segment_ids"] if i not in by_id]
        if missing:
            raise ValueError(f"trails_overrides custom {c.get('id')}: unknown segment ids {missing}")
        groups.append((c["name"], [by_id[i] for i in dict.fromkeys(c["segment_ids"])]))  # dedupe, keep order

    road_proxy = [to_utm(s.geometry) for s in segs if s.subtype in ROAD_PROXY_SUBTYPES]
    road_tree = STRtree(road_proxy) if road_proxy else None
    urls = {map_key(k): v for k, v in (map_urls or {}).items()}

    def make(name: str, members: list[ClassifiedSegment], id_: str = "") -> Trail:
        ordered, dangling = _order(members)
        length = sum(s.length_m for s in ordered)
        mgr_len = Counter()
        for s in ordered:
            if s.manager:
                mgr_len[s.manager] += s.length_m
        park = _park(ordered)
        manager = sorted(mgr_len, key=lambda m: (-mgr_len[m], m))[0] if mgr_len else None
        heads = []
        if road_tree is not None:
            for p in dangling:
                near = [int(j) for j in road_tree.query(p.buffer(TRAILHEAD_M))]
                if any(road_proxy[j].distance(p) <= TRAILHEAD_M for j in near):
                    w = to_wgs(p)
                    heads.append(Point(round(w.x, 6), round(w.y, 6)))
        return Trail(id_, name, manager, [s.id for s in ordered], None, length / MI, park,
                     sorted(heads, key=lambda p: (p.x, p.y)), _map_url(urls, park, name))

    trails = [make(name, members) for name, members in groups]
    custom_ids = [c["id"] for c in overrides.get("custom") or []]
    _assign_ids(trails, custom_ids, len(trails) - len(custom_ids))
    trails = apply_overrides(trails, overrides,
                             lambda t, ids: make(t.name, [by_id[i] for i in ids], t.id))
    for s in segs:
        s.trail_ids = []
    for t in trails:
        for i in t.segment_ids:
            if t.id not in by_id[i].trail_ids:
                by_id[i].trail_ids.append(t.id)
    for s in segs:
        s.trail_ids.sort()
    return sorted(trails, key=lambda t: t.id)


def _map_url(urls: dict[str, str], park: str | None, name: str) -> str | None:
    for key in (map_key(park), map_key(name)):
        if not key:
            continue
        if key in urls:
            return urls[key]
        pref = sorted((k for k in urls if k and (key + " ").startswith(k + " ")), key=lambda k: (-len(k), k))
        if pref:
            return urls[pref[0]]
    return None


def _assign_ids(trails: list[Trail], custom_ids: list[str], n_auto: int) -> None:
    auto = trails[:n_auto]
    for t, cid in zip(trails[n_auto:], custom_ids):
        t.id = cid
    taken = set(custom_ids)
    base = {id(t): slugify(t.name) or "unnamed" for t in auto}
    count = Counter(base.values())
    cand = {}
    for t in auto:
        b = base[id(t)]
        cand[id(t)] = f"{b}-{slugify(t.park)}" if count[b] > 1 and t.park else b
    for t in sorted(auto, key=lambda t: (cand[id(t)], t.segment_ids[0])):
        c, n = f"trail-{cand[id(t)]}", 2
        while c in taken:
            c, n = f"trail-{cand[id(t)]}-{n}", n + 1
        t.id = c
        taken.add(c)


def _park(segs: list[ClassifiedSegment]) -> str | None:
    """Park holding the most length, if >= half the trail lies in parks."""
    park_len = Counter()
    for s in segs:
        if s.park:
            park_len[s.park] += s.length_m
    if not park_len or sum(park_len.values()) < 0.5 * sum(s.length_m for s in segs):
        return None
    return sorted(park_len, key=lambda p: (-park_len[p], p))[0]
