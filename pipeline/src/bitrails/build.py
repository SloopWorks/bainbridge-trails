"""`bitrails build`: cached sources -> canonical segments + trails (offline, deterministic).

normalize -> reconcile -> assign stable ids (once) -> classify -> trails -> write:
  data/canonical/segments.geojson          redistributable geometry only (public repo safe)
  data/canonical/private/agency_only.geojson  non-redistributable geometry (gitignored)
  data/canonical/elevation.json            {segment_id: [[dist_m, elev_m], ...]} 20 m profiles (public segments)
  data/canonical/private/elevation_agency_only.json   same for agency-only segments (gitignored)
  data/canonical/trails.json               trails + island totals + rule snapshot + counts
Elevation (gain/loss/min/max per segment, gain/loss per trail) needs the cached DEM:
`bitrails fetch --only usgs_3dep_dem_island`; the build fails loudly without it.
  data/reports/conflicts.md                conflicts keyed by registry ids
Every line-geometry source in REQUIRED_LINE_SOURCES must be cached: a missing one aborts the build
(MissingSources; `bitrails fetch --only <id>`) before anything is written, because dropping a source
would shrink the dataset and retire its registry ids forever. `--allow-missing <id>` is a dev escape
hatch: the build proceeds without that source but retires nothing and does NOT save the registry
(WARN on stderr; ids of new segments are not persisted). All outputs plus the registry are computed in
memory and written together at the very end (temp file + rename), so a failed build changes nothing.
Island totals include agency-only segments (real trail, geometry not yet shippable);
`island_total_shippable_m` counts only segments whose geometry ships.
"""
from __future__ import annotations

import json
import os
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import shapely

from .boundary import REPO_ROOT, island_polygon
from .classify import CATEGORIES, Area, ClassifiedSegment, InclusionRule, classify, is_included, \
    island_total_m, load_rule
from .elevation import Dem, elevation_stats, load_dem, profile_line, require_dem, rounded_profile, trail_chain_totals
from .fetch import FETCHABLE_KINDS, FetchError, Source, _from_cache, load_sources
from .geo import to_utm
from .reconcile import Conflict, ReconcileResult, conflict_report_text, reconcile
from .registry import DEFAULT_REGISTRY, Registry
from .segments import Segment, assign_ids, normalize_source
from .trails import Trail, build_trails, load_relations, map_urls_from_sources
from .trails_overrides import load_overrides

MI = 1609.344
OSM_SOURCE = "osm_overpass_trails"
RECONCILE_PARKS = ("cobi_bimprd_parks", "NAME", "AGENCY")  # missing_in_agency check (as `bitrails reconcile`)
PARK_SOURCES = (RECONCILE_PARKS, ("cobi_city_parks", "NAME", None))
# line-geometry sources the build cannot silently do without (ids/totals depend on them)
REQUIRED_LINE_SOURCES = ("osm_overpass_trails", "cobi_sto_alignments", "bimprd_trails_agol_2022",
                         "bimprd_trails_2015_legacy")
PRESERVE_SOURCES = (("cobi_bilt_conserved_2024", "NAME", "Cons_Type"),)
DATA = REPO_ROOT / "data"


class MissingSources(FetchError):
    def __init__(self, ids: list[str]):
        self.ids = sorted(ids)
        super().__init__("required line source(s) not cached: " + ", ".join(self.ids) + ". Run: "
                         + "; ".join(f"bitrails fetch --only {i}" for i in self.ids)
                         + " (or pass --allow-missing <id> for a degraded build that keeps the registry untouched)")


def missing_required_sources(sources: dict[str, Source], cache_dir: Path) -> list[str]:
    out = []
    for sid in REQUIRED_LINE_SOURCES:
        if sid in sources:
            try:
                _from_cache(sources[sid], Path(cache_dir) / sid, "offline")
            except FetchError:
                out.append(sid)
    return out


def atomic_write_all(files: dict[Path, str]) -> None:
    """Write every file to a sibling temp file first, then rename them all into place.

    A failure while staging leaves every destination untouched."""
    staged: list[tuple[Path, Path]] = []
    try:
        for path, text in files.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_name(f".{path.name}.tmp{os.getpid()}")
            tmp.write_text(text)
            staged.append((tmp, path))
        for tmp, path in staged:
            os.replace(tmp, path)
    finally:
        for tmp, _ in staged:
            tmp.unlink(missing_ok=True)


def load_line_sources(sources: dict[str, Source], cache_dir: Path, island, log=print) -> dict[str, list[Segment]]:
    """Normalize every cached line source (points/polygons/relations yield nothing)."""
    per_source = {}
    for src in sources.values():
        if src.kind not in FETCHABLE_KINDS:
            continue
        try:
            raw = _from_cache(src, cache_dir / src.id, "offline")
        except FetchError:
            continue
        if raw.path.suffix not in (".json", ".geojson"):
            continue
        segs = normalize_source(src, raw.path, island)
        if segs:
            per_source[src.id] = segs
            km = sum(to_utm(s.geometry).length for s in segs) / 1000
            log(f"source {src.id}: {len(segs)} segs, {km:.1f} km (rank {src.geometry_rank})")
    return per_source


def load_polygons(cache_dir: Path, source_id: str, name_key: str, kind_key: str | None) -> list[Area]:
    """Polygons from a cached source as Areas (empty if not cached)."""
    try:
        raw = _from_cache(Source(source_id, source_id, "arcgis_rest", None), cache_dir / source_id, "offline")
    except FetchError:
        return []
    out = []
    for f in json.loads(raw.path.read_text()).get("features", []):
        g = f.get("geometry")
        if g and g.get("type") in ("Polygon", "MultiPolygon"):
            p = f.get("properties") or {}
            geom = shapely.make_valid(shapely.from_geojson(json.dumps(g)))
            out.append(Area(str(p.get(name_key) or "unnamed").strip(), geom,
                            str(p.get(kind_key) or "").strip() if kind_key else ""))
    return out


def _round_coords(geom) -> list:
    return [[round(x, 6), round(y, 6)] for x, y, *_ in geom.coords]


def _feature(s: ClassifiedSegment, rule: InclusionRule, elev: dict | None = None) -> dict:
    props = {"id": s.id, "category": s.category, "subtype": s.subtype, "manager": s.manager,
             "sources": s.sources, "confidence": s.confidence, "public_access": s.public_access,
             "allowed_uses": s.allowed_uses, "included": is_included(s, rule),
             "geometry_redistributable": s.geometry_redistributable, "name": s.name,
             "trail_ids": s.trail_ids, "park": s.park, "length_m": round(s.length_m, 1)}
    if elev is not None:
        props.update(elev[s.id])
    return {"type": "Feature", "geometry": {"type": "LineString", "coordinates": _round_coords(s.geometry)},
            "properties": dict(sorted(props.items()))}


def segment_features(segs: list[ClassifiedSegment], rule: InclusionRule,
                     elev: dict[str, dict] | None = None) -> tuple[list[dict], list[dict]]:
    """(redistributable features, agency-only features), each sorted by id. `elev` = per-segment stats."""
    public, private = [], []
    for s in sorted(segs, key=lambda s: s.id):
        (public if s.geometry_redistributable else private).append(_feature(s, rule, elev))
    return public, private


def feature_collection_text(features: list[dict]) -> str:
    """One feature per line: deterministic and diff-friendly."""
    body = ",\n".join(json.dumps(f, sort_keys=True, separators=(",", ":"), ensure_ascii=False) for f in features)
    return '{"type":"FeatureCollection","features":[\n' + body + "\n]}\n"


def write_feature_collection(features: list[dict], path: Path) -> Path:
    atomic_write_all({path: feature_collection_text(features)})
    return path


def segment_elevation(segs: list[ClassifiedSegment], dem: Dem) -> tuple[dict[str, dict], dict[str, list]]:
    """({id: gain/loss/min/max stats}, {id: rounded profile}) for every segment, both sorted by id."""
    stats, profiles = {}, {}
    for s in sorted(segs, key=lambda s: s.id):
        prof = profile_line(s.geometry, dem)
        stats[s.id] = elevation_stats(prof)
        profiles[s.id] = rounded_profile(prof)
    return stats, profiles


def profiles_text(profiles: dict[str, list]) -> str:
    """Sorted keys, one segment per line (diff-friendly)."""
    body = ",\n".join(f"{json.dumps(k)}:{json.dumps(profiles[k], separators=(',', ':'))}" for k in sorted(profiles))
    return "{\n" + body + "\n}\n"


def write_profiles(profiles: dict[str, list], path: Path) -> Path:
    atomic_write_all({path: profiles_text(profiles)})
    return path


def trail_elevation(trails: list[Trail], segs: list[ClassifiedSegment], profiles: dict[str, list]) -> dict[str, dict]:
    """{trail_id: gain_m/loss_m/climb_total_m} from each trail's ordered segments."""
    by_id = {s.id: s for s in segs}
    out = {}
    for t in trails:
        items = []
        for i in t.segment_ids:
            c = to_utm(by_id[i].geometry).coords
            items.append(((c[0][0], c[0][1]), (c[-1][0], c[-1][1]), profiles[i]))
        out[t.id] = trail_chain_totals(items)
    return out


def category_stats(segs: list[ClassifiedSegment], rule: InclusionRule) -> dict:
    stats = {c: {"segments": 0, "m": 0.0, "included_m": 0.0, "shippable_m": 0.0} for c in CATEGORIES}
    for s in segs:
        st = stats[s.category]
        st["segments"] += 1
        st["m"] += s.length_m
        if is_included(s, rule):
            st["included_m"] += s.length_m
        if s.geometry_redistributable:
            st["shippable_m"] += s.length_m
    return {c: {k: (round(v, 1) if isinstance(v, float) else v) for k, v in st.items()} for c, st in stats.items()}


def trails_document(segs: list[ClassifiedSegment], trails: list[Trail], rule: InclusionRule,
                    trail_elev: dict[str, dict] | None = None) -> dict:
    total = island_total_m(segs, rule)
    ship = island_total_m(segs, rule, shippable_only=True)
    access = defaultdict(int)
    for s in segs:
        access[s.public_access] += 1
    return {
        "notes": {
            "elevation": ("trail gain_m/loss_m: segments are oriented along the trail's walking order, 20 m DEM "
                          "profiles concatenated and 2 m hysteresis applied once per chain (new chain piece at "
                          "gaps > 50 m; pieces summed). climb_total_m = gain_m + loss_m, direction-free. "
                          "Agency-only segments contribute to trail totals."),
            "island_total": ("island_total_m counts every included segment, including agency-only segments whose "
                             "geometry is not redistributable (kept in data/canonical/private/, gitignored). "
                             "island_total_shippable_m counts only segments whose geometry ships in "
                             "segments.geojson."),
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
            "trails": len(trails),
            "public_access": dict(sorted(access.items())),
            "categories": category_stats(segs, rule),
        },
        "trails": [{**t.to_dict(), **(trail_elev[t.id] if trail_elev is not None else {})}
                   for t in sorted(trails, key=lambda t: t.id)],
    }


@dataclass
class BuildResult:
    segments: list[ClassifiedSegment]
    trails: list[Trail]
    document: dict
    conflicts: list[Conflict]


def run_build(sources_path: Path = DATA / "sources.yaml", cache_dir: Path = DATA / "raw",
              boundary: Path | None = None, registry_path: Path = DEFAULT_REGISTRY,
              out_dir: Path = DATA / "canonical", report: Path = DATA / "reports" / "conflicts.md",
              rule_path: Path = DATA / "inclusion.yaml", overrides_path: Path = DATA / "trails_overrides.yaml",
              allow_missing: list[str] | None = None, log=print) -> BuildResult:
    dem = load_dem(require_dem(cache_dir))  # fail before any work if the DEM was never fetched
    island = island_polygon(boundary)
    sources = {s.id: s for s in load_sources(sources_path)}
    allow = set(allow_missing or [])
    if allow - set(REQUIRED_LINE_SOURCES):
        raise ValueError(f"--allow-missing: not a required line source: {sorted(allow - set(REQUIRED_LINE_SOURCES))}")
    missing = missing_required_sources(sources, cache_dir)
    if set(missing) - allow:
        raise MissingSources(sorted(set(missing) - allow))
    degraded = bool(missing)
    if degraded:
        print(f"WARN building without {', '.join(missing)}: registry NOT saved, no ids retired; "
              "ids of new segments are not persisted", file=sys.stderr)
    per_source = load_line_sources(sources, cache_dir, island, log)
    by_src = {sid: load_polygons(cache_dir, sid, nk, kk) for sid, nk, kk in PARK_SOURCES}
    parks = [a for sid, *_ in PARK_SOURCES for a in by_src[sid]]
    preserves = [a for sid, nk, kk in PRESERVE_SOURCES for a in load_polygons(cache_dir, sid, nk, kk)]
    if not parks:
        print("WARN no park polygons cached; park/agency classification limited", file=sys.stderr)
    result = reconcile(per_source, sources, [(a.name, a.geometry) for a in by_src[RECONCILE_PARKS[0]]] or None)

    registry = Registry.load(registry_path)
    provisional = [s.id for s in result.segments]
    segments = assign_ids(result.segments, registry, finalize=not degraded)  # exactly once per build
    rekey = dict(zip(provisional, (s.id for s in segments)))
    conflicts = sorted((Conflict(c.kind, tuple(sorted({rekey[i] for i in c.segment_ids})), c.detail)
                        for c in result.conflicts), key=lambda c: (c.kind, c.segment_ids, c.detail))
    report_text = conflict_report_text(ReconcileResult(segments, conflicts))

    rule = load_rule(rule_path)
    segs = classify(segments, parks, preserves, OSM_SOURCE)
    osm = sources.get(OSM_SOURCE)
    relations = []
    if osm is not None:
        try:
            relations = load_relations(_from_cache(osm, cache_dir / OSM_SOURCE, "offline").path)
        except FetchError:
            pass
    trails = build_trails(segs, relations, map_urls_from_sources(sources.values()),
                          load_overrides(overrides_path), rule.road_name_pattern)

    elev, profiles = segment_elevation(segs, dem)
    public, private = segment_features(segs, rule, elev)
    shippable = {s.id for s in segs if s.geometry_redistributable}
    doc = trails_document(segs, trails, rule, trail_elevation(trails, segs, profiles))
    files = {
        out_dir / "segments.geojson": feature_collection_text(public),
        out_dir / "private" / "agency_only.geojson": feature_collection_text(private),
        out_dir / "elevation.json": profiles_text({k: v for k, v in profiles.items() if k in shippable}),
        out_dir / "private" / "elevation_agency_only.json":
            profiles_text({k: v for k, v in profiles.items() if k not in shippable}),
        out_dir / "trails.json": json.dumps(doc, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        Path(report): report_text,
    }
    if not degraded:
        files.update(registry.render())
    atomic_write_all(files)  # everything succeeded in memory: now write it all
    return BuildResult(segs, trails, doc, conflicts)
