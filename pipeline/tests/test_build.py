"""End-to-end run_build on a tiny offline fixture: missing-source refusal + transactional writes + determinism."""
import json
import shutil
from pathlib import Path

import numpy as np
import pytest
import rasterio
import shapely
from rasterio.transform import from_origin

from bitrails.boundary import island_polygon
from bitrails.build import MissingSources, REQUIRED_LINE_SOURCES, run_build
from bitrails.cli import main
from bitrails.geo import to_utm, to_wgs

FIX = Path(__file__).parent / "fixtures" / "osm_island_boundary.json"
INCLUSION = Path(__file__).resolve().parents[2] / "data" / "inclusion.yaml"
OSM, AG = "osm_overpass_trails", "bimprd_trails_agol_2022"

SOURCES = """sources:
- {id: osm_overpass_trails, name: osm, url: u, kind: overpass, endpoint: 'http://x', redistribution: 'yes', geometry_rank: 1}
- {id: bimprd_trails_agol_2022, name: ag, url: u, kind: arcgis_rest, endpoint: 'http://y', redistribution: unknown, geometry_rank: 3}
- {id: cobi_sto_alignments, name: sto, url: u, kind: arcgis_rest, endpoint: 'http://z', redistribution: unknown, geometry_rank: 6}
- {id: bimprd_trails_2015_legacy, name: lg, url: u, kind: arcgis_rest, endpoint: 'http://w', redistribution: unknown, geometry_rank: 5}
"""


def _cache(cache, sid, name, body):
    d = cache / sid
    d.mkdir(parents=True)
    (d / name).write_text(body)
    (d / "manifest.json").write_text(json.dumps({"id": sid, "file": name, "fetched_at": "2026-01-01T00:00:00Z"}))


def _setup(tmp_path):
    cache = tmp_path / "raw"
    cx, cy = to_utm(island_polygon(FIX).representative_point()).coords[0]
    wgs = lambda x, y: list(to_wgs(shapely.Point(cx + x, cy + y)).coords[0])  # noqa: E731
    ways = [{"type": "way", "id": 100 + n, "tags": {"highway": "path", "name": f"Trail {n}"},
             "geometry": [{"lon": wgs(0, n * 40)[0], "lat": wgs(0, n * 40)[1]},
                          {"lon": wgs(150, n * 40)[0], "lat": wgs(150, n * 40)[1]}]} for n in range(3)]
    _cache(cache, OSM, "latest.json", json.dumps({"elements": ways}))
    line = {"type": "Feature", "properties": {"OBJECTID": 1, "TRAILNAME": "Agency"},
            "geometry": {"type": "LineString", "coordinates": [wgs(0, 300), wgs(150, 300)]}}
    _cache(cache, AG, "latest.geojson", json.dumps({"type": "FeatureCollection", "features": [line]}))
    for sid in ("cobi_sto_alignments", "bimprd_trails_2015_legacy"):
        _cache(cache, sid, "latest.geojson", json.dumps({"type": "FeatureCollection", "features": []}))
    dem = cache / "usgs_3dep_dem_island"
    dem.mkdir()
    with rasterio.open(dem / "latest.tiff", "w", driver="GTiff", height=1000, width=1000, count=1,
                       dtype="float32", crs="EPSG:32610", transform=from_origin(cx - 500, cy + 500, 1.0, 1.0)) as d:
        d.write(np.full((1000, 1000), 10.0, dtype="float32"), 1)
    sources = tmp_path / "sources.yaml"
    sources.write_text(SOURCES)
    return dict(sources_path=sources, cache_dir=cache, boundary=FIX, registry_path=tmp_path / "reg" / "registry.json",
                out_dir=tmp_path / "out", report=tmp_path / "rep" / "conflicts.md", rule_path=INCLUSION,
                overrides_path=tmp_path / "none.yaml", log=lambda *_: None)


def _snapshot(tmp_path):
    return {str(p.relative_to(tmp_path)): p.read_bytes() for p in sorted(tmp_path.rglob("*"))
            if p.is_file() and "raw" not in p.parts}


def test_required_sources_cover_line_sources():
    assert {OSM, AG, "cobi_sto_alignments", "bimprd_trails_2015_legacy"} == set(REQUIRED_LINE_SOURCES)


def test_run_build_is_deterministic(tmp_path):
    kw = _setup(tmp_path)
    r = run_build(**kw)
    assert len(r.segments) == 4
    first = _snapshot(tmp_path)
    assert "out/segments.geojson" in first and "reg/registry.json" in first and "rep/conflicts.md" in first
    run_build(**kw)
    assert _snapshot(tmp_path) == first


def test_missing_required_source_refuses_and_leaves_everything_unchanged(tmp_path):
    kw = _setup(tmp_path)
    run_build(**kw)
    before = _snapshot(tmp_path)
    shutil.rmtree(kw["cache_dir"] / AG)
    with pytest.raises(MissingSources) as e:
        run_build(**kw)
    assert AG in str(e.value) and f"bitrails fetch --only {AG}" in str(e.value)
    assert _snapshot(tmp_path) == before
    reg = json.loads(kw["registry_path"].read_text())
    assert not any(s["retired"] for s in reg["segments"])


def test_missing_source_first_run_writes_nothing(tmp_path):
    kw = _setup(tmp_path)
    shutil.rmtree(kw["cache_dir"] / OSM)
    with pytest.raises(MissingSources):
        run_build(**kw)
    assert not kw["registry_path"].exists() and not kw["out_dir"].exists() and not kw["report"].exists()


def test_allow_missing_does_not_retire_or_persist_ids(tmp_path, capsys):
    kw = _setup(tmp_path)
    run_build(**kw)
    reg_before = kw["registry_path"].read_bytes()
    shutil.rmtree(kw["cache_dir"] / AG)
    r = run_build(**kw, allow_missing=[AG])
    assert len(r.segments) == 3  # degraded dataset still builds
    assert kw["registry_path"].read_bytes() == reg_before  # agency ID not retired, nothing saved
    assert "WARN" in capsys.readouterr().err


def test_allow_missing_unknown_id_does_not_excuse_other_missing(tmp_path):
    kw = _setup(tmp_path)
    shutil.rmtree(kw["cache_dir"] / AG)
    with pytest.raises(MissingSources):
        run_build(**kw, allow_missing=[OSM])


def test_failure_after_ids_assigned_leaves_registry_and_outputs_untouched(tmp_path, monkeypatch):
    kw = _setup(tmp_path)
    run_build(**kw)
    before = _snapshot(tmp_path)
    (kw["cache_dir"] / OSM / "latest.json").write_text(json.dumps({"elements": []}))  # would retire OSM ids
    kw["overrides_path"].write_text("custom:\n- {id: trail-x, name: X, segment_ids: [seg-999999]}\n")
    with pytest.raises(ValueError):
        run_build(**kw)  # crash in trails step, after ids were assigned
    kw["overrides_path"].unlink()
    assert _snapshot(tmp_path) == {**before}


def test_cli_build_nonzero_naming_source(tmp_path, capsys):
    kw = _setup(tmp_path)
    shutil.rmtree(kw["cache_dir"] / AG)
    argv = ["build", "--sources", str(kw["sources_path"]), "--cache-dir", str(kw["cache_dir"]),
            "--boundary", str(FIX), "--registry", str(kw["registry_path"]), "--out-dir", str(kw["out_dir"]),
            "--report", str(kw["report"]), "--rule", str(INCLUSION), "--overrides", str(kw["overrides_path"])]
    assert main(argv) == 1
    err = capsys.readouterr().err
    assert AG in err and f"bitrails fetch --only {AG}" in err
    assert main(argv + ["--allow-missing", AG]) == 0
