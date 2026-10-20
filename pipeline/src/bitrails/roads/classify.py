"""Classify canonical road segments, and the island-total inclusion rule for the roads goal.

Classification is data (category, ownership, maintainer, surface, speed, shoulder, ...); whether a
segment counts toward "every road on the island" is decided at evaluation time by a RoadRule loaded
from data/roads/inclusion.roads.yaml, so changing the goal never requires re-classifying.

Category (road type, first match wins):
  state_route   matched to WSDOT state route lines, or OSM ref/name says SR 305
  arterial      OSM primary/secondary (+links), COBI Rd_Class *Arterial, LAPR *Arterial
  collector     OSM tertiary (+link), COBI Collector, LAPR *Collector
  residential   OSM residential/unclassified/living_street/road, COBI Residential *, LAPR *Local Access
  lane          OSM highway=service that is named or service=alley (shared lanes, alleys)
  driveway      OSM service=driveway/parking_aisle/drive-through (excluded from the goal)
  unknown       nothing above matched (agency-only geometry with no usable class)

Ownership (who the road belongs to, independent of type; first source that answers wins):
  wsdot_state_route_lines        -> public_state
  wsdot_local_agency_public_road -> Jurisdiction: City/County/Private/BIMPRD/school district
  cobi_rd_streets                -> Maint (COBI / Private / Privately Maintained), Rd_Class=Private,
                                    Status, and the City's " PVT" name marker
  OSM access/foot tags           -> private when access=private|no
  name inference                 -> a segment no agency source matched inherits the ownership its
                                    own street's attested segments agree on (ownership_source
                                    "inferred:name"); without that it stays unknown and uncounted
LAPR outranks the City layer because it is the federal-reporting public-road inventory: it says
outright which centerlines are public and which are private. Disagreements between the two are
reported as `ownership_disagreement` rows in the summary, never silently averaged.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml
from shapely.geometry import LineString

from ..geo import to_utm
from ..segments import Segment
from .names import agency_name, base_key, clean, cobi_street_name, is_private_name, mismatch_kind

OSM_SOURCE = "osm_overpass_roads"
COBI_STREETS = "cobi_rd_streets"
LAPR = "wsdot_local_agency_public_road"
STATE_ROUTES = "wsdot_state_route_lines"

CATEGORIES = ("state_route", "arterial", "collector", "residential", "lane", "driveway", "unknown")
OWNERSHIPS = ("public_state", "public_city", "public_county", "public_parks", "public_school",
              "private", "unknown")
PUBLIC_OWNERSHIPS = tuple(o for o in OWNERSHIPS if o.startswith("public_"))
PUBLIC_ACCESS = ("yes", "permit", "unknown", "no")
CONFIDENCES = ("high", "medium", "low")

ARTERIAL_HW = {"primary", "secondary", "primary_link", "secondary_link", "trunk", "trunk_link",
               "motorway", "motorway_link"}
COLLECTOR_HW = {"tertiary", "tertiary_link"}
RESIDENTIAL_HW = {"residential", "unclassified", "living_street", "road"}
DRIVEWAY_SERVICES = {"driveway", "parking_aisle", "drive-through", "slipway", "emergency_access"}
NO_ACCESS = {"private", "no"}
YES_ACCESS = {"yes", "permissive", "designated", "public", "destination"}
ALLOWED = {"yes", "designated", "permissive", "destination"}

# WSDOT LAPR Jurisdiction -> ownership
LAPR_JURISDICTION = {
    "state": "public_state",
    "city": "public_city",
    "county": "public_county",
    "private": "private",
    "bi metro parks and rec dist": "public_parks",
    "bainbridge island sch dist": "public_school",
}
# City rd_Streets Maint / Status / Rd_Class values that mean private
COBI_PRIVATE = {"private", "privately maintained"}
COBI_CITY = {"cobi", "city of bainbridge island"}

SURFACE_ALIASES = {"asphalt": "paved", "concrete": "paved", "paved": "paved", "paving_stones": "paved",
                   "chipseal": "paved", "gravel": "gravel", "fine_gravel": "gravel", "compacted": "gravel",
                   "dirt": "dirt", "ground": "dirt", "earth": "dirt", "unpaved": "unpaved", "sand": "dirt"}


@dataclass
class RoadSegment:
    id: str
    geometry: LineString
    name: str | None                 # display name (OSM spelling preferred)
    agency_names: list[str]          # names the agency sources give the same centerline
    category: str
    ownership: str
    maintainer: str | None
    functional_class: str | None     # WSDOT federal functional class description
    surface: str | None              # paved | gravel | dirt | unpaved
    speed_mph: int | None
    lanes: int | None
    shoulder: str | None             # yes | no | left | right | both
    sidewalk: str | None
    bike: str | None                 # designated | yes | no
    row_width_ft: float | None
    ownership_source: str            # source id(s) that decided ownership, or inferred:name / osm / none
    public_access: str
    allowed_uses: list[str]
    sources: list[str]
    confidence: str
    geometry_redistributable: bool
    length_m: float
    road_ids: list[str] = field(default_factory=list)
    osm_ways: list[str] = field(default_factory=list)
    tags: dict = field(default_factory=dict)
    dropped: bool = False            # set by a `drop: true` override: mapped, never counted

    @property
    def is_public(self) -> bool:
        return self.ownership in PUBLIC_OWNERSHIPS


def _ref_props(s: Segment, source_id: str) -> dict | None:
    """Properties of the best feature from `source_id` behind this canonical segment."""
    for r in s.source_refs:
        if r.source_id == source_id and r.tags:
            return r.tags
    return None


def _int(v) -> int | None:
    v = clean(v)
    if v is None:
        return None
    digits = "".join(c for c in str(v) if c.isdigit())
    return int(digits) if digits else None


def _float(v) -> float | None:
    v = clean(v)
    try:
        return float(v) if v is not None else None
    except (TypeError, ValueError):
        return None


def _category(tags: dict, cobi: dict | None, lapr: dict | None, state: dict | None) -> str:
    hw = clean(tags.get("highway"))
    ref = (clean(tags.get("ref")) or "") + " " + (clean(tags.get("name")) or "")
    if state is not None or "SR 305" in ref or "State Highway 305" in ref:
        return "state_route"
    cls = (clean((cobi or {}).get("Rd_Class")) or "").casefold()
    ffc = (clean((lapr or {}).get("FederalFunctionalClassDesc")) or "").casefold()
    if hw in ARTERIAL_HW or "arterial" in cls or "arterial" in ffc:
        return "arterial"
    if hw in COLLECTOR_HW or cls == "collector" or "collector" in ffc:
        return "collector"
    if hw in RESIDENTIAL_HW or cls.startswith("residential") or "local access" in ffc:
        return "residential"
    if hw == "service":
        svc = (clean(tags.get("service")) or "").casefold()
        if svc in DRIVEWAY_SERVICES:
            return "driveway"
        return "lane"
    if cls == "private":           # agency-only private centerline with no OSM counterpart
        return "residential"
    return "unknown"


def _ownership(tags: dict, cobi: dict | None, lapr: dict | None,
               state: dict | None) -> tuple[str, str | None, str]:
    """(ownership, maintainer label, what decided it)."""
    if state is not None:
        return "public_state", "WSDOT", STATE_ROUTES
    if lapr is not None:
        j = (clean(lapr.get("Jurisdiction")) or "").casefold()
        own = LAPR_JURISDICTION.get(j)
        if own:
            label = clean(lapr.get("CityName")) or clean(lapr.get("Jurisdiction"))
            return own, ("City of Bainbridge Island" if own == "public_city" else label), LAPR
    if cobi is not None:
        maint = (clean(cobi.get("Maint")) or "").casefold()
        status = (clean(cobi.get("Status")) or "").casefold()
        cls = (clean(cobi.get("Rd_Class")) or "").casefold()
        if maint in COBI_CITY:
            return "public_city", "City of Bainbridge Island", COBI_STREETS
        if (maint in COBI_PRIVATE or status in COBI_PRIVATE or cls == "private"
                or is_private_name(cobi_street_name(cobi))):
            return "private", "private", COBI_STREETS
    access = (clean(tags.get("access")) or "").casefold()
    if access in NO_ACCESS:
        return "private", None, "osm"
    return "unknown", None, "none"


def _public_access(tags: dict, ownership: str) -> str:
    foot = (clean(tags.get("foot")) or "").casefold()
    access = (clean(tags.get("access")) or "").casefold()
    for v in (foot, access):
        if not v:
            continue
        if v in NO_ACCESS:
            return "no"
        if v == "permit":
            return "permit"
        if v in YES_ACCESS:
            return "yes"
    if ownership in PUBLIC_OWNERSHIPS:
        return "yes"
    return "unknown"


def _allowed_uses(tags: dict, category: str) -> list[str]:
    uses = []
    for use, key in (("foot", "foot"), ("bicycle", "bicycle")):
        v = (clean(tags.get(key)) or "").casefold()
        if v:
            if v in ALLOWED:
                uses.append(use)
            continue
        # no tag: every public road class here is walkable and rideable (no interstate on the island)
        if category != "driveway":
            uses.append(use)
    return uses


def _surface(tags: dict, cobi: dict | None) -> str | None:
    v = (clean(tags.get("surface")) or "").casefold()
    if v:
        return SURFACE_ALIASES.get(v, v)
    v = (clean((cobi or {}).get("Rd_Surface")) or "").casefold()
    return SURFACE_ALIASES.get(v, v or None)


def _display_name(tags: dict, agency: list[str]) -> str | None:
    """OSM's spelled-out name when there is one; otherwise the agency spelling."""
    osm = clean(tags.get("name"))
    if osm:
        return osm
    return agency[0] if agency else None


def classify(segments: list[Segment]) -> list[RoadSegment]:
    out = []
    for s in segments:
        tags = s.tags
        cobi = _ref_props(s, COBI_STREETS)
        lapr = _ref_props(s, LAPR)
        state = _ref_props(s, STATE_ROUTES)
        sources = sorted({r.source_id for r in s.source_refs})
        agency = []
        for sid in (COBI_STREETS, LAPR, STATE_ROUTES):
            props = {COBI_STREETS: cobi, LAPR: lapr, STATE_ROUTES: state}[sid]
            if props and (n := agency_name(sid, props)):
                if n not in agency:
                    agency.append(n)
        cat = _category(tags, cobi, lapr, state)
        own, maint, own_src = _ownership(tags, cobi, lapr, state)
        utm = to_utm(s.geometry)
        osm_backed = OSM_SOURCE in sources
        name = _display_name(tags, agency)
        if osm_backed and (lapr is not None or cobi is not None or state is not None):
            conf = "high"
        elif osm_backed and name:
            conf = "medium"
        else:
            conf = "low"
        out.append(RoadSegment(
            id=s.id, geometry=s.geometry, name=name, agency_names=agency, category=cat, ownership=own,
            maintainer=maint, ownership_source=own_src,
            functional_class=clean((lapr or {}).get("FederalFunctionalClassDesc")),
            surface=_surface(tags, cobi),
            speed_mph=_int(tags.get("maxspeed")) or _int((cobi or {}).get("Speed_Limi")),
            lanes=_int(tags.get("lanes")),
            shoulder=clean(tags.get("shoulder")),
            sidewalk=clean(tags.get("sidewalk")),
            bike=clean(tags.get("bicycle")),
            row_width_ft=_float((cobi or {}).get("Mini_width")),
            public_access=_public_access(tags, own),
            allowed_uses=_allowed_uses(tags, cat),
            sources=sources, confidence=conf,
            geometry_redistributable=bool(tags.get("geometry_redistributable")),
            length_m=utm.length, tags=dict(tags),
            osm_ways=sorted({r.feature_id for r in s.source_refs if r.source_id == OSM_SOURCE})))
    return out


def ownership_disagreements(segments: list[Segment]) -> list[tuple[str, str, str]]:
    """(segment id, LAPR verdict, City verdict) where the two agency sources disagree public/private."""
    out = []
    for s in segments:
        lapr, cobi = _ref_props(s, LAPR), _ref_props(s, COBI_STREETS)
        if not lapr or not cobi:
            continue
        a, _, _ = _ownership({}, None, lapr, None)
        b, _, _ = _ownership({}, cobi, None, None)
        if a != b and "unknown" not in (a, b):
            out.append((s.id, a, b))
    return sorted(out)


def name_mismatches(segments: list[RoadSegment]) -> list[tuple[str, str, str, str]]:
    """(segment id, OSM name, agency name, kind) where the sources name a street differently.

    kind (names.mismatch_kind): `type` = same words, different street type (Eagle Harbor Drive vs
    Road); `spelling` = one token off (Lovgreen vs Lovegren); `different` = genuinely other names,
    which is where a real error usually hides."""
    out = []
    for s in segments:
        osm = clean(s.tags.get("name"))
        for a in s.agency_names:
            if osm and a and (kind := mismatch_kind(osm, a)) != "same":
                out.append((s.id, osm, a, kind))
                break
    return sorted(out)


def name_mismatch_counts(segments: list[RoadSegment]) -> dict[str, int]:
    out: dict[str, int] = {}
    for _, _, _, kind in name_mismatches(segments):
        out[kind] = out.get(kind, 0) + 1
    return dict(sorted(out.items()))


AGREEMENT_FRAC = 0.8   # share of a street's attested length that must agree before it is inherited


def infer_unknown_ownership(segments: list[RoadSegment], agreement: float = AGREEMENT_FRAC) -> int:
    """Give `unknown` segments the ownership their own street's attested segments agree on.

    Junction splitting leaves short pieces that no agency centerline overlaps enough to match, so a
    public street can carry a few `unknown` metres in the middle. A segment inherits only when every
    attested segment of the same street (names.base_key) agrees to within `agreement` of their
    length, and the inheritance is recorded as ownership_source "inferred:name". Unnamed unknown
    segments are left alone: there is nothing to inherit from. Returns how many segments changed."""
    attested: dict[str, dict[str, float]] = {}
    for s in segments:
        if s.ownership == "unknown" or not s.name or s.ownership_source in ("none", "inferred:name"):
            continue
        by = attested.setdefault(base_key(s.name), {})
        by[s.ownership] = by.get(s.ownership, 0.0) + s.length_m
    changed = 0
    for s in segments:
        if s.ownership != "unknown" or not s.name:
            continue
        by = attested.get(base_key(s.name))
        if not by:
            continue
        total = sum(by.values())
        own, m = max(sorted(by.items()), key=lambda kv: kv[1])
        if total > 0 and m / total >= agreement:
            s.ownership = own
            s.ownership_source = "inferred:name"
            changed += 1
    return changed


# -- inclusion rule ---------------------------------------------------------------------

@dataclass(frozen=True)
class RoadRule:
    categories: frozenset[str]
    ownerships: frozenset[str]
    exclude_public_access: frozenset[str] = frozenset({"no"})
    count_unknown_ownership: bool = False

    def snapshot(self) -> dict:
        return {"categories": sorted(self.categories), "ownerships": sorted(self.ownerships),
                "exclude_public_access": sorted(self.exclude_public_access),
                "count_unknown_ownership": self.count_unknown_ownership}


def load_rule(path: Path) -> RoadRule:
    d = yaml.safe_load(Path(path).read_text()) or {}
    cats = set(d.get("categories") or [])
    owns = set(d.get("ownerships") or [])
    if unknown := cats - set(CATEGORIES):
        raise ValueError(f"roads inclusion rule: unknown categories {sorted(unknown)}")
    if unknown := owns - set(OWNERSHIPS):
        raise ValueError(f"roads inclusion rule: unknown ownerships {sorted(unknown)}")
    return RoadRule(frozenset(cats), frozenset(owns),
                    frozenset(d.get("exclude_public_access") or ["no"]),
                    bool(d.get("count_unknown_ownership", False)))


def is_included(s: RoadSegment, rule: RoadRule) -> bool:
    if s.dropped:
        return False
    if s.public_access in rule.exclude_public_access:
        return False
    if s.category not in rule.categories:
        return False
    if s.ownership in rule.ownerships:
        return True
    return s.ownership == "unknown" and rule.count_unknown_ownership


def island_total_m(segments: list[RoadSegment], rule: RoadRule, shippable_only: bool = False) -> float:
    seen: set[str] = set()
    total = 0.0
    for s in segments:
        if s.id in seen:
            continue
        seen.add(s.id)
        if is_included(s, rule) and (s.geometry_redistributable or not shippable_only):
            total += s.length_m
    return total
