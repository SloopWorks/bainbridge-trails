"""Manual trail fixes from data/trails_overrides.yaml (see that file for the schema).

Applied after automatic grouping and id assignment, in order: ``merge`` (fold trails
into a target, then rebuild it from the union of segments), then ``trails`` (per-trail
edits / drop). Unknown or chained ids raise, so stale fixes fail the build loudly.
"""
from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING, Callable

import yaml

if TYPE_CHECKING:
    from .trails import Trail

EDITABLE = ("name", "park", "manager", "official_length_mi", "official_map_url")


def load_overrides(path: Path) -> dict:
    p = Path(path)
    return (yaml.safe_load(p.read_text()) or {}) if p.exists() else {}


def merge(trails: list["Trail"], merges: dict, rebuild: Callable[["Trail", list[str]], "Trail"]) -> list["Trail"]:
    """{target id: [ids folded into it]}. rebuild(target, segment_ids) recomputes order,
    length, park, manager, trailheads and map URL; the target keeps its id and name and
    falls back to a merged trail's map URL if its own name/park finds none."""
    by = {t.id: t for t in trails}
    sources = [o for v in merges.values() for o in v]
    unknown = sorted((set(merges) | set(sources)) - set(by))
    if unknown:
        raise ValueError(f"trails_overrides merge: unknown trail ids {unknown}")
    chained = sorted((set(merges) & set(sources)) | {o for o in sources if sources.count(o) > 1}
                     | {k for k, v in merges.items() if k in v})
    if chained:
        raise ValueError(f"trails_overrides merge: chained/duplicate merge ids {chained}; "
                         "list every trail directly under its final target")
    out = {t.id: t for t in trails if t.id not in set(sources)}
    for target in sorted(merges):
        parts = [by[target], *(by[o] for o in merges[target])]
        ids = sorted({i for p in parts for i in p.segment_ids})
        t = rebuild(by[target], ids)
        t.official_map_url = t.official_map_url or next((p.official_map_url for p in parts if p.official_map_url), None)
        out[target] = t
    return [out[t.id] for t in trails if t.id in out]


def apply_overrides(trails: list["Trail"], overrides: dict,
                    rebuild: Callable[["Trail", list[str]], "Trail"]) -> list["Trail"]:
    trails = merge(trails, overrides.get("merge") or {}, rebuild)
    edits = overrides.get("trails") or {}
    unknown = sorted(set(edits) - {t.id for t in trails})
    if unknown:
        raise ValueError(f"trails_overrides: unknown trail ids {unknown}")
    out = []
    for t in trails:
        e = edits.get(t.id) or {}
        if e.get("drop"):
            continue
        for k in EDITABLE:
            if k in e:
                setattr(t, k, e[k])
        out.append(t)
    return out
