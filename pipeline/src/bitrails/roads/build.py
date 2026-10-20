"""`bitroads build`: cached road sources -> canonical road segments + roads (offline, deterministic).

normalize -> reconcile -> assign stable ids (once) -> classify -> group into roads -> write:
  data/roads/canonical/segments.geojson         redistributable geometry only (OSM; repo safe)
  data/roads/canonical/private/agency_only.geojson   agency-only geometry (gitignored)
  data/roads/canonical/elevation.json           {segment_id: [[dist_m, elev_m], ...]} 20 m profiles
  data/roads/canonical/private/elevation_agency_only.json   same for agency-only segments
  data/roads/canonical/roads.json               roads + island totals + rule snapshot + QA counts
  data/roads/reports/conflicts.md               reconcile conflicts keyed by registry ids
Two inputs are shared with the trail dataset and read from data/raw/ (never written):
  osm_island_boundary/latest.json   island clip polygon (committed)
  usgs_3dep_dem_island/latest.tiff  DEM for elevation (gitignored; `bitrails fetch --only usgs_3dep_dem_island`)
Every source in REQUIRED_LINE_SOURCES must be cached or the build aborts before writing anything,
because dropping one would shrink the dataset and retire its registry ids forever. `--allow-missing`
is the dev escape hatch: it retires nothing and does not save the registry. All outputs are computed
in memory and written together (temp file + rename), so a failed build changes nothing.
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from ..boundary import REPO_ROOT, island_polygon
from ..build import atomic_write_all, feature_collection_text, segment_elevation
from ..elevation import load_dem, require_dem
from ..fetch import FETCHABLE_KINDS, FetchError, Source, _from_cache, load_sources
from ..geo import to_utm
from ..reconcile import Conflict, KINDS, ReconcileResult, conflict_report_text, reconcile
from ..registry import Registry
from ..segments import Segment, assign_ids, normalize_source
from .classify import (CATEGORIES, OWNERSHIPS, RoadRule, RoadSegment, classify, infer_unknown_ownership,
                       island_total_m, is_included, load_rule, name_mismatch_counts, name_mismatches,
                       ownership_disagreements)
from .group import Road, build_roads
from .overrides import apply_overrides, load_overrides
from .qa import qa_report_text

MI = 1609.344
DATA = REPO_ROOT / "data"
ROADS = DATA / "roads"
SHARED_CACHE = DATA / "raw"                      # boundary + DEM live with the trail dataset
DEFAULT_SOURCES = ROADS / "sources.roads.yaml"
DEFAULT_CACHE = ROADS / "raw"
DEFAULT_REGISTRY = ROADS / "registry" / "segments.json"
DEFAULT_RULE = ROADS / "inclusion.roads.yaml"
DEFAULT_OVERRIDES = ROADS / "roads_overrides.yaml"
DEFAULT_CANONICAL = ROADS / "canonical"
DEFAULT_REPORT = ROADS / "reports" / "conflicts.md"
DEFAULT_QA = ROADS / "reports" / "qa.md"

# line sources reconciled into the canonical road set, in geometry_rank order
ROAD_LINE_SOURCES = ("osm_overpass_roads", "cobi_rd_streets", "wsdot_local_agency_public_road",
                     "wsdot_state_route_lines")
REQUIRED_LINE_SOURCES = ROAD_LINE_SOURCES
OSM_SOURCE = ROAD_LINE_SOURCES[0]


class MissingSources(FetchError):
    def __init__(self, ids: list[str]):
        self.ids = sorted(ids)
        super().__init__("required road line source(s) not cached: " + ", ".join(self.ids) + ". Run: "
                         + "; ".join(f"bitroads fetch --only {i}" for i in self.ids)
                         + " (or --allow-missing <id> for a degraded build that keeps the registry untouched)")


def missing_required_sources(sources: dict[str, Source], cache_dir: Path) -> list[str]:
    out = []
    for sid in REQUIRED_LINE_SOURCES:
        if sid in sources:
            try:
                _from_cache(sources[sid], Path(cache_dir) / sid, "offline")
            except FetchError:
                out.append(sid)
    return out


def load_road_sources(sources: dict[str, Source], cache_dir: Path, island, log=print) -> dict[str, list[Segment]]:
    """Normalize the cached road line sources (ROAD_LINE_SOURCES only; point layers are ignored)."""
    per_source: dict[str, list[Segment]] = {}
    for sid in ROAD_LINE_SOURCES:
        src = sources.get(sid)
        if src is None or src.kind not in FETCHABLE_KINDS:
            continue
        try:
            raw = _from_cache(src, Path(cache_dir) / sid, "offline")
        except FetchError:
            continue
        segs = normalize_source(src, raw.path, island)
        if segs:
            per_source[sid] = segs
            km = sum(to_utm(s.geometry).length for s in segs) / 1000
            log(f"source {sid}: {len(segs)} segs, {km:.1f} km (rank {src.geometry_rank})")
    return per_source


def _feature(s: RoadSegment, rule: RoadRule, elev: dict | None = None) -> dict:
    props = {"id": s.id, "name": s.name, "agency_names": s.agency_names, "category": s.category,
             "ownership": s.ownership, "ownership_source": s.ownership_source, "maintainer": s.maintainer,
             "functional_class": s.functional_class,
             "surface": s.surface, "speed_mph": s.speed_mph, "lanes": s.lanes, "shoulder": s.shoulder,
             "sidewalk": s.sidewalk, "bike": s.bike, "row_width_ft": s.row_width_ft,
             "public_access": s.public_access, "allowed_uses": s.allowed_uses, "sources": s.sources,
             "confidence": s.confidence, "included": is_included(s, rule),
             "geometry_redistributable": s.geometry_redistributable, "road_ids": s.road_ids,
             "length_m": round(s.length_m, 1)}
    if elev is not None:
        props.update(elev[s.id])
    return {"type": "Feature",
            "geometry": {"type": "LineString",
                         "coordinates": [[round(x, 6), round(y, 6)] for x, y, *_ in s.geometry.coords]},
            "properties": dict(sorted(props.items()))}


def segment_features(segs: list[RoadSegment], rule: RoadRule,
                     elev: dict[str, dict] | None = None) -> tuple[list[dict], list[dict]]:
    public, private = [], []
    for s in sorted(segs, key=lambda s: s.id):
        (public if s.geometry_redistributable else private).append(_feature(s, rule, elev))
    return public, private


def profiles_text(profiles: dict[str, list]) -> str:
    body = ",\n".join(f"{json.dumps(k)}:{json.dumps(profiles[k], separators=(',', ':'))}"
                      for k in sorted(profiles))
    return "{\n" + body + "\n}\n"


def _stats(segs: list[RoadSegment], rule: RoadRule, attr: str) -> dict:
    keys = {"category": CATEGORIES, "ownership": OWNERSHIPS}.get(attr)
    out: dict[str, dict] = {k: {"segments": 0, "m": 0.0, "included_m": 0.0, "shippable_m": 0.0}
                            for k in (keys or ())}
    for s in segs:
        k = getattr(s, attr) or "unknown"
        st = out.setdefault(k, {"segments": 0, "m": 0.0, "included_m": 0.0, "shippable_m": 0.0})
        st["segments"] += 1
        st["m"] += s.length_m
        if is_included(s, rule):
            st["included_m"] += s.length_m
        if s.geometry_redistributable:
            st["shippable_m"] += s.length_m
    return {k: {n: (round(v, 1) if isinstance(v, float) else v) for n, v in st.items()}
            for k, st in sorted(out.items())}


def roads_document(segs: list[RoadSegment], roads: list[Road], rule: RoadRule,
                   qa: dict | None = None) -> dict:
    total = island_total_m(segs, rule)
    ship = island_total_m(segs, rule, shippable_only=True)
    access: dict[str, int] = defaultdict(int)
    conf: dict[str, int] = defaultdict(int)
    for s in segs:
        access[s.public_access] += 1
        conf[s.confidence] += 1
    return {
        "notes": {
            "goal": ("island_total_m is the denominator for 'every road on Bainbridge': centerline metres of "
                     "every segment the rule counts (see `rule`), each segment once. Private roads, named "
                     "service lanes and driveways are in the dataset but not in the total."),
            "elevation": ("per-segment gain_m/loss_m follow the stored orientation (20 m DEM samples, 2 m "
                          "hysteresis). A road's gain/loss is the sum over its segments, so climb_total_m "
                          "(gain + loss) is the direction-free number to show."),
            "island_total": ("island_total_shippable_m counts only segments whose geometry may ship "
                             "(OSM/ODbL). Agency-only geometry stays in data/roads/canonical/private/."),
        },
        "rule": rule.snapshot(),
        "island_total_m": round(total, 1),
        "island_total_mi": round(total / MI, 2),
        "island_total_shippable_m": round(ship, 1),
        "island_total_shippable_mi": round(ship / MI, 2),
        "dataset": {
            "segments": len(segs),
            "segments_shippable": sum(s.geometry_redistributable for s in segs),
            "segments_included": sum(is_included(s, rule) for s in segs),
            "length_m": round(sum(s.length_m for s in segs), 1),
            "roads": len(roads),
            "roads_counted": sum(r.counted for r in roads),
            "public_access": dict(sorted(access.items())),
            "confidence": dict(sorted(conf.items())),
            "categories": _stats(segs, rule, "category"),
            "ownerships": _stats(segs, rule, "ownership"),
        },
        "qa": qa or {},
        "roads": [r.to_dict() for r in sorted(roads, key=lambda r: r.id)],
    }


@dataclass
class BuildResult:
    segments: list[RoadSegment]
    roads: list[Road]
    document: dict
    conflicts: list[Conflict]


def run_build(sources_path: Path = DEFAULT_SOURCES, cache_dir: Path = DEFAULT_CACHE,
              boundary: Path | None = None, registry_path: Path = DEFAULT_REGISTRY,
              out_dir: Path = DEFAULT_CANONICAL, report: Path = DEFAULT_REPORT,
              rule_path: Path = DEFAULT_RULE, overrides_path: Path = DEFAULT_OVERRIDES,
              qa_report: Path = DEFAULT_QA, dem_cache: Path = SHARED_CACHE,
              allow_missing: list[str] | None = None, log=print) -> BuildResult:
    dem = load_dem(require_dem(dem_cache))      # fail before any work if the shared DEM is missing
    island = island_polygon(boundary)
    sources = {s.id: s for s in load_sources(sources_path)}
    allow = set(allow_missing or [])
    if allow - set(REQUIRED_LINE_SOURCES):
        raise ValueError(f"--allow-missing: not a required road line source: "
                         f"{sorted(allow - set(REQUIRED_LINE_SOURCES))}")
    missing = missing_required_sources(sources, cache_dir)
    if set(missing) - allow:
        raise MissingSources(sorted(set(missing) - allow))
    degraded = bool(missing)
    if degraded:
        print(f"WARN building without {', '.join(missing)}: registry NOT saved, no ids retired",
              file=sys.stderr)

    per_source = load_road_sources(sources, cache_dir, island, log)
    result = reconcile(per_source, sources, None)

    registry = Registry.load(registry_path)
    provisional = [s.id for s in result.segments]
    segments = assign_ids(result.segments, registry, finalize=not degraded)
    rekey = dict(zip(provisional, (s.id for s in segments)))
    conflicts = sorted((Conflict(c.kind, tuple(sorted({rekey[i] for i in c.segment_ids})), c.detail)
                        for c in result.conflicts), key=lambda c: (c.kind, c.segment_ids, c.detail))
    report_text = conflict_report_text(ReconcileResult(segments, conflicts))

    rule = load_rule(rule_path)
    segs = classify(segments)
    inferred = infer_unknown_ownership(segs)
    ov_stats = apply_overrides(segs, load_overrides(overrides_path))
    elev, profiles = segment_elevation(segs, dem)
    roads = build_roads(segs, rule, elev)       # also fills RoadSegment.road_ids
    disagreements = ownership_disagreements(segments)
    mismatches = name_mismatches(segs)
    qa = {
        "overrides_applied": ov_stats,
        "ownership_disagreements": len(disagreements),
        "ownership_inferred_from_name": inferred,
        "ownership_unknown_segments": sum(1 for s in segs if s.ownership == "unknown"),
        "name_mismatches": len(name_mismatches(segs)),
        "name_mismatch_kinds": name_mismatch_counts(segs),
        "conflicts": {k: sum(c.kind == k for c in conflicts) for k in KINDS},
        "unnamed_included_segments": sum(1 for s in segs if is_included(s, rule) and not s.name),
    }
    public, private = segment_features(segs, rule, elev)
    shippable = {s.id for s in segs if s.geometry_redistributable}
    doc = roads_document(segs, roads, rule, qa)
    files = {
        out_dir / "segments.geojson": feature_collection_text(public),
        out_dir / "private" / "agency_only.geojson": feature_collection_text(private),
        out_dir / "elevation.json": profiles_text({k: v for k, v in profiles.items() if k in shippable}),
        out_dir / "private" / "elevation_agency_only.json":
            profiles_text({k: v for k, v in profiles.items() if k not in shippable}),
        out_dir / "roads.json": json.dumps(doc, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        Path(report): report_text,
        Path(qa_report): qa_report_text(segs, roads, disagreements, mismatches, rule),
    }
    if not degraded:
        files.update(registry.render())
    atomic_write_all(files)
    return BuildResult(segs, roads, doc, conflicts)
