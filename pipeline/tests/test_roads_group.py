from shapely.geometry import LineString

from bitrails.geo import to_wgs
from bitrails.roads.classify import RoadRule, classify
from bitrails.roads.group import build_roads
from bitrails.segments import Segment, SourceRef

OSM = "osm_overpass_roads"
LAPR = "wsdot_local_agency_public_road"
X0, Y0 = 525_000.0, 5_275_000.0
RULE = RoadRule(frozenset({"residential", "collector"}), frozenset({"public_city"}))
CITY = {"Jurisdiction": "City", "CityName": "Bainbridge Island"}


def line(*pts_m):
    return to_wgs(LineString([(X0 + x, Y0 + y) for x, y in pts_m]))


def seg(n, geom, name=None, city=True, highway="residential", **tags):
    tags = {"geometry_redistributable": True, "highway": highway, **({"name": name} if name else {}), **tags}
    refs = [SourceRef(OSM, f"way/{n}", dict(tags))]
    if city:
        refs.append(SourceRef(LAPR, f"{LAPR}/{n}", dict(CITY)))
    return Segment(f"seg-{n:06d}", geom, refs, tags)


def test_same_name_pieces_make_one_road_and_back_reference_it():
    segs = classify([seg(1, line((0, 0), (300, 0)), "Northeast Koura Road"),
                     seg(2, line((300, 0), (600, 0)), "Koura Rd NE")])
    roads = build_roads(segs, RULE)
    assert len(roads) == 1
    road = roads[0]
    assert road.id == "road-northeast-koura-road"
    assert road.segment_ids == ["seg-000001", "seg-000002"]
    assert road.counted and road.public and road.ownership == "public_city"
    assert [s.road_ids for s in segs] == [[road.id], [road.id]]


def test_one_name_in_two_places_makes_two_roads():
    segs = classify([seg(3, line((0, 0), (300, 0)), "Madison Avenue North"),
                     seg(4, line((5_000, 0), (5_300, 0)), "Madison Avenue North")])
    roads = build_roads(segs, RULE)
    assert [r.id for r in roads] == ["road-madison-avenue-north-1", "road-madison-avenue-north-2"]


def test_unnamed_and_driveway_segments_join_no_road():
    segs = classify([seg(5, line((0, 0), (300, 0))),
                     seg(6, line((0, 0), (80, 0)), "Service Spur", highway="service", service="driveway")])
    assert build_roads(segs, RULE) == []
    assert all(not s.road_ids for s in segs)


def test_private_road_is_grouped_but_not_counted():
    segs = classify([seg(7, line((0, 0), (300, 0)), "Northeast Warabi Lane", city=False, access="private")])
    roads = build_roads(segs, RULE)
    assert len(roads) == 1 and not roads[0].counted and not roads[0].public
    assert roads[0].ownership == "private"


def test_road_takes_the_highest_class_and_the_dominant_ownership():
    segs = classify([seg(8, line((0, 0), (900, 0)), "High Road", highway="tertiary"),
                     seg(9, line((900, 0), (1_000, 0)), "High Road", city=False, access="private")])
    road = build_roads(segs, RULE)[0]
    assert road.category == "collector"
    assert road.ownership == "public_city"
    assert sorted(road.ownership_mix) == ["private", "public_city"]


def test_elevation_sums_over_the_road():
    segs = classify([seg(10, line((0, 0), (300, 0)), "Hill Road"),
                     seg(11, line((300, 0), (600, 0)), "Hill Road")])
    elev = {"seg-000010": {"gain_m": 10.0, "loss_m": 2.0}, "seg-000011": {"gain_m": 5.0, "loss_m": 1.0}}
    road = build_roads(segs, RULE, elev)[0]
    assert (road.gain_m, road.loss_m, road.climb_total_m) == (15.0, 3.0, 18.0)
