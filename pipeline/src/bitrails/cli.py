from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from .boundary import REPO_ROOT, island_polygon
from .build import RECONCILE_PARKS, MissingSources, load_line_sources, load_polygons, run_build
from .dist import run_dist
from .elevation import DemMissing
from .fetch import fetch_source, is_fetchable, load_sources
from .geo import to_utm
from .reconcile import KINDS, reconcile, write_conflict_report
from .registry import DEFAULT_REGISTRY
from .summary import write_summary

PARKS_SOURCE = RECONCILE_PARKS[0]


def cmd_fetch(args: argparse.Namespace) -> int:
    failures = []
    stale = []
    srcs = load_sources(args.sources)
    if args.only:
        unknown = sorted(set(args.only) - {s.id for s in srcs})
        if unknown:
            print(f"unknown source id(s): {', '.join(unknown)}", file=sys.stderr)
            return 2
        srcs = [s for s in srcs if s.id in set(args.only)]
    for src in srcs:
        if not is_fetchable(src) or not src.endpoint:
            print(f"skip   {src.id} ({src.kind})")
            continue
        try:
            r = fetch_source(src, args.cache_dir, offline=args.offline)
        except Exception as e:  # one bad source must not abort the run
            failures.append(src.id)
            print(f"FAIL   {src.id}: {e}", file=sys.stderr)
            continue
        if r.stale_reason:
            stale.append(src.id)
            print(f"WARN  {src.id}: endpoint failed ({r.stale_reason}), using cache from {r.fetched_at}",
                  file=sys.stderr)
        print(f"{'cached' if r.from_cache else 'ok    '} {src.id} {r.path} @ {r.fetched_at}")
    if stale:
        print(f"{len(stale)} stale (cache fallback): {', '.join(stale)}", file=sys.stderr)
    if failures:
        print(f"{len(failures)} failed: {', '.join(failures)}", file=sys.stderr)
        return 1
    return 0


def cmd_reconcile(args: argparse.Namespace) -> int:
    t0 = time.monotonic()
    island = island_polygon(args.boundary)
    sources = {s.id: s for s in load_sources(args.sources)}
    per_source = load_line_sources(sources, args.cache_dir, island)
    t1 = time.monotonic()
    parks = [(a.name, a.geometry) for a in load_polygons(args.cache_dir, *RECONCILE_PARKS)] or None
    if parks is None:
        print(f"WARN no {PARKS_SOURCE} polygons; missing_in_agency limited to named OSM paths", file=sys.stderr)
    result = reconcile(per_source, sources, parks)
    t2 = time.monotonic()
    segs = result.segments
    km_all = sum(to_utm(s.geometry).length for s in segs) / 1000
    km_r = sum(to_utm(s.geometry).length for s in segs if s.tags.get("geometry_redistributable")) / 1000
    print(f"canonical: {len(segs)} segs, {km_all:.1f} km all, {km_r:.1f} km redistributable")
    for sid in per_source:
        only = [s for s in segs if s.source_refs and s.source_refs[0].source_id == sid
                and not s.tags.get("geometry_redistributable")]
        if only:
            km = sum(to_utm(s.geometry).length for s in only) / 1000
            print(f"  agency-only from {sid}: {len(only)} segs, {km:.1f} km")
    for k in KINDS:
        print(f"conflict {k}: {sum(c.kind == k for c in result.conflicts)}")
    if args.report:  # provisional ids; the committed report is written by `build` only
        print(f"report: {write_conflict_report(result, args.report)}")
    print(f"runtime: normalize {t1 - t0:.1f} s, reconcile {t2 - t1:.1f} s")
    return 0


def cmd_build(args: argparse.Namespace) -> int:
    t0 = time.monotonic()
    allow = [i for chunk in args.allow_missing or [] for i in chunk.split(",") if i]
    try:
        r = run_build(args.sources, args.cache_dir, args.boundary, args.registry, args.out_dir, args.report,
                      args.rule, args.overrides, allow_missing=allow)
    except (MissingSources, DemMissing, ValueError) as e:
        print(f"build failed: {e}", file=sys.stderr)
        return 1
    d = r.document
    ds = d["dataset"]
    print(f"segments: {ds['segments']} ({ds['segments_shippable']} shippable), "
          f"{ds['length_m'] / 1000:.1f} km; included {ds['segments_included']}")
    for cat, st in ds["categories"].items():
        print(f"  {cat:16s} {st['segments']:5d} segs {st['m'] / 1000:7.1f} km  counted {st['included_m'] / 1000:6.1f} km")
    print(f"island total: {d['island_total_m'] / 1000:.1f} km ({d['island_total_mi']} mi); "
          f"shippable {d['island_total_shippable_m'] / 1000:.1f} km ({d['island_total_shippable_mi']} mi)")
    print(f"trails: {ds['trails']}; conflicts: {len(r.conflicts)}")
    print(f"runtime: {time.monotonic() - t0:.1f} s")
    return 0


def cmd_dist(args: argparse.Namespace) -> int:
    doc = run_dist(args.canonical, args.out_dir, args.sources)
    print(f"dist: version {doc['version']}, {len(doc['trails'])} trails -> {args.out_dir}")
    print(f"summary: {write_summary(args.canonical, args.summary, args.report, args.sources)}")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="bitrails")
    sub = p.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch", help="download raw source data into the cache")
    f.add_argument("--offline", action="store_true", help="use cache only")
    f.add_argument("--only", action="append", metavar="ID", help="fetch only this source id (repeatable)")
    f.add_argument("--sources", type=Path, default=REPO_ROOT / "data" / "sources.yaml")
    f.add_argument("--cache-dir", type=Path, default=REPO_ROOT / "data" / "raw")
    f.set_defaults(func=cmd_fetch)
    r = sub.add_parser("reconcile", help="normalize cached line sources, reconcile, print conflict summary")
    r.add_argument("--sources", type=Path, default=REPO_ROOT / "data" / "sources.yaml")
    r.add_argument("--cache-dir", type=Path, default=REPO_ROOT / "data" / "raw")
    r.add_argument("--boundary", type=Path, default=None, help="island boundary JSON (default: cached OSM)")
    r.add_argument("--report", type=Path, default=None,
                   help="also write a conflict report (provisional ids) here; data/reports/ is build's")
    r.set_defaults(func=cmd_reconcile)
    b = sub.add_parser("build", help="offline: reconcile, assign ids, classify, group trails, write data/canonical")
    b.add_argument("--sources", type=Path, default=REPO_ROOT / "data" / "sources.yaml")
    b.add_argument("--cache-dir", type=Path, default=REPO_ROOT / "data" / "raw")
    b.add_argument("--boundary", type=Path, default=None, help="island boundary JSON (default: cached OSM)")
    b.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    b.add_argument("--out-dir", type=Path, default=REPO_ROOT / "data" / "canonical")
    b.add_argument("--report", type=Path, default=REPO_ROOT / "data" / "reports" / "conflicts.md")
    b.add_argument("--rule", type=Path, default=REPO_ROOT / "data" / "inclusion.yaml")
    b.add_argument("--overrides", type=Path, default=REPO_ROOT / "data" / "trails_overrides.yaml")
    b.add_argument("--allow-missing", action="append", metavar="ID[,ID]",
                   help="build without this uncached line source (dev only): retires no registry ids and does "
                        "NOT save the registry")
    b.set_defaults(func=cmd_build)
    d = sub.add_parser("dist", help="offline: canonical -> data/dist (trails.json, tile input, ATTRIBUTION.md) + summary.md")
    d.add_argument("--canonical", type=Path, default=REPO_ROOT / "data" / "canonical")
    d.add_argument("--out-dir", type=Path, default=REPO_ROOT / "data" / "dist")
    d.add_argument("--sources", type=Path, default=REPO_ROOT / "data" / "sources.yaml")
    d.add_argument("--report", type=Path, default=REPO_ROOT / "data" / "reports" / "conflicts.md")
    d.add_argument("--summary", type=Path, default=REPO_ROOT / "data" / "reports" / "summary.md")
    d.set_defaults(func=cmd_dist)
    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
