import pytest
from shapely.geometry import LineString, Polygon

from bitrails.classify import Area, InclusionRule, classify, island_total_m
from bitrails.geo import to_utm, to_wgs
from bitrails.segments import Segment, SourceRef
from bitrails.trails import Relation, build_trails, slugify

OSM = "osm_overpass_trails"
AG = "bimprd_trails_agol_2022"
X0, Y0 = 525_000.0, 5_275_000.0
MI = 1609.344
RULE = InclusionRule(frozenset({"agency_trail", "preserve_trail", "multi_use_path"}))


def line(*pts_m):
    return to_wgs(LineString([(X0 + x, Y0 + y) for x, y in pts_m]))


def poly(x0, y0, x1, y1):
    return to_wgs(Polygon([(X0 + x0, Y0 + y0), (X0 + x1, Y0 + y0), (X0 + x1, Y0 + y1), (X0 + x0, Y0 + y1)]))


def osm(n, geom, **tags):
    tags = {"geometry_redistributable": True, **tags}
    return Segment(f"seg-{n:06d}", geom, [SourceRef(OSM, f"way/{n}", dict(tags))], tags)


PARK_A = Area("Fort Ward Park", poly(-10, -100, 2000, 100), "BIMPRD")
PARK_B = Area("Grand Forest West", poly(-10, 900, 2000, 1100), "BIMPRD")


def test_trail_lengths_sum_segments():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="Loop Trail"),
                     osm(2, line((300, 0), (300, 50), (700, 50)), highway="path", name="Loop Trail"),
                     osm(3, line((700, 50), (1200, 50)), highway="path", name="Loop Trail")], parks=[PARK_A])
    (t,) = build_trails(segs)
    assert t.id == "trail-loop-trail" and t.name == "Loop Trail"
    assert t.segment_ids == ["seg-000001", "seg-000002", "seg-000003"]  # ordered along the trail
    assert abs(t.computed_length_mi - sum(s.length_m for s in segs) / MI) < 1e-3
    assert t.official_length_mi is None
    assert t.park == "Fort Ward Park" and t.manager == "BIMPRD"
    assert all(s.trail_ids == ["trail-loop-trail"] for s in segs)


def test_shared_segment_counted_once_in_island_total():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="Ridge Trail"),
                     osm(2, line((300, 0), (600, 0)), highway="path", name="Ridge Trail"),
                     osm(3, line((600, 0), (900, 0)), highway="path", name="Ridge Trail")], parks=[PARK_A])
    rels = [Relation(1, "Ridge Trail", ("way/1", "way/2")), Relation(2, "Lake Loop", ("way/2", "way/3"))]
    trails = {t.id: t for t in build_trails(segs, rels)}
    assert set(trails) == {"trail-ridge-trail", "trail-lake-loop"}
    shared = next(s for s in segs if s.id == "seg-000002")
    assert shared.trail_ids == ["trail-lake-loop", "trail-ridge-trail"]
    trail_sum = sum(t.computed_length_mi for t in trails.values()) * MI
    assert abs(trail_sum - 1200) < 1  # shared segment counts toward each trail ...
    assert abs(island_total_m(segs, RULE) - 900) < 0.5  # ... but once toward the island


def test_same_name_disconnected_trails_get_park_suffix():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="Loop Trail"),
                     osm(2, line((0, 1000), (300, 1000)), highway="path", name="Loop Trail"),
                     osm(3, line((0, 5000), (300, 5000)), highway="path", name="Loop Trail"),
                     osm(4, line((0, 6000), (300, 6000)), highway="path", name="Loop Trail")],
                    parks=[PARK_A, PARK_B])
    ids = sorted(t.id for t in build_trails(segs))
    assert ids == ["trail-loop-trail", "trail-loop-trail-2", "trail-loop-trail-fort-ward-park",
                   "trail-loop-trail-grand-forest-west"]


def test_agency_name_groups_and_map_url():
    a = {"agency:name": "Gazzam Lake Trail", "name": "Gazzam Lake Trail", "agency:sources": AG,
         "agency:operator": "BIMPRD"}
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", **a),
                     osm(2, line((800, 0), (1200, 0)), highway="path", **a)], parks=[PARK_A])
    (t,) = build_trails(segs, map_urls={"fort ward": "https://example.org/fw.pdf"})
    assert t.segment_ids == ["seg-000001", "seg-000002"]  # agency name groups even with a gap
    assert t.official_map_url == "https://example.org/fw.pdf"


def test_unnamed_and_road_named_segments_not_grouped():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path"),
                     osm(2, line((0, 500), (300, 500)), highway="footway", footway="sidewalk",
                         name="Madison Avenue North")])
    assert build_trails(segs, road_name_pattern=r"\bavenue(\s+north)?$") == []


def test_overrides_rename_and_drop():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="Loop Trail"),
                     osm(2, line((0, 5000), (300, 5000)), highway="path", name="Junk Trail")], parks=[PARK_A])
    trails = build_trails(segs, overrides={"trails": {"trail-loop-trail": {"name": "Fort Ward Loop",
                                                                            "official_length_mi": 0.5,
                                                                            "official_length_source": "https://x"},
                                                      "trail-junk-trail": {"drop": True}}})
    (t,) = trails
    assert (t.id, t.name, t.official_length_mi) == ("trail-loop-trail", "Fort Ward Loop", 0.5)
    assert next(s for s in segs if s.id == "seg-000002").trail_ids == []


def test_override_official_length_requires_source():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="Loop Trail")], parks=[PARK_A])
    with pytest.raises(ValueError, match="trail-loop-trail"):
        build_trails(segs, overrides={"trails": {"trail-loop-trail": {"official_length_mi": 1.2}}})


def test_slugify():
    assert slugify("Hidden Cove Trail #2") == "hidden-cove-trail-2"
    assert slugify("Jim's Trail") == "jims-trail"


def test_agency_and_osm_named_pieces_of_one_trail_merge():
    a = {"agency:name": "Fairy Dell Trail", "name": "Fairy Dell Trail", "agency:sources": AG}
    segs = classify([osm(1, line((0, 3000), (200, 3000)), highway="path", **a),
                     osm(2, line((200, 3000), (204, 3000)), highway="path", name="Fairy Dell Trail"),
                     osm(3, line((204, 3000), (500, 3000)), highway="path", **a)])
    (t,) = build_trails(segs)
    assert t.id == "trail-fairy-dell-trail" and t.segment_ids == ["seg-000001", "seg-000002", "seg-000003"]


def test_osm_name_replaced_by_agency_name_is_alias():
    a = {"agency:name": "Meigs Trail", "name": "Meigs Trail", "osm:name": "Meigs Park Main Trail",
         "agency:sources": AG}
    segs = classify([osm(1, line((0, 3000), (200, 3000)), highway="path", **a),
                     osm(2, line((200, 3000), (207, 3000)), highway="path", name="Meigs Park Main Trail")])
    (t,) = build_trails(segs)
    assert (t.id, t.segment_ids) == ("trail-meigs-trail", ["seg-000001", "seg-000002"])


def test_same_name_pieces_next_to_relation_extend_it():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="Hilltop Trail"),
                     osm(2, line((300, 0), (600, 0)), highway="path", name="Hilltop Trail"),
                     osm(3, line((620, 0), (900, 0)), highway="path", name="Hilltop Trail"),  # 20 m gap
                     osm(4, line((0, 5000), (300, 5000)), highway="path", name="Hilltop Trail")],
                    parks=[PARK_A])
    trails = {t.id: t for t in build_trails(segs, [Relation(9, "Hilltop Trail", ("way/1", "way/2"))])}
    assert trails["trail-hilltop-trail-fort-ward-park"].segment_ids == ["seg-000001", "seg-000002", "seg-000003"]
    assert trails["trail-hilltop-trail"].segment_ids == ["seg-000004"]  # far away: separate trail


def test_overrides_merge_near_duplicate_trails():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="Gazzam Lake Main Trail"),
                     osm(2, line((300, 0), (600, 0)), highway="path", name="Gazzam Main Trail")], parks=[PARK_A])
    (t,) = build_trails(segs, overrides={"merge": {"trail-gazzam-lake-main-trail": ["trail-gazzam-main-trail"]}})
    assert (t.id, t.name, t.segment_ids) == ("trail-gazzam-lake-main-trail", "Gazzam Lake Main Trail",
                                             ["seg-000001", "seg-000002"])
    assert abs(t.computed_length_mi * MI - 600) < 0.5
    assert all(s.trail_ids == ["trail-gazzam-lake-main-trail"] for s in segs)


def test_trailheads_only_from_redistributable_endpoints():
    priv = Segment("seg-000009", line((0, 0), (300, 0)), [SourceRef(AG, "9", {})],
                   {"geometry_redistributable": False, "name": "Farm Trail", "agency:name": "Farm Trail",
                    "agency:sources": AG})
    segs = classify([priv,
                     osm(2, line((300, 0), (600, 0)), highway="path", name="Farm Trail"),
                     osm(3, line((-50, -1), (50, -1)), highway="footway", footway="sidewalk"),
                     osm(4, line((550, 1), (650, 1)), highway="footway", footway="sidewalk")])
    t = next(t for t in build_trails(segs) if t.id == "trail-farm-trail")
    assert len(t.trailheads) == 1  # only the OSM end at x=600; the agency-only end at x=0 is private
    assert abs(to_utm(t.trailheads[0]).x - (X0 + 600)) < 1


def test_merge_recomputes_manager_and_map_url_and_rejects_chains():
    a = {"agency:operator": "BIMPRD"}
    segs = classify([osm(1, line((0, 5000), (100, 5000)), highway="path", name="Lost Trail"),
                     osm(2, line((100, 5000), (900, 5000)), highway="path", name="Fort Ward Loop", **a)],
                    parks=[])
    trails = build_trails(segs, map_urls={"lost": "https://x/lost.pdf"},
                          overrides={"merge": {"trail-fort-ward-loop": ["trail-lost-trail"]}})
    (t,) = trails
    assert t.manager == "BIMPRD" and t.official_map_url == "https://x/lost.pdf"  # falls back to merged trail's
    segs2 = classify([osm(1, line((0, 5000), (100, 5000)), highway="path", name="Lost Trail"),
                      osm(2, line((100, 5000), (900, 5000)), highway="path", name="Other Trail", **a)])
    (t2,) = build_trails(segs2, map_urls={"other": "https://x/other.pdf"},
                         overrides={"merge": {"trail-lost-trail": ["trail-other-trail"]}})
    assert t2.manager == "BIMPRD" and t2.official_map_url == "https://x/other.pdf"  # target had no manager
    segs3 = classify([osm(n, line((0, 100 * n), (50, 100 * n)), highway="path", name=f"T{n} Trail")
                      for n in (1, 2, 3)])
    try:
        build_trails(segs3, overrides={"merge": {"trail-t1-trail": ["trail-t2-trail"],
                                                 "trail-t2-trail": ["trail-t3-trail"]}})
        raise AssertionError("chained merge accepted")
    except ValueError as e:
        assert "chain" in str(e)


def test_custom_group_duplicate_segment_id_not_double_counted():
    segs = classify([osm(1, line((0, 0), (300, 0)), highway="path", name="A Trail"),
                     osm(2, line((300, 0), (600, 0)), highway="path", name="A Trail")], parks=[PARK_A])
    ov = {"custom": [{"id": "trail-mine", "name": "Mine", "segment_ids": ["seg-000001", "seg-000002", "seg-000001"]}]}
    trails = {t.id: t for t in build_trails(segs, overrides=ov)}
    t = trails["trail-mine"]
    assert t.segment_ids == ["seg-000001", "seg-000002"]
    assert abs(t.computed_length_mi - sum(s.length_m for s in segs) / MI) < 1e-3
