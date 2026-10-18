import json

import pytest
import requests
import responses

from bitrails.fetch import FetchError, Source, fetch_source

OVERPASS = "https://overpass.test/api/interpreter"
ARC = "https://arc.test/FeatureServer/0/query"


def src(**kw):
    base = dict(id="s1", name="S1", kind="overpass", endpoint=OVERPASS,
                format="json", query="[out:json];rel(1);out;", redistribution="yes")
    base.update(kw)
    return Source(**base)


@responses.activate
def test_fetch_writes_raw_and_manifest(tmp_path):
    responses.post(OVERPASS, json={"elements": [{"id": 1}]})
    r = fetch_source(src(), tmp_path, offline=False)
    assert not r.from_cache and r.stale_reason is None
    assert json.loads(r.path.read_text()) == {"elements": [{"id": 1}]}
    man = json.loads((tmp_path / "s1" / "manifest.json").read_text())
    assert len(man["sha256"]) == 64 and man["fetched_at"] == r.fetched_at
    assert "bitrails" in responses.calls[0].request.headers["User-Agent"]
    assert "data=" in responses.calls[0].request.body


@responses.activate
def test_fetch_uses_cache_when_endpoint_fails(tmp_path):
    responses.post(OVERPASS, json={"elements": [1]})
    first = fetch_source(src(), tmp_path, offline=False)
    responses.replace(responses.POST, OVERPASS, status=503)
    r = fetch_source(src(), tmp_path, offline=False, retries=1)
    assert r.from_cache and r.path == first.path
    assert r.fetched_at == first.fetched_at


@responses.activate
def test_fetch_fails_loudly_without_cache(tmp_path):
    responses.post(OVERPASS, status=503)
    with pytest.raises(FetchError, match="s1"):
        fetch_source(src(), tmp_path, offline=False, retries=1)


def test_offline_uses_cache_without_network(tmp_path):
    d = tmp_path / "s1"
    d.mkdir()
    (d / "latest.json").write_text("{}")
    (d / "manifest.json").write_text(json.dumps({"fetched_at": "t", "sha256": "x", "file": "latest.json"}))
    r = fetch_source(src(), tmp_path, offline=True)
    assert r.from_cache and r.fetched_at == "t"


def test_offline_without_cache_raises(tmp_path):
    with pytest.raises(FetchError, match="s1"):
        fetch_source(src(), tmp_path, offline=True)


@responses.activate
def test_arcgis_pages_merge(tmp_path):
    def page(ids, more):
        return {"type": "FeatureCollection", "exceededTransferLimit": more,
                "features": [{"type": "Feature", "properties": {"i": i}, "geometry": None} for i in ids]}
    responses.get(ARC, json=page([1, 2], True),
                  match=[responses.matchers.query_param_matcher({"where": "1=1", "outFields": "*", "f": "geojson", "resultOffset": "0", "resultRecordCount": "1000"}, strict_match=False)])
    responses.get(ARC, json=page([3], False),
                  match=[responses.matchers.query_param_matcher({"resultOffset": "2"}, strict_match=False)])
    s = src(id="a", kind="arcgis_rest", endpoint=ARC, format="geojson",
            query="where=1%3D1&outFields=%2A&f=geojson")
    r = fetch_source(s, tmp_path, offline=False)
    fc = json.loads(r.path.read_text())
    assert [f["properties"]["i"] for f in fc["features"]] == [1, 2, 3]
    assert "exceededTransferLimit" not in fc


@responses.activate
def test_shapefile_zip_downloaded_raw(tmp_path):
    responses.get("https://z.test/a.zip", body=b"PK\x03\x04zip")
    s = src(id="z", kind="geojson", endpoint="https://z.test/a.zip", format="shapefile_zip", query=None)
    r = fetch_source(s, tmp_path, offline=False)
    assert r.path.suffix == ".zip" and r.path.read_bytes() == b"PK\x03\x04zip"


def _seed(tmp_path, s):
    responses.post(OVERPASS, json={"elements": [{"id": 1}]})
    return fetch_source(s, tmp_path, offline=False)


@responses.activate
@pytest.mark.parametrize("bad", [
    {"json": {"elements": [], "remark": "runtime error: Query timed out in \"query\""}},
    {"json": {"elements": []}},
    {"body": "<html>busy</html>"},
])
def test_invalid_overpass_body_keeps_cache(tmp_path, bad):
    first = _seed(tmp_path, src())
    responses.replace(responses.POST, OVERPASS, **bad)
    r = fetch_source(src(), tmp_path, offline=False, retries=1)
    assert r.from_cache and r.stale_reason
    assert json.loads(r.path.read_text()) == {"elements": [{"id": 1}]}
    assert r.fetched_at == first.fetched_at


@responses.activate
def test_arcgis_empty_keeps_cache(tmp_path):
    s = src(id="a", kind="arcgis_rest", endpoint=ARC, format="geojson", query="where=1%3D1&f=geojson")
    responses.get(ARC, json={"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {}, "geometry": None}]})
    fetch_source(s, tmp_path, offline=False)
    responses.replace(responses.GET, ARC, json={"type": "FeatureCollection", "features": []})
    r = fetch_source(s, tmp_path, offline=False, retries=1)
    assert r.from_cache and r.stale_reason
    assert len(json.loads(r.path.read_text())["features"]) == 1


@responses.activate
def test_arcgis_empty_without_cache_raises(tmp_path):
    s = src(id="a", kind="arcgis_rest", endpoint=ARC, format="geojson", query="f=geojson")
    responses.get(ARC, json={"type": "FeatureCollection", "features": []})
    with pytest.raises(FetchError, match="a"):
        fetch_source(s, tmp_path, offline=False, retries=1)


@responses.activate
def test_bad_zip_rejected(tmp_path):
    responses.get("https://z.test/a.zip", body=b"<html>")
    s = src(id="z", kind="geojson", endpoint="https://z.test/a.zip", format="shapefile_zip", query=None)
    with pytest.raises(FetchError, match="zip"):
        fetch_source(s, tmp_path, offline=False, retries=1)


@responses.activate
def test_arcgis_pages_without_exceeded_flag(tmp_path):
    feats = lambda ids: [{"type": "Feature", "properties": {"i": i}, "geometry": None} for i in ids]
    responses.get("https://arc.test/FeatureServer/0", json={"maxRecordCount": 2})
    for off, ids in ((0, [1, 2]), (2, [3, 4]), (4, [5])):
        responses.get(ARC, json={"type": "FeatureCollection", "features": feats(ids)},
                      match=[responses.matchers.query_param_matcher(
                          {"resultOffset": str(off), "resultRecordCount": "2"}, strict_match=False)])
    s = src(id="a", kind="arcgis_rest", endpoint=ARC, format="geojson", query="f=geojson")
    r = fetch_source(s, tmp_path, offline=False)
    assert [f["properties"]["i"] for f in json.loads(r.path.read_text())["features"]] == [1, 2, 3, 4, 5]


@responses.activate
def test_non_retryable_4xx_not_retried(tmp_path):
    responses.post(OVERPASS, status=404)
    with pytest.raises(FetchError):
        fetch_source(src(), tmp_path, offline=False, retries=3)
    assert len(responses.calls) == 1


@responses.activate
def test_stale_fallback_reports_reason_and_cli_warns(tmp_path, capsys, monkeypatch):
    from bitrails import cli
    _seed(tmp_path, src())
    responses.replace(responses.POST, OVERPASS, status=503)
    monkeypatch.setattr("bitrails.fetch.time.sleep", lambda s: None)
    sf = tmp_path / "sources.yaml"
    sf.write_text(f"sources:\n- id: s1\n  name: S1\n  kind: overpass\n  endpoint: {OVERPASS}\n"
                  "  format: json\n  redistribution: 'yes'\n  query: 'x'\n")
    rc = cli.main(["fetch", "--sources", str(sf), "--cache-dir", str(tmp_path)])
    err = capsys.readouterr().err
    assert rc == 0
    assert "WARN  s1: endpoint failed" in err and "1 stale" in err


def test_cli_survives_corrupt_manifest(tmp_path, capsys):
    from bitrails import cli
    (tmp_path / "s1").mkdir()
    (tmp_path / "s1" / "manifest.json").write_text("{not json")
    sf = tmp_path / "sources.yaml"
    sf.write_text(f"sources:\n- id: s1\n  name: S1\n  kind: overpass\n  endpoint: {OVERPASS}\n  query: x\n")
    assert cli.main(["fetch", "--offline", "--sources", str(sf), "--cache-dir", str(tmp_path)]) == 1
    assert "FAIL   s1" in capsys.readouterr().err


def test_cli_fetch_only_limits_to_named_sources(tmp_path, capsys):
    from bitrails import cli
    sf = tmp_path / "sources.yaml"
    sf.write_text("sources:\n"
                  f"- id: s1\n  name: S1\n  kind: overpass\n  endpoint: {OVERPASS}\n  query: x\n"
                  f"- id: s2\n  name: S2\n  kind: overpass\n  endpoint: {OVERPASS}\n  query: x\n")
    rc = cli.main(["fetch", "--offline", "--only", "s2", "--sources", str(sf), "--cache-dir", str(tmp_path)])
    err = capsys.readouterr().err
    assert rc == 1 and "FAIL   s2" in err and "s1" not in err  # s2 has no cache; s1 never touched
    assert cli.main(["fetch", "--only", "nope", "--sources", str(sf), "--cache-dir", str(tmp_path)]) == 2


@responses.activate
def test_fetch_dem_tiles_mosaic(tmp_path, monkeypatch):
    """Tiled exportImage requests are mosaicked into one UTM GeoTIFF; value = easting-derived so seams show."""
    import re
    from urllib.parse import parse_qs, urlparse

    import numpy as np
    import rasterio
    from rasterio.io import MemoryFile
    from rasterio.transform import from_origin

    from bitrails import fetch as F
    monkeypatch.setattr(F, "DEM_TILE_PX", 4)
    ep = "https://dem.test/ImageServer/exportImage"

    def cb(request):
        q = parse_qs(urlparse(request.url).query)
        minx, miny, maxx, maxy = (float(v) for v in q["bbox"][0].split(","))
        w, h = (int(v) for v in q["size"][0].split(","))
        xs = minx + (np.arange(w) + 0.5) * (maxx - minx) / w
        arr = np.tile(xs, (h, 1)).astype("float32")
        with MemoryFile() as mf:
            with mf.open(driver="GTiff", height=h, width=w, count=1, dtype="float32", crs="EPSG:32610",
                         transform=from_origin(minx, maxy, (maxx - minx) / w, (maxy - miny) / h)) as d:
                d.write(arr, 1)
            return 200, {}, mf.read()

    responses.add_callback(responses.GET, re.compile(re.escape(ep)), callback=cb)
    s = Source(id=F.DEM_SOURCE_ID, name="dem", kind="elevation", endpoint=ep, format="tiff",
               query="bbox=1000,2000,1010,2007&res=1", redistribution="yes")
    r = fetch_source(s, tmp_path)
    assert len(responses.calls) == 3 * 2  # 10x7 px in 4 px tiles
    with rasterio.open(r.path) as ds:
        assert ds.crs.to_epsg() == 32610 and ds.shape == (7, 10)
        a = ds.read(1)
    assert np.allclose(a, np.tile(1000.5 + np.arange(10), (7, 1)))
