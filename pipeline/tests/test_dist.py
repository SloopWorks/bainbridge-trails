import json

import pytest

from bitrails.dist import (TILE_PROPS, attribution_md, coverage_segments_doc, dataset_version, dist_trails_doc, run_dist,
                           segment_trail_map, shippable_info, used_source_ids, write_tile_input)

PUB = {"seg-000001", "seg-000002"}
PRIV = "seg-000099"


def feat(i, **props):
    base = {"id": i, "category": "agency_trail", "subtype": None, "manager": "BIMPRD", "sources": ["osm_overpass_trails"],
            "confidence": "high", "public_access": "yes", "allowed_uses": ["foot"], "included": True,
            "geometry_redistributable": True, "name": "Loop", "trail_ids": ["trail-a", "trail-b"], "park": "P",
            "length_m": 100.0, "gain_m": 1.0, "loss_m": 1.0, "elev_min_m": 1.0, "elev_max_m": 2.0}
    base.update(props)
    return {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[0, 0], [1, 1]]},
            "properties": base}


def canon(tmp_path, segs=None, trails=None):
    d = tmp_path / "canonical"
    (d / "private").mkdir(parents=True)
    segs = segs or [feat("seg-000001"), feat("seg-000002", name=None, trail_ids=[], sources=["bimprd_trails_agol_2022"])]
    (d / "segments.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": segs}))
    (d / "private" / "agency_only.geojson").write_text(json.dumps(
        {"type": "FeatureCollection", "features": [feat(PRIV, geometry_redistributable=False)]}))
    doc = {"island_total_m": 100.0, "island_total_shippable_m": 60.0, "rule": {}, "dataset": {},
           "trails": trails or [
               {"id": "trail-a", "name": "A", "manager": "BIMPRD", "park": "P", "segment_ids": ["seg-000001", PRIV],
                "official_length_mi": None, "computed_length_mi": 0.1, "gain_m": 1.0, "loss_m": 2.0,
                "climb_total_m": 3.0, "trailheads": [], "official_map_url": None},
               {"id": "trail-priv", "name": "Only private", "manager": None, "park": None, "segment_ids": [PRIV],
                "official_length_mi": None, "computed_length_mi": 0.1, "gain_m": 0, "loss_m": 0,
                "climb_total_m": 0, "trailheads": [], "official_map_url": None}]}
    (d / "trails.json").write_text(json.dumps(doc))
    return d


def test_tile_input_whitelists_props(tmp_path):
    d = canon(tmp_path)
    out = write_tile_input(d / "segments.geojson", tmp_path / "t.geojson", {"seg-000001": ["trail-a", "trail-b"]})
    feats = json.loads(out.read_text())["features"]
    assert len(feats) == 2
    for f in feats:
        assert set(f["properties"]) <= set(TILE_PROPS)
    p = feats[0]["properties"]
    assert p["trail_ids"] == "trail-a,trail-b" and p["included"] is True
    assert "name" not in feats[1]["properties"] and feats[1]["properties"]["trail_ids"] == ""


def test_tile_input_refuses_non_redistributable(tmp_path):
    d = canon(tmp_path, segs=[feat("seg-000001", geometry_redistributable=False)])
    with pytest.raises(ValueError, match="redistributable"):
        write_tile_input(d / "segments.geojson", tmp_path / "t.geojson", {})


def test_dist_trails_shape_and_no_private_ids(tmp_path):
    d = canon(tmp_path)
    info = shippable_info(d / "segments.geojson", None)
    doc = dist_trails_doc(json.loads((d / "trails.json").read_text()), info, "2026-10-18+abcd1234")
    assert doc["version"] == "2026-10-18+abcd1234"
    assert doc["island_total_m"] == 100.0 and doc["island_total_shippable_m"] == 60.0
    assert [t["id"] for t in doc["trails"]] == ["trail-a"]  # all-private trail dropped
    assert set(doc["trails"][0]) == {"id", "name", "manager", "park", "segment_ids", "computed_length_mi", "gain_m",
                                     "loss_m", "climb_total_m", "trailheads", "official_map_url",
                                     "agency_only_length_mi", "official_length_mi", "official_length_source"}
    assert doc["trails"][0]["segment_ids"] == ["seg-000001"]
    assert PRIV not in json.dumps(doc)


def test_trail_stats_from_shippable_segments_only(tmp_path):
    d = canon(tmp_path)  # trail-a: shippable seg-000001 (10 m) + private seg-000099; canonical says 0.1 mi
    (d / "elevation.json").write_text(json.dumps({"seg-000001": [[0, 10.0], [10, 15.0]]}))
    info = shippable_info(d / "segments.geojson", d / "elevation.json")
    (t,) = dist_trails_doc(json.loads((d / "trails.json").read_text()), info, "v")["trails"]
    assert t["segment_ids"] == ["seg-000001"]
    assert t["computed_length_mi"] == round(100 / 1609.344, 3)
    assert t["agency_only_length_mi"] == round(0.1 - 100 / 1609.344, 3)
    assert t["gain_m"] == 5.0 and t["loss_m"] == 0.0 and t["climb_total_m"] == 5.0  # canonical said 1/2/3


def test_trailhead_kept_only_near_shippable(tmp_path):
    d = canon(tmp_path)
    doc = json.loads((d / "trails.json").read_text())
    near = {"type": "Point", "coordinates": [0, 0]}
    far = {"type": "Point", "coordinates": [0.5, 0.2]}
    doc["trails"][0]["trailheads"] = [near, far]
    info = shippable_info(d / "segments.geojson", None)
    assert dist_trails_doc(doc, info, "v")["trails"][0]["trailheads"] == [near]


def test_version_is_date_plus_hash(tmp_path):
    f = tmp_path / "s.geojson"
    f.write_text("x")
    v = dataset_version(f, "2026-10-18T12:00:00-07:00")
    assert v.startswith("2026-10-18+") and len(v.split("+")[1]) == 8
    assert dataset_version(f, "2026-10-18T12:00:00-07:00") == v


def test_attribution_covers_every_used_source(tmp_path):
    d = canon(tmp_path)
    used = used_source_ids(d / "segments.geojson")
    assert {"osm_overpass_trails", "bimprd_trails_agol_2022", "usgs_3dep_dem_island", "protomaps_basemap"} <= used
    md = attribution_md(used, [
        {"id": "osm_overpass_trails", "attribution": "(c) OpenStreetMap contributors, ODbL 1.0", "redistribution": "yes"},
        {"id": "bimprd_trails_agol_2022", "attribution": "BIMPRD", "redistribution": "unknown"},
        {"id": "usgs_3dep_dem_island", "attribution": "USGS NGP"},
        {"id": "cobi_open_space", "attribution": "NOT USED"}])
    assert "OpenStreetMap contributors" in md and "BIMPRD" in md and "USGS NGP" in md
    assert "Protomaps" in md and "NOT USED" not in md
    geom, attrs = md.split("## Names and attributes only")
    assert "OpenStreetMap" in geom and "BIMPRD" in attrs and "BIMPRD" not in geom


def test_attribution_unknown_source_fails(tmp_path):
    with pytest.raises(ValueError, match="no attribution"):
        attribution_md({"mystery"}, [])


def test_run_dist_writes_outputs_without_private(tmp_path):
    d = canon(tmp_path)
    out = tmp_path / "dist"
    sources = tmp_path / "sources.yaml"
    sources.write_text("sources:\n- {id: osm_overpass_trails, name: OSM, kind: overpass, endpoint: null, attribution: osm attr}\n"
                       "- {id: bimprd_trails_agol_2022, name: B, kind: arcgis_rest, endpoint: null, attribution: bimprd attr}\n"
                       "- {id: usgs_3dep_dem_island, name: U, kind: elevation, endpoint: null, attribution: usgs attr}\n")
    run_dist(d, out, sources, tmp_path / "ATTR.md", date="2026-10-18T00:00:00+00:00")
    assert {p.name for p in out.iterdir()} == {"trails.json", "ATTRIBUTION.md", "segments.tiles.geojson", "coverage_segments.json"}
    for p in out.iterdir():
        assert PRIV not in p.read_text()
    assert (tmp_path / "ATTR.md").read_text() == (out / "ATTRIBUTION.md").read_text()


def test_coverage_segments_selects_included_or_trail_segments(tmp_path):
    segs = [feat("seg-000001", included=False, trail_ids=[]),
            feat("seg-000002", included=True, trail_ids=[]),
            feat("seg-000003", included=False, trail_ids=["trail-a"])]
    d = canon(tmp_path, segs=segs)
    doc = coverage_segments_doc(d / "segments.geojson", {"seg-000003": ["trail-a"]}, "v1")
    assert [s["id"] for s in doc["segments"]] == ["seg-000002", "seg-000003"]
    assert doc["version"] == "v1"
    s = doc["segments"][1]
    assert set(s) == {"id", "length_m", "included", "trail_ids", "coords"}
    assert s["included"] is False and s["trail_ids"] == ["trail-a"] and s["length_m"] == 100.0


def test_coverage_segments_rounds_and_takes_trail_ids_from_map(tmp_path):
    a = feat("seg-000002", trail_ids=["trail-a", "trail-gone"])
    a["geometry"]["coordinates"] = [[-122.1234567, 47.0000004], [-122.1, 47.1]]
    d = canon(tmp_path, segs=[a, feat("seg-000001", trail_ids=["trail-a"])])
    doc = coverage_segments_doc(d / "segments.geojson", {"seg-000001": ["trail-a"], "seg-000002": ["trail-a"]}, "v1")
    assert [s["id"] for s in doc["segments"]] == ["seg-000001", "seg-000002"]
    s = doc["segments"][1]
    assert s["trail_ids"] == ["trail-a"]
    assert s["coords"] == [[-122.123457, 47.0], [-122.1, 47.1]]


def test_coverage_segments_refuses_non_redistributable(tmp_path):
    d = canon(tmp_path, segs=[feat("seg-000001", geometry_redistributable=False)])
    with pytest.raises(ValueError, match="redistributable"):
        coverage_segments_doc(d / "segments.geojson", {}, "v1")


def test_run_dist_writes_coverage_segments(tmp_path):
    d = canon(tmp_path)
    out = tmp_path / "dist"
    sources = tmp_path / "sources.yaml"
    sources.write_text("sources:\n- {id: osm_overpass_trails, name: OSM, kind: overpass, endpoint: null, attribution: osm attr}\n"
                       "- {id: bimprd_trails_agol_2022, name: B, kind: arcgis_rest, endpoint: null, attribution: bimprd attr}\n"
                       "- {id: usgs_3dep_dem_island, name: U, kind: elevation, endpoint: null, attribution: usgs attr}\n")
    run_dist(d, out, sources, tmp_path / "ATTR.md", date="2026-10-18T00:00:00Z")
    cov = json.loads((out / "coverage_segments.json").read_text())
    assert cov["version"] == json.loads((out / "trails.json").read_text())["version"]
    assert cov["segments"]


def test_summary_helpers(tmp_path):
    from bitrails.summary import agency_only_rows, conflict_counts, near_duplicates, short_trails
    c = tmp_path / "c.md"
    c.write_text("| kind | count |\n|---|---|\n| name_mismatch | 35 |\n| geometry_offset | 310 |\n")
    assert conflict_counts(c) == [("name_mismatch", 35), ("geometry_offset", 310)]
    tr = [{"id": "a", "name": "Gazzam Lake Main Trail", "computed_length_mi": 0.8},
          {"id": "b", "name": "Gazzam Main Trail", "computed_length_mi": 0.01},
          {"id": "c", "name": "Grand Forest East", "computed_length_mi": 0.9},
          {"id": "d", "name": "Grand Forest East Trail", "computed_length_mi": 0.5}]
    assert [t["id"] for t in short_trails(tr)] == ["b"]
    pairs = near_duplicates(tr)
    assert ("Grand Forest East", "Grand Forest East Trail") in pairs
    d = canon(tmp_path)
    rows = agency_only_rows(d / "private" / "agency_only.geojson", [{"id": "trail-a", "name": "A"}])
    assert rows == [["A", "agency_trail", 1, "0.1", "0.1"]]


def _doc(tmp_path, **over):
    d = canon(tmp_path)
    doc = json.loads((d / "trails.json").read_text())
    doc["trails"][0].update(over)
    return doc, shippable_info(d / "segments.geojson", None)


def test_override_official_length_passes_through_dist(tmp_path):
    doc, info = _doc(tmp_path, official_length_mi=1.2, official_length_source="https://example.org/map")
    (t,) = dist_trails_doc(doc, info, "v")["trails"]
    assert (t["official_length_mi"], t["official_length_source"]) == (1.2, "https://example.org/map")


def test_dist_official_length_null_by_default(tmp_path):
    doc, info = _doc(tmp_path)
    (t,) = dist_trails_doc(doc, info, "v")["trails"]
    assert t["official_length_mi"] is None and t["official_length_source"] is None


@pytest.mark.parametrize("name", [None, "  "])
def test_dist_drops_unnamed_trails(tmp_path, name):
    doc, info = _doc(tmp_path, name=name)
    assert dist_trails_doc(doc, info, "v")["trails"] == []


def test_summary_flags_large_official_gap():
    from bitrails.summary import official_vs_computed_rows
    rows = official_vs_computed_rows([
        {"name": "Far", "official_length_mi": 1.0, "official_length_source": "src", "computed_mi": 0.5, "agency_only_mi": 0.0},
        {"name": "Near", "official_length_mi": 1.0, "official_length_source": "src", "computed_mi": 0.9, "agency_only_mi": 0.05},
        {"name": "None", "official_length_mi": None, "computed_mi": 1, "agency_only_mi": 0}])
    assert len(rows) == 2
    assert "⚠" in rows[0][4] and "⚠" not in rows[1][4]


# --- tiny trails (< 30 m) are folded into a touching trail or hidden ---
LON, LAT = -122.5, 47.6
DLAT = 1 / 111_200  # ~1 m of latitude in degrees


def _line(i, lat0_m, lat1_m, lon_off_m=0.0, length=None):
    """Segment running north from lat0_m to lat1_m (metres from the origin); lon_off_m shifts it east."""
    lon = LON + lon_off_m / 75_000
    f = feat(i, length_m=length if length is not None else abs(lat1_m - lat0_m))
    f["geometry"]["coordinates"] = [[lon, LAT + lat0_m * DLAT], [lon, LAT + lat1_m * DLAT]]
    return f


def _trail(tid, name, ids, mi):
    return {"id": tid, "name": name, "manager": None, "park": None, "segment_ids": ids, "official_length_mi": None,
            "computed_length_mi": mi, "gain_m": 0, "loss_m": 0, "climb_total_m": 0, "trailheads": [],
            "official_map_url": None}


def _tiny_doc(tmp_path, segs, trails):
    d = canon(tmp_path, segs=segs, trails=trails)
    doc = json.loads((d / "trails.json").read_text())
    return dist_trails_doc(doc, shippable_info(d / "segments.geojson", None), "v")


def test_tiny_trail_folded_into_touching_trail(tmp_path):
    segs = [_line("seg-000001", 0, 500), _line("seg-000002", 500, 520)]
    out = _tiny_doc(tmp_path, segs, [_trail("trail-a", "Big", ["seg-000001"], 0.31),
                                     _trail("trail-b", "Tiny", ["seg-000002"], 0.012)])
    (t,) = out["trails"]
    assert t["id"] == "trail-a" and t["segment_ids"] == ["seg-000001", "seg-000002"]
    assert t["computed_length_mi"] == round(520 / 1609.344, 3)
    assert t["folded_from"] == ["trail-b"]


def test_tiny_trail_without_neighbour_hidden(tmp_path):
    segs = [_line("seg-000001", 0, 500), _line("seg-000002", 0, 20, lon_off_m=300)]
    out = _tiny_doc(tmp_path, segs, [_trail("trail-a", "Big", ["seg-000001"], 0.31),
                                     _trail("trail-b", "Tiny", ["seg-000002"], 0.012)])
    (t,) = out["trails"]
    assert t["segment_ids"] == ["seg-000001"] and "folded_from" not in t


def test_tiny_trail_prefers_longest_neighbour(tmp_path):
    segs = [_line("seg-000001", 0, 200), _line("seg-000002", 0, 500, lon_off_m=100),
            _line("seg-000003", 200, 220)]
    segs[2]["geometry"]["coordinates"][1] = [LON + 100 / 75_000, LAT + 500 * DLAT]  # joins both ends
    segs[2]["geometry"]["coordinates"][0] = [LON, LAT + 200 * DLAT]
    out = _tiny_doc(tmp_path, segs, [_trail("trail-a", "Short", ["seg-000001"], 0.12),
                                     _trail("trail-b", "Long", ["seg-000002"], 0.31),
                                     _trail("trail-c", "Tiny", ["seg-000003"], 0.012)])
    by = {t["id"]: t for t in out["trails"]}
    assert set(by) == {"trail-a", "trail-b"}
    assert by["trail-b"]["segment_ids"] == ["seg-000002", "seg-000003"]
    assert "folded_from" not in by["trail-a"]


def test_island_totals_unchanged_by_folding(tmp_path):
    segs = [_line("seg-000001", 0, 500), _line("seg-000002", 500, 520)]
    out = _tiny_doc(tmp_path, segs, [_trail("trail-a", "Big", ["seg-000001"], 0.31),
                                     _trail("trail-b", "Tiny", ["seg-000002"], 0.012)])
    assert out["island_total_m"] == 100.0 and out["island_total_shippable_m"] == 60.0


def test_tiny_trail_rows_list_folded_and_hidden():
    from bitrails.summary import tiny_trail_rows
    canon_trails = [{"id": "t1", "name": "Big", "segment_ids": ["s1"]}, {"id": "t2", "name": "Folded", "segment_ids": ["s2"]},
                    {"id": "t3", "name": "Hidden", "segment_ids": ["s3"]}, {"id": "t4", "name": "Private", "segment_ids": ["p"]}]
    dist = [{"id": "t1", "name": "Big", "folded_from": ["t2"]}]
    assert tiny_trail_rows(canon_trails, dist, {"s1", "s2", "s3"}) == [["Folded", "Big"], ["Hidden", "hidden"]]


def test_segment_trail_map_inverts_segment_ids():
    doc = {"trails": [{"id": "trail-b", "segment_ids": ["s1", "s2"]}, {"id": "trail-a", "segment_ids": ["s2"]}]}
    assert segment_trail_map(doc) == {"s1": ["trail-b"], "s2": ["trail-a", "trail-b"]}


def _fold_canon(tmp_path):
    """trail-big (100 m) and trail-tiny (10 m, not included) touch; trail-tiny folds into trail-big."""
    segs = [feat("seg-000001", trail_ids=["trail-big"]),
            feat("seg-000002", included=False, trail_ids=["trail-tiny"], length_m=10.0)]
    row = {"official_length_mi": None, "computed_length_mi": 0.1, "gain_m": 0, "loss_m": 0, "climb_total_m": 0,
           "trailheads": [], "official_map_url": None, "manager": None, "park": None}
    trails = [dict(row, id="trail-big", name="Big", segment_ids=["seg-000001"]),
              dict(row, id="trail-tiny", name="Tiny", segment_ids=["seg-000002"])]
    return canon(tmp_path, segs=segs, trails=trails)


def test_coverage_segments_folded_segment_names_receiver(tmp_path):
    d = _fold_canon(tmp_path)
    info = shippable_info(d / "segments.geojson", None)
    trails = dist_trails_doc(json.loads((d / "trails.json").read_text()), info, "v1")
    assert [(t["id"], t.get("folded_from")) for t in trails["trails"]] == [("trail-big", ["trail-tiny"])]
    doc = coverage_segments_doc(d / "segments.geojson", segment_trail_map(trails), "v1")
    by = {s["id"]: s for s in doc["segments"]}
    assert by["seg-000002"]["trail_ids"] == ["trail-big"] and by["seg-000002"]["included"] is False
    tiles = write_tile_input(d / "segments.geojson", tmp_path / "t.geojson", segment_trail_map(trails))
    tp = {f["properties"]["id"]: f["properties"] for f in json.loads(tiles.read_text())["features"]}
    assert tp["seg-000002"]["trail_ids"] == "trail-big"


def test_coverage_segments_drops_noninc_segment_whose_trail_filtered(tmp_path):
    segs = [feat("seg-000001", included=False, trail_ids=["trail-gone"]), feat("seg-000002", trail_ids=[])]
    d = canon(tmp_path, segs=segs)
    doc = coverage_segments_doc(d / "segments.geojson", {}, "v1")
    assert [s["id"] for s in doc["segments"]] == ["seg-000002"]
    assert doc["segments"][0]["trail_ids"] == []


def test_coverage_segments_canonical_orientation(tmp_path):
    fwd = feat("seg-000001")
    fwd["geometry"]["coordinates"] = [[-122.5, 47.6], [-122.4, 47.7]]
    rev = feat("seg-000002")
    rev["geometry"]["coordinates"] = [[-122.4, 47.7], [-122.45, 47.65], [-122.5, 47.6]]
    tie = feat("seg-000003")  # same lon after rounding: lat decides
    tie["geometry"]["coordinates"] = [[-122.4000001, 47.7], [-122.4, 47.6]]
    d = canon(tmp_path, segs=[fwd, rev, tie])
    doc = coverage_segments_doc(d / "segments.geojson", {}, "v1")
    by = {s["id"]: s["coords"] for s in doc["segments"]}
    assert by["seg-000001"] == [[-122.5, 47.6], [-122.4, 47.7]]
    assert by["seg-000002"] == [[-122.5, 47.6], [-122.45, 47.65], [-122.4, 47.7]]
    assert by["seg-000003"] == [[-122.4, 47.6], [-122.4, 47.7]]
    for c in by.values():
        assert tuple(c[0]) <= tuple(c[-1])


def test_run_dist_folded_segments_carry_receiver(tmp_path):
    d = _fold_canon(tmp_path)
    out = tmp_path / "dist"
    sources = tmp_path / "sources.yaml"
    sources.write_text("sources:\n- {id: osm_overpass_trails, name: OSM, kind: overpass, endpoint: null, attribution: osm attr}\n"
                       "- {id: usgs_3dep_dem_island, name: U, kind: elevation, endpoint: null, attribution: usgs attr}\n")
    run_dist(d, out, sources, tmp_path / "ATTR.md", date="2026-10-18T00:00:00Z")
    trails = json.loads((out / "trails.json").read_text())
    cov = {s["id"]: s for s in json.loads((out / "coverage_segments.json").read_text())["segments"]}
    for t in trails["trails"]:
        for i in t["segment_ids"]:
            assert t["id"] in cov[i]["trail_ids"]
    tp = {f["properties"]["id"]: f["properties"] for f in json.loads((out / "segments.tiles.geojson").read_text())["features"]}
    assert tp["seg-000002"]["trail_ids"] == "trail-big"
