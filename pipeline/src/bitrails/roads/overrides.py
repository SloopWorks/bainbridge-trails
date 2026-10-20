"""Manual corrections for the road dataset (data/roads/roads_overrides.yaml).

The agency sources disagree with OSM about some names, and the geometric match occasionally lends a
centerline the wrong street's attributes (see data/roads/reports/qa.md). Rather than hand-edit
generated data, record the verdict here: the build applies it after classification and before
grouping, and every applied override is counted in roads.json's `qa`.

Keys are either a stable segment id (`seg-000123`) or a street, matched on the direction- and
abbreviation-insensitive name key, so "NE Day Rd W" and "Northeast Day Road West" are one entry.
Each entry may set `ownership`, `category`, `name` or `drop: true` (drop = leave the segment in the
dataset but never count it and never group it into a road).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml

from .classify import CATEGORIES, OWNERSHIPS, RoadSegment
from .names import base_key

FIELDS = ("ownership", "category", "name")


@dataclass(frozen=True)
class Override:
    key: str                      # segment id or street name as written in the file
    fields: dict = field(default_factory=dict)
    drop: bool = False
    note: str | None = None


@dataclass(frozen=True)
class Overrides:
    by_segment: dict[str, Override] = field(default_factory=dict)
    by_street: dict[str, Override] = field(default_factory=dict)

    def __len__(self) -> int:
        return len(self.by_segment) + len(self.by_street)


def _entry(key: str, raw: dict | None) -> Override:
    raw = dict(raw or {})
    drop = bool(raw.pop("drop", False))
    note = raw.pop("note", None)
    bad = set(raw) - set(FIELDS)
    if bad:
        raise ValueError(f"road override '{key}': unknown field(s) {sorted(bad)}")
    if (own := raw.get("ownership")) and own not in OWNERSHIPS:
        raise ValueError(f"road override '{key}': unknown ownership {own!r}")
    if (cat := raw.get("category")) and cat not in CATEGORIES:
        raise ValueError(f"road override '{key}': unknown category {cat!r}")
    return Override(key, raw, drop, note)


def load_overrides(path: Path) -> Overrides:
    if not Path(path).exists():
        return Overrides()
    doc = yaml.safe_load(Path(path).read_text()) or {}
    by_seg = {k: _entry(k, v) for k, v in (doc.get("segments") or {}).items()}
    by_street = {}
    for k, v in (doc.get("streets") or {}).items():
        key = base_key(k)
        if not key:
            raise ValueError(f"road override '{k}': not a usable street name")
        by_street[key] = _entry(k, v)
    return Overrides(by_seg, by_street)


def apply_overrides(segments: list[RoadSegment], ov: Overrides) -> dict[str, int]:
    """Apply overrides in place. Segment-id entries win over street entries. Returns counts."""
    stats = {"segments": 0, "streets": 0, "dropped": 0}
    for s in segments:
        entry = ov.by_segment.get(s.id)
        which = "segments"
        if entry is None and s.name:
            entry = ov.by_street.get(base_key(s.name))
            which = "streets"
        if entry is None:
            continue
        for f, v in entry.fields.items():
            setattr(s, f, v)
        if entry.drop:
            s.dropped = True
            stats["dropped"] += 1
        s.ownership_source = f"override:{entry.key}" if "ownership" in entry.fields else s.ownership_source
        stats[which] += 1
    return stats
