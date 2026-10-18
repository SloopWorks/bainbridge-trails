import json

from shapely.geometry import LineString, Polygon

from bitrails.build import segment_features
from bitrails.classify import Area, InclusionRule, classify, is_included, island_total_m, load_rule
from bitrails.geo import to_wgs
from bitrails.segments import Segment, SourceRef

OSM = "osm_overpass_trails"
AG = "bimprd_trails_agol_2022"
STO = "cobi_sto_alignments"
X0, Y0 = 525_000.0, 5_275_000.0
ROAD = r"\b(road|rd|avenue|ave|way|street|st|lane|ln)\.?(\s+(ne|nw|se|sw|north|south|east|west))?$"
DEFAULT = InclusionRule(frozenset({"agency_trail", "preserve_trail", "multi_use_path"}), True, ROAD)


def line(*pts_m):
    return to_wgs(LineString([(X0 + x, Y0 + y) for x, y in pts_m]))


def poly(x0, y0, x1, y1):
    return to_wgs(Polygon([(X0 + x0, Y0 + y0), (X0 + x1, Y0 + y0), (X0 + x1, Y0 + y1), (X0 + x0, Y0 + y1)]))


def osm(n, geom, redist=True, **tags):
    tags = {"geometry_redistributable": redist, **tags}
    return Segment(f"{OSM}:{n}", geom, [SourceRef(OSM, f"way/{n}", dict(tags))], tags)


PARK = Area("Gazzam Lake Nature Preserve", poly(-10, -100, 1000, 100), "BIMPRD")


def by_id(segs):
    return {s.id: s for s in segs}


def test_private_access_excluded_from_total():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="Lake Trail"),
                     osm(2, line((300, 0), (500, 0)), highway="path", name="Lake Trail", access="private")],
                    parks=[PARK])
    s = by_id(segs)
    assert s[f"{OSM}:2"].public_access == "no"
    assert s[f"{OSM}:2"].category == "agency_trail"  # still classified + shown
    assert not is_included(s[f"{OSM}:2"], DEFAULT)
    assert s[f"{OSM}:1"].public_access == "yes"
    assert abs(island_total_m(segs, DEFAULT) - 300) < 0.5


def test_access_values_map_to_public_access():
    segs = classify([osm(1, line((0, 500), (100, 500)), highway="path", access="permissive"),
                     osm(2, line((0, 600), (100, 600)), highway="path", access="permit"),
                     osm(3, line((0, 700), (100, 700)), highway="path"),
                     osm(4, line((0, 800), (100, 800)), highway="path", access="private", foot="yes"),
                     osm(5, line((0, 900), (100, 900)), highway="path", access="no")])
    pa = {s.id.split(":")[1]: s.public_access for s in segs}
    assert pa == {"1": "yes", "2": "permit", "3": "unknown", "4": "yes", "5": "no"}
    assert by_id(segs)[f"{OSM}:5"].category == "seasonal_closed"


def test_unrecognised_foot_does_not_mask_private_access_in_park():
    segs = classify([osm(1, line((0, 0), (100, 0)), highway="path", foot="destination", access="private"),
                     osm(2, line((0, 10), (100, 10)), highway="path", foot="destination", access="no"),
                     osm(3, line((0, 20), (100, 20)), highway="path", foot="destination"),
                     osm(4, line((0, 30), (100, 30)), highway="path", foot="yes", access="private")],
                    parks=[PARK])
    pa = {s.id.split(":")[1]: s.public_access for s in segs}
    # explicit recognised foot=yes stays more specific than access=private (existing behaviour)
    assert pa == {"1": "no", "2": "no", "3": "yes", "4": "yes"}


def test_rule_change_changes_total_without_reprocessing(tmp_path):
    segs = classify([osm(1, line((0, 0), (400, 0)), highway="path", name="Lake Trail"),
                     osm(2, line((0, 500), (250, 500)), highway="path", informal="yes")], parks=[PARK])
    before = [(s.id, s.category) for s in segs]
    assert abs(island_total_m(segs, DEFAULT) - 400) < 0.5
    rule_file = tmp_path / "inclusion.yaml"
    rule_file.write_text("categories: [agency_trail, informal]\nconnector:\n  count_named: false\n")
    wider = load_rule(rule_file)
    assert abs(island_total_m(segs, wider) - 650) < 0.5
    assert [(s.id, s.category) for s in segs] == before  # classification untouched


def test_unnamed_informal_path_kept_with_null_name():
    segs = classify([osm(7, line((0, 500), (200, 500)), highway="path")], parks=[PARK])
    (s,) = segs
    assert s.category == "informal" and s.name is None and s.confidence == "low"
    assert not is_included(s, DEFAULT)
    public, private = segment_features(segs, DEFAULT)
    assert private == []
    (f,) = public
    assert f["properties"]["name"] is None
    assert f["properties"]["category"] == "informal"
    assert f["properties"]["included"] is False


def test_duplicate_segment_ids_counted_once_in_island_total():
    # the same segment listed twice (e.g. concatenated per-trail lists) counts once; the
    # two-trails-share-one-segment case is test_trails::test_shared_segment_counted_once_in_island_total
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="A Trail")], parks=[PARK])
    assert abs(island_total_m(segs + segs, DEFAULT) - 300) < 0.5


def test_sidewalk_in_a_trail_still_not_counted():
    segs = classify([osm(1, line((0, 500), (100, 500)), highway="footway", footway="sidewalk"),
                     osm(2, line((0, 600), (100, 600)), highway="footway", surface="concrete"),
                     osm(3, line((0, 700), (100, 700)), highway="path", name="Morgan Road Path")])
    for s in segs:
        s.trail_ids = ["trail-x"]
    assert [s.subtype for s in segs] == ["sidewalk", "walkway", "named_path"]
    assert [is_included(s, DEFAULT) for s in segs] == [False, False, True]


def test_sidewalk_is_connector_and_not_counted_even_if_road_named():
    segs = classify([osm(1, line((0, 500), (100, 500)), highway="footway", footway="sidewalk",
                         name="Madison Avenue North"),
                     osm(2, line((0, 600), (100, 600)), highway="footway", footway="crossing"),
                     osm(3, line((0, 700), (100, 700)), highway="path", name="Morgan Road Path")])
    s = by_id(segs)
    assert s[f"{OSM}:1"].category == "connector" and s[f"{OSM}:1"].subtype == "sidewalk"
    assert s[f"{OSM}:2"].subtype == "crossing"
    assert s[f"{OSM}:3"].category == "connector" and s[f"{OSM}:3"].subtype == "named_path"
    assert [x.id for x in segs if is_included(x, DEFAULT)] == [f"{OSM}:3"]


def test_categories_preserve_multi_use_beach_planned():
    pres = Area("Hilltop", poly(-10, 1900, 500, 2100), "BILT Fee Owned")
    ease = Area("Private CE", poly(-10, 2900, 500, 3100), "BILT Conservation Easement")
    sto = Segment(f"{STO}:1~1", line((0, 4000), (900, 4000)), [SourceRef(STO, "1", {})],
                  {"geometry_redistributable": False, "AlignmentNumber": "Alignment 1"})
    ag_only = Segment(f"{AG}:3~1", line((0, 5000), (300, 5000)), [SourceRef(AG, "3", {})],
                      {"geometry_redistributable": False, "TRAILNAME": "Farm Trail", "name": "Farm Trail",
                       "agency:operator": "BIMPRD", "agency:sources": AG, "HIKING_ALL": "Y", "BICYCLES_A": "N"})
    segs = by_id(classify([
        osm(1, line((0, 2000), (300, 2000)), highway="path"),
        osm(2, line((0, 3000), (300, 3000)), highway="path"),
        osm(3, line((0, 6000), (300, 6000)), highway="cycleway"),
        osm(4, line((0, 7000), (300, 7000)), highway="footway", name="Fletcher Landing Road End"),
        sto, ag_only], preserves=[pres, ease]))
    assert segs[f"{OSM}:1"].category == "preserve_trail"
    assert segs[f"{OSM}:1"].manager == "Bainbridge Island Land Trust"
    assert segs[f"{OSM}:2"].category == "easement"
    assert segs[f"{OSM}:3"].category == "multi_use_path"
    assert segs[f"{OSM}:4"].category == "beach_access"
    assert segs[sto.id].category == "planned" and not is_included(segs[sto.id], DEFAULT)
    a = segs[ag_only.id]
    assert (a.category, a.confidence, a.manager, a.public_access) == ("agency_trail", "low", "BIMPRD", "yes")
    assert a.allowed_uses == ["foot"]


def test_shippable_total_and_private_features_split():
    ag_only = Segment(f"{AG}:3~1", line((0, 0), (300, 0)), [SourceRef(AG, "3", {})],
                      {"geometry_redistributable": False, "name": "Farm Trail", "agency:sources": AG})
    segs = classify([osm(1, line((0, 50), (200, 50)), highway="path", name="Lake Trail"), ag_only],
                    parks=[PARK])
    assert abs(island_total_m(segs, DEFAULT) - 500) < 0.5
    assert abs(island_total_m(segs, DEFAULT, shippable_only=True) - 200) < 0.5
    public, private = segment_features(segs, DEFAULT)
    assert [f["properties"]["id"] for f in public] == [f"{OSM}:1"]
    assert [f["properties"]["id"] for f in private] == [ag_only.id]
    coords = public[0]["geometry"]["coordinates"]
    assert all(round(c, 6) == c for pt in coords for c in pt)
    assert list(public[0]["properties"]) == sorted(public[0]["properties"])
    json.dumps(public)  # serializable
