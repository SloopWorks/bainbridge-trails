import json

import pytest
from shapely.geometry import Polygon

from bitrails.roads.dist import (TILE_PROPS, coverage_segments_doc, dist_roads_doc, road_ends_doc,
                                 segment_road_map, shippable_info, used_source_ids, write_tile_input)

PUB = "seg-000001"


def feat(i, coords=None, **props):
    base = {"id": i, "name": "Koura Road", "agency_names": ["Koura Rd NE"], "category": "residential",
            "ownership": "public_city", "ownership_source": "wsdot_local_agency_public_road",
            "maintainer": "City of Bainbridge Island", "functional_class": "Urban Local Access",
            "surface": "paved", "speed_mph": 25, "lanes": 2, "shoulder": "yes", "sidewalk": None,
            "bike": None, "row_width_ft": 60.0, "public_access": "yes", "allowed_uses": ["foot", "bicycle"],
            "sources": ["osm_overpass_roads"], "confidence": "high", "included": True,
            "geometry_redistributable": True, "road_ids": ["road-koura-road"], "length_m": 100.0,
            "gain_m": 2.0, "loss_m": 1.0}
    base.update(props)
    return {"type": "Feature",
            "geometry": {"type": "LineString", "coordinates": coords or [[-122.5, 47.6], [-122.49, 47.61]]},
            "properties": base}


def canon(tmp_path, segs=None, roads=None):
    d = tmp_path / "canonical"
    (d / "private").mkdir(parents=True)
    (d / "segments.geojson").write_text(json.dumps(
        {"type": "FeatureCollection", "features": segs or [feat(PUB)]}))
    doc = {"island_total_m": 100.0, "island_total_mi": 0.06, "island_total_shippable_m": 100.0,
           "island_total_shippable_mi": 0.06, "rule": {}, "dataset": {},
           "roads": roads or [{"id": "road-koura-road", "name": "Koura Road", "category": "residential",
                               "ownership": "public_city", "maintainer": "City of Bainbridge Island",
                               "segment_ids": [PUB, "seg-000099"], "computed_length_mi": 0.2,
                               "gain_m": 2.0, "loss_m": 1.0, "climb_total_m": 3.0, "surface": "paved",
                               "speed_mph": 25, "public": True, "counted": True}]}
    (d / "roads.json").write_text(json.dumps(doc))
    return d, doc


def test_dist_road_row_counts_only_shippable_segments_and_names_the_gap(tmp_path):
    d, doc = canon(tmp_path)
    info = shippable_info(d / "segments.geojson", None)
    out = dist_roads_doc(doc, info, "v1")
    row = out["roads"][0]
    assert row["segment_ids"] == [PUB]                       # seg-000099 has no shippable geometry
    assert row["computed_length_mi"] == pytest.approx(100.0 / 1609.344, abs=1e-3)
    assert row["agency_only_length_mi"] > 0
    assert row["climb_total_m"] == 3.0


def test_roads_with_nothing_to_draw_are_dropped(tmp_path):
    d, doc = canon(tmp_path, roads=[{"id": "road-ghost", "name": "Ghost Road", "segment_ids": ["seg-000099"],
                                     "computed_length_mi": 0.5, "category": "residential",
                                     "ownership": "private", "maintainer": None, "gain_m": 0, "loss_m": 0,
                                     "climb_total_m": 0, "surface": None, "speed_mph": None,
                                     "public": False, "counted": False}])
    info = shippable_info(d / "segments.geojson", None)
    assert dist_roads_doc(doc, info, "v1")["roads"] == []


def test_tiles_carry_only_whitelisted_props_and_join_road_ids(tmp_path):
    d, doc = canon(tmp_path)
    info = shippable_info(d / "segments.geojson", None)
    dist = dist_roads_doc(doc, info, "v1")
    out = write_tile_input(d / "segments.geojson", tmp_path / "t.geojson", segment_road_map(dist))
    props = json.loads(out.read_text())["features"][0]["properties"]
    assert set(props) <= set(TILE_PROPS)
    assert props["road_ids"] == "road-koura-road"
    assert props["ownership"] == "public_city"


def test_tiles_and_coverage_refuse_non_redistributable_geometry(tmp_path):
    d, _ = canon(tmp_path, segs=[feat("seg-000098", geometry_redistributable=False)])
    with pytest.raises(ValueError, match="refusing to tile"):
        write_tile_input(d / "segments.geojson", tmp_path / "t.geojson", {})
    with pytest.raises(ValueError, match="refusing to export"):
        coverage_segments_doc(d / "segments.geojson", {}, "v1")


def test_coverage_orientation_is_fixed(tmp_path):
    d, doc = canon(tmp_path, segs=[feat(PUB, coords=[[-122.49, 47.61], [-122.5, 47.6]])])
    cov = coverage_segments_doc(d / "segments.geojson", {PUB: ["road-koura-road"]}, "v1")
    coords = cov["segments"][0]["coords"]
    assert tuple(coords[0]) <= tuple(coords[-1])
    assert cov["segments"][0]["ownership"] == "public_city"


def test_road_ends_are_dead_ends_near_the_shore_of_counted_roads(tmp_path):
    island = Polygon([(-122.6, 47.5), (-122.4, 47.5), (-122.4, 47.7), (-122.6, 47.7)])
    # one segment running to the island edge, one inland junction pair that must not qualify
    segs = [feat(PUB, coords=[[-122.5, 47.6], [-122.5, 47.7]]),
            feat("seg-000002", coords=[[-122.5, 47.6], [-122.49, 47.6]], road_ids=["road-koura-road"])]
    d, doc = canon(tmp_path, segs=segs)
    info = shippable_info(d / "segments.geojson", None)
    dist = dist_roads_doc(doc, info, "v1")
    ends = road_ends_doc(d / "segments.geojson", segment_road_map(dist), dist, "v1",
                         cache_dir=tmp_path / "nocache", island=island)
    pts = [tuple(r["coordinates"]) for r in ends["road_ends"]]
    assert (-122.5, 47.7) in pts                     # touches the boundary
    assert (-122.5, 47.6) not in pts                 # shared endpoint = junction
    assert all(r["city_confirmed"] is False for r in ends["road_ends"])


def test_attribution_lists_geometry_and_attribute_sources(tmp_path):
    d, _ = canon(tmp_path, segs=[feat(PUB, sources=["osm_overpass_roads", "cobi_rd_streets"])])
    used = used_source_ids(d / "segments.geojson", {"road_ends": []})
    assert {"osm_overpass_roads", "cobi_rd_streets", "usgs_3dep_dem_island", "protomaps_basemap"} <= used
