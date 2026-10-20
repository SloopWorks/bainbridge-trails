"""Split long road segments into ~TARGET_M pieces (docs/roads/decisions.md RD-2a).

The app paints progress per segment, so a 1.1 km segment walked for 300 m paints 1.1 km as "Partway". Junction
splitting leaves road segments at a 179 m mean with a 1.7 km tail; trails sit at 66 m. Every reconciled segment
longer than MAX_M is cut into n equal pieces along the line, n = round(L / TARGET_M) raised as needed so that no
piece exceeds MAX_M. Equal pieces leave no stub remainder, and the cut is direction-independent (same pieces
whichever way the source digitised the line), so ids stay stable run to run.

All segments are split, counted or not: the geometry then does not depend on the inclusion rule or on overrides,
which change without a rebuild (inclusion.roads.yaml), so flipping a category on never re-issues ids.
"""
from __future__ import annotations

import math
from dataclasses import replace

from shapely.geometry import LineString
from shapely.ops import substring

from ..geo import to_utm, to_wgs
from ..segments import Segment

TARGET_M = 120.0
MAX_M = 150.0


def piece_count(length_m: float, target_m: float = TARGET_M, max_m: float = MAX_M) -> int:
    if length_m <= max_m:
        return 1
    return max(math.floor(length_m / target_m + 0.5), math.ceil(length_m / max_m))


def split_geometry(geom: LineString) -> list[LineString]:
    """geom (WGS84) cut into piece_count equal-length pieces measured in UTM; original endpoints kept exactly."""
    utm = to_utm(geom)
    n = piece_count(utm.length)
    if n == 1:
        return [geom]
    step = utm.length / n
    out = []
    for k in range(n):
        piece = substring(utm, k * step, utm.length if k == n - 1 else (k + 1) * step)
        out.append([tuple(c) for c in to_wgs(piece).coords])
    # shared cut points come from one substring call each side; make them (and the original ends) identical
    out[0][0] = tuple(geom.coords[0])
    out[-1][-1] = tuple(geom.coords[-1])
    for a, b in zip(out, out[1:]):
        b[0] = a[-1]
    return [LineString(c) for c in out]


def split_long(segments: list[Segment]) -> list[Segment]:
    """Each segment, or its pieces (provisional ids `<id>/<k>`, k from 1, same tags and source refs)."""
    out: list[Segment] = []
    for s in segments:
        pieces = split_geometry(s.geometry)
        if len(pieces) == 1:
            out.append(s)
        else:
            out.extend(replace(s, id=f"{s.id}/{k}", geometry=g) for k, g in enumerate(pieces, start=1))
    return out
