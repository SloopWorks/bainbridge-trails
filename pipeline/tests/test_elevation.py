from __future__ import annotations

import json

import numpy as np
import pytest
import rasterio
from rasterio.transform import from_origin
from shapely.geometry import LineString

from bitrails.elevation import (HYSTERESIS_M, SAMPLE_SPACING_M, Dem, DemMissing, elevation_stats, gain_loss,
                                load_dem, profile_line, require_dem)
from bitrails.geo import to_wgs

X0, Y0 = 500000.0, 5270000.0  # UTM 10N origin (NW corner), 1 m pixels


def make_dem(path, fn, size=400, nodata=-9999.0, res=1.0):
    cols = np.arange(size)
    rows = np.arange(size)
    xs = X0 + (cols + 0.5) * res
    ys = Y0 - (rows + 0.5) * res
    xx, yy = np.meshgrid(xs, ys)
    arr = fn(xx, yy).astype("float32")
    with rasterio.open(path, "w", driver="GTiff", height=size, width=size, count=1, dtype="float32",
                       crs="EPSG:32610", transform=from_origin(X0, Y0, res, res), nodata=nodata) as dst:
        dst.write(arr, 1)
    return path


def utm_line(x1, y1, x2, y2):
    """WGS84 LineString from UTM endpoints (offsets from the DEM origin)."""
    return to_wgs(LineString([(X0 + x1, Y0 - y1), (X0 + x2, Y0 - y2)]))


@pytest.fixture
def ramp(tmp_path):
    """Elevation rises 0.3 m per metre eastward (x)."""
    return load_dem(make_dem(tmp_path / "ramp.tif", lambda x, y: (x - X0) * 0.3))


def test_profile_sample_spacing(ramp):
    prof = profile_line(utm_line(10, 50, 105, 50), ramp)  # 95 m long
    dists = [d for d, _ in prof]
    assert SAMPLE_SPACING_M == 20.0
    assert dists[:5] == pytest.approx([0, 20, 40, 60, 80], abs=0.01)
    assert dists[-1] == pytest.approx(95, abs=0.01)  # both endpoints included
    assert len(prof) == 6
    # bilinear on a planar ramp is exact: elev = 0.3 * x
    assert prof[0][1] == pytest.approx(0.3 * 10, abs=0.01)
    assert prof[-1][1] == pytest.approx(0.3 * 105, abs=0.01)


def test_profile_exact_multiple_has_no_duplicate_endpoint(ramp):
    dists = [d for d, _ in profile_line(utm_line(10, 50, 90, 50), ramp)]
    assert dists == pytest.approx([0, 20, 40, 60, 80], abs=0.01)


def test_gain_loss_from_synthetic_dem(ramp):
    up = elevation_stats(profile_line(utm_line(10, 50, 110, 50), ramp))  # 100 m * 0.3 = 30 m
    assert up["gain_m"] == pytest.approx(30, abs=0.1) and up["loss_m"] == 0
    down = elevation_stats(profile_line(utm_line(110, 50, 10, 50), ramp))
    assert down["loss_m"] == pytest.approx(30, abs=0.1) and down["gain_m"] == 0
    assert up["elev_min_m"] == pytest.approx(3, abs=0.1) and up["elev_max_m"] == pytest.approx(33, abs=0.1)


def test_elevation_noise_threshold():
    rng = np.random.default_rng(1)
    noisy = list(100 + rng.choice([-0.5, 0.5], size=200))
    assert HYSTERESIS_M == 2.0
    assert gain_loss(noisy) == (0.0, 0.0)
    # real climb with jitter on top still counted once
    climb = [i * 0.5 + (0.4 if i % 2 else -0.4) for i in range(61)]  # 0 -> 30
    g, l = gain_loss(climb)
    assert g == pytest.approx(30, abs=1.0) and l == 0


def test_gain_loss_zigzag_counts_both_ways():
    assert gain_loss([0, 5, 1, 6]) == (5 + 5, 4)
    assert gain_loss([0, 1.9, 0]) == (0, 0)  # below hysteresis


def test_nodata_is_interpolated_or_none(tmp_path):
    # nodata hole in the middle of the ramp -> profile still gets values from neighbours
    def fn(x, y):
        z = (x - X0) * 0.3
        return np.where((x - X0 > 48) & (x - X0 < 52), -9999.0, z)
    dem = load_dem(make_dem(tmp_path / "hole.tif", fn))
    prof = profile_line(utm_line(10, 50, 110, 50), dem)
    assert all(e is not None for _, e in prof)
    assert elevation_stats(prof)["gain_m"] == pytest.approx(30, abs=0.5)
    outside = profile_line(utm_line(1000, 50, 1100, 50), dem)  # off the raster
    stats = elevation_stats(outside)
    assert stats["elev_min_m"] is None and stats["gain_m"] is None


def test_require_dem_missing_message(tmp_path):
    with pytest.raises(DemMissing, match="bitrails fetch --only usgs_3dep_dem_island"):
        require_dem(tmp_path)


def test_end_of_profile_hysteresis():
    assert gain_loss([0, 5, 3.5]) == (5, 0)  # trailing 1.5 m dip not confirmed
    assert gain_loss([0, 5, 2.9, 1]) == (5, 4)  # 5 -> 1 confirmed once past 2 m


def _seg(x1, x2, e1, e2, reverse=False):
    """Straight 100 m segment (UTM x1->x2 at y=0), 6 profile samples ramping e1->e2, optionally stored reversed."""
    prof = [[i * 20.0, e1 + (e2 - e1) * i / 5] for i in range(6)]
    a, b = (x1, 0.0), (x2, 0.0)
    if reverse:
        a, b = b, a
        prof = [[d, e] for (d, _), (_, e) in zip(prof, prof[::-1])]
    return (a, b, prof)


def test_trail_chain_orientation_independent():
    from bitrails.elevation import trail_chain_totals
    # walk east: 0->10, 10->4, 4->20 (up 10, down 6, up 16 = gain 26, loss 6)
    fwd = [_seg(0, 100, 0, 10), _seg(100, 200, 10, 4), _seg(200, 300, 4, 20)]
    mid_rev = [fwd[0], _seg(100, 200, 10, 4, reverse=True), fwd[2]]
    a, b = trail_chain_totals(fwd), trail_chain_totals(mid_rev)
    assert a == b
    assert a["gain_m"] == 26 and a["loss_m"] == 6 and a["climb_total_m"] == 32
    # first segment stored reversed too
    assert trail_chain_totals([_seg(0, 100, 0, 10, reverse=True), *fwd[1:]]) == a


def test_trail_chain_small_steps_accumulate_and_gap_breaks():
    from bitrails.elevation import trail_chain_totals
    # five 1.5 m rises per segment: per-segment hysteresis would give 0, chain gives 7.5 total
    steps = [_seg(i * 100, i * 100 + 100, i * 1.5, (i + 1) * 1.5) for i in range(5)]
    assert trail_chain_totals(steps)["gain_m"] == 7.5
    # gap of 500 m between pieces: summed independently, no bridging
    far = [steps[0], _seg(600, 700, 50, 60)]
    assert trail_chain_totals(far)["gain_m"] == 10  # the 1.5 m piece is below hysteresis
    assert trail_chain_totals([(( 0, 0), (1, 0), [[0, None], [1, None]])])["gain_m"] is None


def test_segment_elevation_props_and_profile_file(ramp, tmp_path):
    from bitrails.build import segment_elevation, segment_features, write_profiles
    from bitrails.classify import ClassifiedSegment, InclusionRule

    def cs(sid, geom, shippable):
        return ClassifiedSegment(sid, geom, None, "agency_trail", None, None, [], "high", "public", [],
                                 shippable, 100.0)
    segs = [cs("b", utm_line(10, 50, 110, 50), True), cs("a", utm_line(110, 60, 10, 60), False)]
    stats, profiles = segment_elevation(segs, ramp)
    assert list(stats) == ["a", "b"]  # sorted
    rule = InclusionRule(frozenset({"agency_trail"}), True, "x")
    public, private = segment_features(segs, rule, stats)
    assert public[0]["properties"]["gain_m"] == pytest.approx(30, abs=0.1)
    assert private[0]["properties"]["loss_m"] == pytest.approx(30, abs=0.1)
    out = write_profiles({"b": profiles["b"], "a": profiles["a"]}, tmp_path / "e.json")
    data = json.loads(out.read_text())
    assert list(data) == ["a", "b"] and data["b"][0] == [0.0, 3.0] and data["b"][-1][0] == 100.0
