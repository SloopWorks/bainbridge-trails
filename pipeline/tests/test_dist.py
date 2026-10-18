import json

import pytest

from bitrails.dist import (TILE_PROPS, attribution_md, dataset_version, dist_trails_doc, run_dist, shippable_info,
                           used_source_ids, write_tile_input)

PUB = {"seg-000001", "seg-000002"}
PRIV = "seg-000099"


def feat(i, **props):
    base = {"id": i, "category": "agency_trail", "subtype": None, "manager": "BIMPRD", "sources": ["osm_overpass_trails"],
            "confidence": "high", "public_access": "yes", "allowed_uses": ["foot"], "included": True,
            "geometry_redistributable": True, "name": "Loop", "trail_ids": ["trail-a", "trail-b"], "park": "P",
            "length_m": 10.0, "gain_m": 1.0, "loss_m": 1.0, "elev_min_m": 1.0, "elev_max_m": 2.0}
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
    out = write_tile_input(d / "segments.geojson", tmp_path / "t.geojson")
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
        write_tile_input(d / "segments.geojson", tmp_path / "t.geojson")


def test_dist_trails_shape_and_no_private_ids(tmp_path):
    d = canon(tmp_path)
    info = shippable_info(d / "segments.geojson", None)
    doc = dist_trails_doc(json.loads((d / "trails.json").read_text()), info, "2026-10-18+abcd1234")
    assert doc["version"] == "2026-10-18+abcd1234"
    assert doc["island_total_m"] == 100.0 and doc["island_total_shippable_m"] == 60.0
    assert [t["id"] for t in doc["trails"]] == ["trail-a"]  # all-private trail dropped
    assert set(doc["trails"][0]) == {"id", "name", "manager", "park", "segment_ids", "computed_length_mi", "gain_m",
                                     "loss_m", "climb_total_m", "trailheads", "official_map_url",
                                     "agency_only_length_mi"}
    assert doc["trails"][0]["segment_ids"] == ["seg-000001"]
    assert PRIV not in json.dumps(doc)


def test_trail_stats_from_shippable_segments_only(tmp_path):
    d = canon(tmp_path)  # trail-a: shippable seg-000001 (10 m) + private seg-000099; canonical says 0.1 mi
    (d / "elevation.json").write_text(json.dumps({"seg-000001": [[0, 10.0], [10, 15.0]]}))
    info = shippable_info(d / "segments.geojson", d / "elevation.json")
    (t,) = dist_trails_doc(json.loads((d / "trails.json").read_text()), info, "v")["trails"]
    assert t["segment_ids"] == ["seg-000001"]
    assert t["computed_length_mi"] == round(10 / 1609.344, 3)
    assert t["agency_only_length_mi"] == round(0.1 - 10 / 1609.344, 3)
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
    assert {p.name for p in out.iterdir()} == {"trails.json", "ATTRIBUTION.md", "segments.tiles.geojson"}
    for p in out.iterdir():
        assert PRIV not in p.read_text()
    assert (tmp_path / "ATTR.md").read_text() == (out / "ATTRIBUTION.md").read_text()


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
    assert rows == [["A", "agency_trail", 1, "0.0", "0.0"]]
