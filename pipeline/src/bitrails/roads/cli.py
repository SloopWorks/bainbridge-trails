"""`bitroads` CLI: fetch | reconcile | build | dist for the road dataset.

Run it with `uv run --project pipeline python -m bitrails.roads <cmd>` (or scripts/bitroads).
Mirrors the `bitrails` CLI, with its own sources file, cache, registry and output tree under
data/roads/, so a roads run can never write the trail dataset.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from ..boundary import island_polygon
from ..elevation import DemMissing
from ..fetch import fetch_source, is_fetchable, load_sources
from ..geo import to_utm
from ..reconcile import KINDS, reconcile
from .build import (DEFAULT_CACHE, DEFAULT_CANONICAL, DEFAULT_OVERRIDES, DEFAULT_QA, DEFAULT_REGISTRY,
                    DEFAULT_REPORT, DEFAULT_RULE, DEFAULT_SOURCES, MissingSources, ROADS, SHARED_CACHE,
                    load_road_sources, run_build)
from .dist import run_dist
from .summary import write_summary


def cmd_fetch(args: argparse.Namespace) -> int:
    srcs = load_sources(args.sources)
    if args.only:
        unknown = sorted(set(args.only) - {s.id for s in srcs})
        if unknown:
            print(f"unknown source id(s): {', '.join(unknown)}", file=sys.stderr)
            return 2
        srcs = [s for s in srcs if s.id in set(args.only)]
    failures, stale = [], []
    for src in srcs:
        if not is_fetchable(src) or not src.endpoint:
            print(f"skip   {src.id} ({src.kind})")
            continue
        try:
            r = fetch_source(src, args.cache_dir, offline=args.offline)
        except Exception as e:
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
    per_source = load_road_sources(sources, args.cache_dir, island)
    t1 = time.monotonic()
    result = reconcile(per_source, sources, None)
    segs = result.segments
    km = sum(to_utm(s.geometry).length for s in segs) / 1000
    km_r = sum(to_utm(s.geometry).length for s in segs if s.tags.get("geometry_redistributable")) / 1000
    print(f"canonical: {len(segs)} segs, {km:.1f} km all, {km_r:.1f} km redistributable")
    for k in KINDS:
        print(f"conflict {k}: {sum(c.kind == k for c in result.conflicts)}")
    print(f"runtime: normalize {t1 - t0:.1f} s, reconcile {time.monotonic() - t1:.1f} s")
    return 0


def cmd_build(args: argparse.Namespace) -> int:
    t0 = time.monotonic()
    allow = [i for chunk in args.allow_missing or [] for i in chunk.split(",") if i]
    try:
        r = run_build(args.sources, args.cache_dir, args.boundary, args.registry, args.out_dir,
                      args.report, args.rule, args.overrides, args.qa_report, args.dem_cache,
                      allow_missing=allow)
    except (MissingSources, DemMissing, ValueError) as e:
        print(f"build failed: {e}", file=sys.stderr)
        return 1
    d, ds = r.document, r.document["dataset"]
    print(f"segments: {ds['segments']} ({ds['segments_shippable']} shippable), "
          f"{ds['length_m'] / 1000:.1f} km; included {ds['segments_included']}")
    for cat, st in ds["categories"].items():
        if st["segments"]:
            print(f"  {cat:12s} {st['segments']:5d} segs {st['m'] / 1000:7.1f} km  "
                  f"counted {st['included_m'] / 1000:6.1f} km")
    for own, st in ds["ownerships"].items():
        if st["segments"]:
            print(f"  {own:14s} {st['segments']:5d} segs {st['m'] / 1000:7.1f} km  "
                  f"counted {st['included_m'] / 1000:6.1f} km")
    print(f"island road total: {d['island_total_m'] / 1000:.1f} km ({d['island_total_mi']} mi); "
          f"shippable {d['island_total_shippable_m'] / 1000:.1f} km ({d['island_total_shippable_mi']} mi)")
    print(f"roads: {ds['roads']} ({ds['roads_counted']} counted); conflicts: {len(r.conflicts)}; qa: {d['qa']}")
    print(f"runtime: {time.monotonic() - t0:.1f} s")
    return 0


def cmd_dist(args: argparse.Namespace) -> int:
    doc = run_dist(args.canonical, args.out_dir, args.sources, args.cache_dir, args.boundary)
    print(f"dist: version {doc['version']}, {len(doc['roads'])} roads -> {args.out_dir}")
    print(f"summary: {write_summary(args.canonical, args.summary, args.sources, args.rule, doc['roads'])}")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="bitroads", description="Bainbridge Island road dataset pipeline")
    sub = p.add_subparsers(dest="cmd", required=True)

    f = sub.add_parser("fetch", help="download raw road source data into data/roads/raw")
    f.add_argument("--offline", action="store_true", help="use cache only")
    f.add_argument("--only", action="append", metavar="ID", help="fetch only this source id (repeatable)")
    f.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    f.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE)
    f.set_defaults(func=cmd_fetch)

    r = sub.add_parser("reconcile", help="normalize cached road sources, reconcile, print conflict summary")
    r.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    r.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE)
    r.add_argument("--boundary", type=Path, default=None, help="island boundary JSON (default: shared OSM cache)")
    r.set_defaults(func=cmd_reconcile)

    b = sub.add_parser("build", help="offline: reconcile, assign ids, classify, group roads, write data/roads/canonical")
    b.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    b.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE)
    b.add_argument("--boundary", type=Path, default=None)
    b.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    b.add_argument("--out-dir", type=Path, default=DEFAULT_CANONICAL)
    b.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    b.add_argument("--rule", type=Path, default=DEFAULT_RULE)
    b.add_argument("--overrides", type=Path, default=DEFAULT_OVERRIDES)
    b.add_argument("--qa-report", type=Path, default=DEFAULT_QA)
    b.add_argument("--dem-cache", type=Path, default=SHARED_CACHE,
                   help="cache holding usgs_3dep_dem_island (default: the trail dataset's data/raw)")
    b.add_argument("--allow-missing", action="append", metavar="ID[,ID]",
                   help="build without this uncached line source (dev only): retires no registry ids "
                        "and does NOT save the registry")
    b.set_defaults(func=cmd_build)

    d = sub.add_parser("dist", help="offline: canonical -> data/roads/dist + summary.md")
    d.add_argument("--canonical", type=Path, default=DEFAULT_CANONICAL)
    d.add_argument("--out-dir", type=Path, default=ROADS / "dist")
    d.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    d.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE)
    d.add_argument("--boundary", type=Path, default=None)
    d.add_argument("--rule", type=Path, default=DEFAULT_RULE)
    d.add_argument("--summary", type=Path, default=ROADS / "reports" / "summary.md")
    d.set_defaults(func=cmd_dist)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
