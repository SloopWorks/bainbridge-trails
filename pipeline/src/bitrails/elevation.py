"""Elevation profiles + gain/loss from the cached island DEM (UTM 10N GeoTIFF).

Each segment is sampled (bilinear) every SAMPLE_SPACING_M along its length in UTM, endpoints included.
Gain/loss use a zig-zag hysteresis: a climb or descent only counts once elevation has moved at least
HYSTERESIS_M from the last confirmed pivot, so lidar/sampling jitter does not inflate totals.
Per-segment gain/loss follow the stored orientation; trail totals orient segments along the trail's
walking order and run the hysteresis once over the concatenated chain (trail_chain_totals).
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import rasterio
import shapely
from affine import Affine

from .fetch import DEM_SOURCE_ID
from .geo import to_utm

SAMPLE_SPACING_M = 20.0
HYSTERESIS_M = 2.0
DEM_FILE = "latest.tiff"


class DemMissing(RuntimeError):
    pass


def require_dem(cache_dir: Path) -> Path:
    path = Path(cache_dir) / DEM_SOURCE_ID / DEM_FILE
    if not path.exists():
        raise DemMissing(f"elevation DEM not cached at {path}; run `bitrails fetch --only {DEM_SOURCE_ID}`")
    return path


@dataclass
class Dem:
    data: np.ndarray        # float32 (rows, cols), nan = nodata
    transform: Affine       # north-up, square pixels

    def sample(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        """Bilinear elevation at UTM coordinates; nan outside the raster or next to nodata."""
        t = self.transform
        rows, cols = self.data.shape
        fx = (np.asarray(xs, dtype="float64") - t.c) / t.a - 0.5
        fy = (np.asarray(ys, dtype="float64") - t.f) / t.e - 0.5
        inside = (fx >= -0.5) & (fx <= cols - 0.5) & (fy >= -0.5) & (fy <= rows - 0.5)
        fx = np.clip(fx, 0, cols - 1)
        fy = np.clip(fy, 0, rows - 1)
        x0 = np.minimum(np.floor(fx).astype(int), max(cols - 2, 0))
        y0 = np.minimum(np.floor(fy).astype(int), max(rows - 2, 0))
        x1 = np.minimum(x0 + 1, cols - 1)
        y1 = np.minimum(y0 + 1, rows - 1)
        wx, wy = fx - x0, fy - y0
        d = self.data
        top = d[y0, x0] * (1 - wx) + d[y0, x1] * wx
        bot = d[y1, x0] * (1 - wx) + d[y1, x1] * wx
        out = top * (1 - wy) + bot * wy
        out[~inside] = np.nan
        return out


def load_dem(path: Path) -> Dem:
    with rasterio.open(path) as src:
        if src.crs is None or src.crs.to_epsg() != 32610:
            raise ValueError(f"{path}: DEM must be EPSG:32610, got {src.crs}")
        arr = src.read(1, masked=True).astype("float32").filled(np.nan)
        if src.nodata is not None:
            arr[arr == np.float32(src.nodata)] = np.nan
        return Dem(arr, src.transform)


def profile_line(geom_wgs, dem: Dem, step: float = SAMPLE_SPACING_M) -> list[tuple[float, float | None]]:
    """[(dist_m, elev_m)] every `step` m along the line, plus the end point. nodata gaps are linearly
    interpolated; a line with no valid sample at all yields elev None."""
    line = to_utm(geom_wgs)
    length = float(line.length)
    dists = list(np.arange(0.0, length, step))
    if not dists or length - dists[-1] > 1e-6:
        dists.append(length)
    pts = shapely.line_interpolate_point(line, dists)
    elev = dem.sample(shapely.get_x(pts), shapely.get_y(pts))
    ok = ~np.isnan(elev)
    if ok.any() and not ok.all():
        elev = np.interp(dists, np.asarray(dists)[ok], elev[ok])
    if not ok.any():
        return [(float(d), None) for d in dists]
    return [(float(d), float(e)) for d, e in zip(dists, elev)]


def gain_loss(elevs, hysteresis: float = HYSTERESIS_M) -> tuple[float, float]:
    """Total (gain, loss) in metres, counting only moves of >= hysteresis from the last pivot."""
    elevs = list(elevs)
    if len(elevs) < 2:
        return 0.0, 0.0
    pivots = [elevs[0]]
    direction, ext = 0, elevs[0]
    for e in elevs[1:]:
        if direction == 0:
            if e - pivots[0] >= hysteresis:
                direction, ext = 1, e
            elif pivots[0] - e >= hysteresis:
                direction, ext = -1, e
        elif direction == 1:
            if e > ext:
                ext = e
            elif ext - e >= hysteresis:
                pivots.append(ext)
                direction, ext = -1, e
        else:
            if e < ext:
                ext = e
            elif e - ext >= hysteresis:
                pivots.append(ext)
                direction, ext = 1, e
    if direction != 0:
        pivots.append(ext)
    gain = sum(b - a for a, b in zip(pivots, pivots[1:]) if b > a)
    loss = sum(a - b for a, b in zip(pivots, pivots[1:]) if b < a)
    return float(gain), float(loss)


def elevation_stats(profile) -> dict:
    """gain/loss/min/max (rounded 0.1 m) for one profile; all None if the DEM has no data there."""
    elevs = [e for _, e in profile]
    if not elevs or any(e is None for e in elevs):
        return {"gain_m": None, "loss_m": None, "elev_min_m": None, "elev_max_m": None}
    g, l = gain_loss(elevs)
    return {"gain_m": round(g, 1), "loss_m": round(l, 1),
            "elev_min_m": round(min(elevs), 1), "elev_max_m": round(max(elevs), 1)}


def rounded_profile(profile) -> list[list[float | None]]:
    return [[round(d, 1), None if e is None else round(e, 1)] for d, e in profile]


CHAIN_GAP_M = 50.0


def trail_chain_totals(items, gap: float = CHAIN_GAP_M) -> dict:
    """Gain/loss for a trail from its segments in walking order.

    items = [(start_xy, end_xy, profile)] with UTM endpoints and rounded profiles [[dist, elev], ...].
    Each segment is oriented so its start is the end nearest the previous segment's exit (the first one so
    its exit is the end nearest the next segment), profiles are concatenated (junction sample dropped) and
    gain_loss runs once per chain. A gap > `gap` m or a segment without elevation starts a new chain piece;
    pieces are summed. climb_total_m = gain + loss, independent of direction."""
    def d(a, b):
        return ((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2) ** 0.5

    pieces: list[list[float]] = []
    exit_pt = None
    for k, (a, b, prof) in enumerate(items):
        elevs = None if any(e is None for _, e in prof) else [e for _, e in prof]
        if elevs is None:
            exit_pt = None
            continue
        if exit_pt is not None and min(d(exit_pt, a), d(exit_pt, b)) <= gap:
            rev = d(exit_pt, b) < d(exit_pt, a)
            pieces[-1].extend((elevs[::-1] if rev else elevs)[1:])
            exit_pt = a if rev else b
            continue
        rev = False
        if k + 1 < len(items):
            na, nb = items[k + 1][0], items[k + 1][1]
            rev = min(d(a, na), d(a, nb)) < min(d(b, na), d(b, nb))
        pieces.append(list(elevs[::-1] if rev else elevs))
        exit_pt = a if rev else b
    if not pieces:
        return {"gain_m": None, "loss_m": None, "climb_total_m": None}
    gl = [gain_loss(p) for p in pieces]
    g, l = sum(x for x, _ in gl), sum(y for _, y in gl)
    return {"gain_m": round(g, 1), "loss_m": round(l, 1), "climb_total_m": round(g + l, 1)}
