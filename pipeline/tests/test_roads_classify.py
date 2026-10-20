import pytest
from shapely.geometry import LineString

from bitrails.geo import to_wgs
from bitrails.roads.classify import (RoadRule, classify, infer_unknown_ownership, is_included,
                                     island_total_m, load_rule, name_mismatch_counts,
                                     ownership_disagreements)
from bitrails.segments import Segment, SourceRef

OSM = "osm_overpass_roads"
COBI = "cobi_rd_streets"
LAPR = "wsdot_local_agency_public_road"
SR = "wsdot_state_route_lines"
X0, Y0 = 525_000.0, 5_275_000.0
RULE = RoadRule(frozenset({"state_route", "arterial", "collector", "residential"}),
                frozenset({"public_state", "public_city", "public_county", "public_parks", "public_school"}))


def line(*pts_m):
    return to_wgs(LineString([(X0 + x, Y0 + y) for x, y in pts_m]))


def seg(n, geom, tags=None, refs=(), redist=True):
    tags = {"geometry_redistributable": redist, **(tags or {})}
    base = [SourceRef(OSM, f"way/{n}", dict(tags))] if redist else []
    return Segment(f"seg-{n:06d}", geom, base + [SourceRef(sid, f"{sid}/1", dict(props))
                                                 for sid, props in refs], tags)


def one(s):
    return classify([s])[0]


def test_category_from_osm_highway():
    assert one(seg(1, line((0, 0), (100, 0)), {"highway": "secondary"})).category == "arterial"
    assert one(seg(2, line((0, 0), (100, 0)), {"highway": "tertiary"})).category == "collector"
    assert one(seg(3, line((0, 0), (100, 0)), {"highway": "residential"})).category == "residential"
    assert one(seg(4, line((0, 0), (100, 0)), {"highway": "service", "name": "Mill Lane"})).category == "lane"
    assert one(seg(5, line((0, 0), (100, 0)), {"highway": "service", "service": "driveway"})).category == "driveway"


def test_state_route_wins_over_highway_class():
    s = one(seg(6, line((0, 0), (500, 0)), {"highway": "primary", "name": "State Highway 305"},
                refs=[(SR, {"StateRouteNumber": "305", "RouteID": "305"})]))
    assert (s.category, s.ownership, s.maintainer) == ("state_route", "public_state", "WSDOT")


def test_wsdot_jurisdiction_decides_ownership_over_the_city_layer():
    s = one(seg(7, line((0, 0), (200, 0)), {"highway": "residential"},
                refs=[(LAPR, {"Jurisdiction": "Private", "RoadName": "PVT"}),
                      (COBI, {"Maint": "COBI", "Rd_Name": "Example", "RD_Desig": "Ln"})]))
    assert (s.ownership, s.ownership_source) == ("private", LAPR)
    assert ownership_disagreements([seg(7, line((0, 0), (200, 0)), {},
                                       refs=[(LAPR, {"Jurisdiction": "Private"}),
                                             (COBI, {"Maint": "COBI"})])])


def test_city_pvt_name_marker_means_private():
    s = one(seg(8, line((0, 0), (200, 0)), {"highway": "residential", "name": "Northeast Warabi Lane"},
                refs=[(COBI, {"Rd_Name": "Warabi", "RD_Desig": "Ln", "Suffix": "NE PVT"})]))
    assert s.ownership == "private"
    assert s.name == "Northeast Warabi Lane"           # OSM spelling is what the app shows


def test_unmatched_segment_is_unknown_and_uncounted():
    s = one(seg(9, line((0, 0), (200, 0)), {"highway": "residential", "name": "Stromville Lane"}))
    assert (s.ownership, s.ownership_source) == ("unknown", "none")
    assert not is_included(s, RULE)


def test_ownership_inferred_from_the_rest_of_the_street():
    segs = classify([
        seg(10, line((0, 0), (300, 0)), {"highway": "residential", "name": "Northeast Koura Road"},
            refs=[(LAPR, {"Jurisdiction": "City", "CityName": "Bainbridge Island"})]),
        seg(11, line((300, 0), (340, 0)), {"highway": "residential", "name": "Koura Rd NE"}),
    ])
    assert infer_unknown_ownership(segs) == 1
    assert (segs[1].ownership, segs[1].ownership_source) == ("public_city", "inferred:name")
    assert is_included(segs[1], RULE)


def test_inference_refuses_a_street_whose_segments_disagree():
    segs = classify([
        seg(12, line((0, 0), (300, 0)), {"highway": "residential", "name": "Split Road"},
            refs=[(LAPR, {"Jurisdiction": "City", "CityName": "Bainbridge Island"})]),
        seg(13, line((300, 0), (600, 0)), {"highway": "residential", "name": "Split Road"},
            refs=[(LAPR, {"Jurisdiction": "Private"})]),
        seg(14, line((600, 0), (640, 0)), {"highway": "residential", "name": "Split Rd"}),
    ])
    assert infer_unknown_ownership(segs) == 0
    assert segs[2].ownership == "unknown"


def test_private_and_lane_are_mapped_but_not_counted():
    private = one(seg(15, line((0, 0), (500, 0)), {"highway": "residential"},
                      refs=[(LAPR, {"Jurisdiction": "Private"})]))
    lane = one(seg(16, line((0, 0), (500, 0)), {"highway": "service", "name": "Mill Lane"},
                   refs=[(LAPR, {"Jurisdiction": "City", "CityName": "Bainbridge Island"})]))
    assert not is_included(private, RULE) and not is_included(lane, RULE)
    assert island_total_m([private, lane], RULE) == 0.0


def test_dropped_override_never_counts():
    s = one(seg(17, line((0, 0), (500, 0)), {"highway": "residential"},
                refs=[(LAPR, {"Jurisdiction": "City", "CityName": "Bainbridge Island"})]))
    assert is_included(s, RULE)
    s.dropped = True
    assert not is_included(s, RULE)


def test_attributes_fall_back_from_osm_to_the_city_layer():
    s = one(seg(18, line((0, 0), (500, 0)), {"highway": "tertiary"},
                refs=[(COBI, {"Rd_Surface": "Gravel", "Speed_Limi": "25", "Mini_width": 50,
                              "Maint": "COBI", "Rd_Name": "Example", "RD_Desig": "Rd"})]))
    assert (s.surface, s.speed_mph, s.row_width_ft) == ("gravel", 25, 50.0)
    s2 = one(seg(19, line((0, 0), (500, 0)), {"highway": "tertiary", "surface": "asphalt",
                                              "maxspeed": "35 mph", "shoulder": "yes"},
                 refs=[(COBI, {"Rd_Surface": "Gravel", "Speed_Limi": "25"})]))
    assert (s2.surface, s2.speed_mph, s2.shoulder) == ("paved", 35, "yes")


def test_island_total_counts_each_segment_once():
    a = one(seg(20, line((0, 0), (1000, 0)), {"highway": "tertiary"},
                refs=[(LAPR, {"Jurisdiction": "City", "CityName": "Bainbridge Island"})]))
    assert island_total_m([a, a], RULE) == pytest.approx(a.length_m)
    assert island_total_m([a], RULE, shippable_only=True) == pytest.approx(a.length_m)


def test_name_mismatch_counts_group_by_kind():
    segs = classify([
        seg(21, line((0, 0), (100, 0)), {"highway": "residential", "name": "Northeast Eagle Harbor Drive"},
            refs=[(COBI, {"PreDir": "NE", "Rd_Name": "Eagle Harbor", "RD_Desig": "Rd"})]),
        seg(22, line((0, 0), (100, 0)), {"highway": "residential", "name": "Northeast Wild Cherry Lane"},
            refs=[(COBI, {"Rd_Name": "Foster", "RD_Desig": "St", "Suffix": "NE"})]),
    ])
    assert name_mismatch_counts(segs) == {"different": 1, "type": 1}


def test_rule_file_round_trip_and_validation(tmp_path):
    p = tmp_path / "inclusion.roads.yaml"
    p.write_text("categories: [residential]\nownerships: [public_city]\n")
    rule = load_rule(p)
    assert rule.snapshot()["categories"] == ["residential"]
    p.write_text("categories: [freeway]\nownerships: [public_city]\n")
    with pytest.raises(ValueError, match="unknown categories"):
        load_rule(p)
    p.write_text("categories: [residential]\nownerships: [the_queen]\n")
    with pytest.raises(ValueError, match="unknown ownerships"):
        load_rule(p)
