"""Classify canonical segments and apply the island-total inclusion rule.

Classification is data (category, manager, sources, confidence, public_access,
allowed_uses); whether a segment counts toward the island total is decided at
evaluation time by an `InclusionRule` loaded from data/inclusion.yaml, so changing
the rule never requires re-classifying.

Category precedence (first match wins):
  seasonal_closed  OSM foot=no, access=no (unless foot allowed), disused/abandoned, seasonal, or seasonal opening_hours
  planned          agency-only geometry from Sound to Olympics alignments (not built / not in OSM)
  multi_use_path   highway=cycleway, or OSM-backed and matched to STO alignments
  connector        footway=sidewalk|crossing|traffic_island|link|access_aisle (subtype = that value),
                   or paved unnamed footway/steps/pedestrian outside parks/preserves (subtype = walkway)
  agency_trail     matched to agency trail data (agency:sources present)
  informal         OSM informal=yes
  agency_trail     >= 50% inside an agency park polygon
  preserve_trail   >= 50% inside a Land Trust conserved polygon (fee owned / assisted / other)
  easement         >= 50% inside a Land Trust *conservation easement* polygon (private land)
  multi_use_path   highway=path with bicycle=designated and foot allowed
  beach_access     name/tags indicate beach, shore, landing or road/street end
  informal         unnamed
  connector        anything else (named path outside parks; subtype = named_path)
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml
from shapely.geometry import LineString
from shapely.geometry.base import BaseGeometry
from shapely.strtree import STRtree

from .geo import to_utm
from .segments import Segment

CATEGORIES = ("agency_trail", "preserve_trail", "multi_use_path", "connector", "beach_access",
              "easement", "informal", "seasonal_closed", "planned")
CONFIDENCES = ("high", "medium", "low")
PUBLIC_ACCESS = ("yes", "permit", "unknown", "no")

OSM_SOURCE = "osm_overpass_trails"
STO_SOURCE = "cobi_sto_alignments"
INSIDE_FRAC = 0.5

CONNECTOR_FOOTWAYS = {"sidewalk", "crossing", "traffic_island", "link", "access_aisle"}
UNCOUNTED_CONNECTORS = CONNECTOR_FOOTWAYS | {"walkway"}
PAVED = {"concrete", "paved", "asphalt", "paving_stones", "concrete:plates"}
WALKWAY_HIGHWAYS = {"footway", "steps", "pedestrian"}
CLOSED_ACCESS = {"no"}
NO_ACCESS = {"private", "no"}
YES_ACCESS = {"yes", "permissive", "designated", "public"}
ALLOWED = {"yes", "designated", "permissive"}
BEACH_RE = re.compile(r"\b(beach|shore|shoreline|landing|waterfront access|water access|road end|street end)\b", re.I)
SEASONAL_HOURS_RE = re.compile(r"\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b", re.I)

MANAGER_ALIASES = {
    "bimprd": "BIMPRD",
    "bainbridge island metropolitan parks and rec district": "BIMPRD",
    "bainbridge island metro park & recreation district": "BIMPRD",
    "cobi": "City of Bainbridge Island",
    "city of bainbridge island": "City of Bainbridge Island",
    "private": "private",
    "kpud": "Kitsap PUD",
}
BILT = "Bainbridge Island Land Trust"


@dataclass
class ClassifiedSegment:
    id: str
    geometry: LineString
    name: str | None
    category: str
    subtype: str | None
    manager: str | None
    sources: list[str]
    confidence: str
    public_access: str
    allowed_uses: list[str]
    geometry_redistributable: bool
    length_m: float
    park: str | None = None
    tags: dict = field(default_factory=dict)
    osm_ways: list[str] = field(default_factory=list)   # OSM feature ids ("way/<id>") behind it
    trail_ids: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class Area:
    name: str
    geometry: BaseGeometry          # WGS84 polygon
    kind: str = ""                  # parks: AGENCY; preserves: Cons_Type


class _Index:
    def __init__(self, areas: list[Area] | None):
        self.areas = list(areas or [])
        self.utm = [to_utm(a.geometry) for a in self.areas]
        self.tree = STRtree(self.utm) if self.utm else None

    def best(self, utm: LineString) -> tuple[Area | None, float]:
        """Area holding the largest share of `utm`, and that share."""
        if self.tree is None or utm.length <= 0:
            return None, 0.0
        best = (0.0, -1)
        for j in sorted(int(i) for i in self.tree.query(utm)):
            inside = utm.intersection(self.utm[j]).length
            if inside > best[0]:
                best = (inside, j)
        return (self.areas[best[1]], best[0] / utm.length) if best[1] >= 0 else (None, 0.0)


def _clean(v) -> str | None:
    if v is None:
        return None
    v = str(v).strip()
    return None if not v or v.lower() in ("<null>", "null", "none") else v


def _manager_alias(v: str | None) -> str | None:
    v = _clean(v)
    return MANAGER_ALIASES.get(v.casefold(), v) if v else None


def _public_access(t: dict, in_area: bool, agency: bool) -> str:
    foot = (_clean(t.get("foot")) or "").lower()
    access = (_clean(t.get("access")) or "").lower()
    for v in (foot, access):
        if not v:
            continue
        if v in NO_ACCESS:
            return "no"
        if v == "permit":
            return "permit"
        if v in YES_ACCESS:
            return "yes"
        # unrecognised (customers/destination/discouraged): keep looking, so access=private|no still wins
    return "yes" if (in_area or agency) else "unknown"


def _yn(v) -> bool | None:
    v = (_clean(v) or "").upper()
    return True if v == "Y" else False if v == "N" else None


def _allowed_uses(t: dict) -> list[str]:
    hw = t.get("highway")
    uses = []
    for use, osm_key, agency_key, default in (
            ("foot", "foot", "HIKING_ALL", hw in ("path", "footway", "steps", "track", "pedestrian", "bridleway")),
            ("bicycle", "bicycle", "BICYCLES_A", hw == "cycleway"),
            ("horse", "horse", "HORSES_ALL", hw == "bridleway")):
        osm = (_clean(t.get(osm_key)) or "").lower()
        if osm:
            ok = osm in ALLOWED
        elif (ag := _yn(t.get(agency_key))) is not None:
            ok = ag
        else:
            ok = default or (use == "foot" and hw is None)  # agency-only lines: assume walkable
        if ok:
            uses.append(use)
    return uses


def _seasonal_closed(t: dict) -> bool:
    foot = (_clean(t.get("foot")) or "").lower()
    if foot == "no" or ((_clean(t.get("access")) or "").lower() in CLOSED_ACCESS and foot not in ALLOWED):
        return True
    if any(_clean(t.get(k)) for k in ("disused", "abandoned", "seasonal", "disused:highway", "abandoned:highway")):
        return True
    hours = _clean(t.get("opening_hours")) or ""
    return bool(SEASONAL_HOURS_RE.search(hours))


def classify(segments: list[Segment], parks: list[Area] | None = None,
             preserves: list[Area] | None = None, base_source: str = OSM_SOURCE) -> list[ClassifiedSegment]:
    """Attach classification attributes to reconciled segments (see module docstring)."""
    park_idx, pres_idx = _Index(parks), _Index(preserves)
    out = []
    for s in segments:
        t = s.tags
        utm = to_utm(s.geometry)
        sources = sorted({r.source_id for r in s.source_refs})
        osm_backed = base_source in sources
        agency_srcs = [x for x in sources if x != base_source]
        agency = bool(agency_srcs) and any(x != STO_SOURCE for x in agency_srcs)
        name = _clean(t.get("name"))
        park, pf = park_idx.best(utm)
        pres, rf = pres_idx.best(utm)
        in_park = park is not None and pf >= INSIDE_FRAC
        in_pres = pres is not None and rf >= INSIDE_FRAC
        easement_pres = in_pres and "easement" in pres.kind.lower()
        hw = t.get("highway")
        fw = _clean(t.get("footway"))
        subtype = None

        if osm_backed and _seasonal_closed(t):
            cat = "seasonal_closed"
        elif not osm_backed and sources == [STO_SOURCE]:
            cat = "planned"
        elif hw == "cycleway" or (osm_backed and STO_SOURCE in sources):
            cat = "multi_use_path"
        elif fw in CONNECTOR_FOOTWAYS:
            cat, subtype = "connector", fw
        elif (osm_backed and not agency and not name and hw in WALKWAY_HIGHWAYS
              and (_clean(t.get("surface")) or "") in PAVED and not in_park and not in_pres):
            cat, subtype = "connector", "walkway"
        elif agency:
            cat = "agency_trail"
        elif (_clean(t.get("informal")) or "").lower() == "yes":
            cat = "informal"
        elif in_park:
            cat = "agency_trail"
        elif in_pres and not easement_pres:
            cat = "preserve_trail"
        elif easement_pres:
            cat = "easement"
        elif (hw == "path" and (_clean(t.get("bicycle")) or "") == "designated"
              and (_clean(t.get("foot")) or "designated") in ALLOWED):
            cat = "multi_use_path"
        elif BEACH_RE.search(name or "") or _clean(t.get("natural")) == "beach":
            cat = "beach_access"
        elif not name:
            cat = "informal"
        else:
            cat, subtype = "connector", "named_path"

        manager = (_manager_alias(t.get("agency:operator")) or _manager_alias(t.get("operator"))
                   or (_manager_alias(park.kind) if in_park else None)
                   or (BILT if in_pres and not easement_pres else None))
        if not osm_backed:
            confidence = "low"
        elif agency:
            confidence = "high"
        elif name or in_park or in_pres:
            confidence = "medium"
        else:
            confidence = "low"
        out.append(ClassifiedSegment(
            id=s.id, geometry=s.geometry, name=name, category=cat, subtype=subtype, manager=manager,
            sources=sources, confidence=confidence,
            public_access=_public_access(t, in_park or (in_pres and not easement_pres), agency),
            allowed_uses=_allowed_uses(t),
            geometry_redistributable=bool(t.get("geometry_redistributable")),
            length_m=utm.length, park=park.name if in_park else None, tags=dict(t),
            osm_ways=sorted({r.feature_id for r in s.source_refs if r.source_id == base_source})))
    return out


# -- inclusion rule ---------------------------------------------------------------------

@dataclass(frozen=True)
class InclusionRule:
    categories: frozenset[str]
    count_named_connectors: bool = True
    road_name_pattern: str = ""
    exclude_public_access: frozenset[str] = frozenset({"no"})

    def snapshot(self) -> dict:
        return {"categories": sorted(self.categories), "count_named_connectors": self.count_named_connectors,
                "road_name_pattern": self.road_name_pattern,
                "exclude_public_access": sorted(self.exclude_public_access)}


def load_rule(path: Path) -> InclusionRule:
    d = yaml.safe_load(Path(path).read_text()) or {}
    cats = set(d.get("categories") or [])
    unknown = cats - set(CATEGORIES)
    if unknown:
        raise ValueError(f"inclusion rule: unknown categories {sorted(unknown)}")
    conn = d.get("connector") or {}
    return InclusionRule(frozenset(cats), bool(conn.get("count_named", True)),
                         str(conn.get("road_name_pattern") or ""),
                         frozenset(d.get("exclude_public_access") or ["no"]))


def road_like(name: str | None, rule: InclusionRule) -> bool:
    return bool(name and rule.road_name_pattern and re.search(rule.road_name_pattern, name, re.I))


def is_included(segment: ClassifiedSegment, rule: InclusionRule) -> bool:
    """Whether a segment counts toward the island total under `rule`.

    Connectors count only if rule.count_named_connectors, the subtype is not a
    sidewalk/crossing/traffic_island/link/access_aisle/walkway, and the connector carries a
    trail-ish name (not matching rule.road_name_pattern) or belongs to a trail grouping.
    """
    if segment.public_access in rule.exclude_public_access:
        return False
    if segment.category in rule.categories:
        return True
    if segment.category == "connector" and rule.count_named_connectors:
        if segment.subtype in UNCOUNTED_CONNECTORS:  # sidewalks/crossings/walkways never count
            return False
        return bool(segment.trail_ids) or bool(segment.name and not road_like(segment.name, rule))
    return False


def island_total_m(segments: list[ClassifiedSegment], rule: InclusionRule, shippable_only: bool = False) -> float:
    """Total length of unique included segments (each segment id counted once)."""
    seen: set[str] = set()
    total = 0.0
    for s in segments:
        if s.id in seen:
            continue
        seen.add(s.id)
        if is_included(s, rule) and (s.geometry_redistributable or not shippable_only):
            total += s.length_m
    return total
