"""Group canonical road segments into roads: the unit a user completes.

A road = every segment sharing one street-name key (names.base_key, so "Koura Rd NE" from the
agency data and "Northeast Koura Road" from OSM are one street) that hangs together geometrically:
endpoints within GAP_M of another piece of the same street. One name can therefore yield several
roads where the city reuses a name on disconnected stretches, and each gets its own suffixed id.
The display name is the spelling carrying the most length, which is OSM's wherever OSM maps it. Unnamed segments join no road (they still count toward island totals when the
inclusion rule says so) - as on the trail side, the app lists named things only.

Elevation per road is the sum of its segments' profiles (gain and loss both), not a walking-order
chain: a road is a corridor you cover in any order and direction, so a direction-free climb total
is the honest number. `climb_total_m` = gain + loss.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field

from ..geo import to_utm
from ..trails import slugify
from .classify import RoadSegment
from .names import base_key

GAP_M = 50.0          # same-name pieces this close belong to one road
MI = 1609.344
CLASS_ORDER = ("state_route", "arterial", "collector", "residential", "lane", "driveway", "unknown")


@dataclass
class Road:
    id: str
    name: str
    category: str
    ownership: str
    maintainer: str | None
    segment_ids: list[str]
    length_m: float
    gain_m: float = 0.0
    loss_m: float = 0.0
    climb_total_m: float = 0.0
    surface: str | None = None
    speed_mph: int | None = None
    public: bool = True
    counted: bool = False
    ownership_mix: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {"id": self.id, "name": self.name, "category": self.category, "ownership": self.ownership,
                "maintainer": self.maintainer, "segment_ids": list(self.segment_ids),
                "computed_length_mi": round(self.length_m / MI, 3), "gain_m": round(self.gain_m, 1),
                "loss_m": round(self.loss_m, 1), "climb_total_m": round(self.climb_total_m, 1),
                "surface": self.surface, "speed_mph": self.speed_mph, "public": self.public,
                "counted": self.counted,
                "ownership_mix": {k: round(v, 1) for k, v in sorted(self.ownership_mix.items())}}


def _components(segs: list[RoadSegment]) -> list[list[RoadSegment]]:
    """Split one name group into connected runs (endpoints within GAP_M)."""
    ends = []
    for s in segs:
        c = list(to_utm(s.geometry).coords)
        ends.append((c[0][:2], c[-1][:2]))
    parent = list(range(len(segs)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(len(segs)):
        for j in range(i + 1, len(segs)):
            if any((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 <= GAP_M ** 2
                   for a in ends[i] for b in ends[j]):
                parent[find(i)] = find(j)
    groups: dict[int, list[RoadSegment]] = defaultdict(list)
    for i, s in enumerate(segs):
        groups[find(i)].append(s)
    return [sorted(g, key=lambda s: s.id) for _, g in sorted(groups.items(),
            key=lambda kv: -sum(s.length_m for s in kv[1]))]


def _dominant(segs: list[RoadSegment], attr: str) -> str:
    """Attribute value carrying the most length ("" when every segment leaves it blank)."""
    by: dict[str, float] = defaultdict(float)
    for s in segs:
        by[getattr(s, attr) or ""] += s.length_m
    return max(sorted(by), key=lambda k: by[k])


def build_roads(segments: list[RoadSegment], rule=None, elevation: dict[str, dict] | None = None) -> list[Road]:
    """Named roads, sorted by id. `elevation` = {segment id: {gain_m, loss_m, ...}} from the DEM."""
    from .classify import is_included

    by_name: dict[str, list[RoadSegment]] = defaultdict(list)
    for s in segments:
        if s.name and s.category != "driveway" and not s.dropped:
            by_name[base_key(s.name)].append(s)
    roads: list[Road] = []
    for key in sorted(by_name):
        parts = _components(by_name[key])
        for n, part in enumerate(parts, start=1):
            name = max(sorted({s.name for s in part if s.name}),
                       key=lambda v: sum(s.length_m for s in part if s.name == v))
            slug = slugify(name) or slugify(key) or "road"
            rid = f"road-{slug}" if len(parts) == 1 else f"road-{slug}-{n}"
            mix: dict[str, float] = defaultdict(float)
            for s in part:
                mix[s.ownership] += s.length_m
            road = Road(
                id=rid, name=name, category=min((s.category for s in part), key=CLASS_ORDER.index),
                ownership=_dominant(part, "ownership"), maintainer=_dominant(part, "maintainer") or None,
                segment_ids=sorted(s.id for s in part), length_m=sum(s.length_m for s in part),
                surface=_dominant(part, "surface") or None,
                speed_mph=max((s.speed_mph for s in part if s.speed_mph), default=None),
                public=any(s.is_public for s in part),
                counted=bool(rule is not None and any(is_included(s, rule) for s in part)),
                ownership_mix=dict(mix))
            if elevation:
                road.gain_m = sum(elevation.get(s.id, {}).get("gain_m", 0.0) for s in part)
                road.loss_m = sum(elevation.get(s.id, {}).get("loss_m", 0.0) for s in part)
                road.climb_total_m = road.gain_m + road.loss_m
            roads.append(road)
    by_seg: dict[str, list[str]] = defaultdict(list)
    for r in roads:
        for i in r.segment_ids:
            by_seg[i].append(r.id)
    for s in segments:
        s.road_ids = sorted(by_seg.get(s.id, []))
    return sorted(roads, key=lambda r: r.id)
