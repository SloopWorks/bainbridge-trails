import pytest
from shapely.geometry import LineString

from bitrails.geo import to_wgs
from bitrails.roads.classify import classify
from bitrails.roads.overrides import apply_overrides, load_overrides
from bitrails.segments import Segment, SourceRef

OSM = "osm_overpass_roads"
X0, Y0 = 525_000.0, 5_275_000.0


def seg(n, name):
    tags = {"geometry_redistributable": True, "highway": "residential", "name": name}
    geom = to_wgs(LineString([(X0, Y0), (X0 + 200, Y0)]))
    return Segment(f"seg-{n:06d}", geom, [SourceRef(OSM, f"way/{n}", dict(tags))], tags)


def write(tmp_path, text):
    p = tmp_path / "roads_overrides.yaml"
    p.write_text(text)
    return p


def test_missing_file_is_no_overrides(tmp_path):
    assert len(load_overrides(tmp_path / "nope.yaml")) == 0


def test_street_entry_matches_any_spelling(tmp_path):
    ov = load_overrides(write(tmp_path, "streets:\n  NE Day Rd W:\n    ownership: public_city\n"))
    segs = classify([seg(1, "Northeast Day Road West")])
    assert apply_overrides(segs, ov) == {"segments": 0, "streets": 1, "dropped": 0}
    assert segs[0].ownership == "public_city"
    assert segs[0].ownership_source == "override:NE Day Rd W"


def test_segment_entry_wins_over_street_entry(tmp_path):
    ov = load_overrides(write(tmp_path, "segments:\n  seg-000002:\n    ownership: private\n"
                                        "streets:\n  Day Rd:\n    ownership: public_city\n"))
    segs = classify([seg(2, "Northeast Day Road")])
    apply_overrides(segs, ov)
    assert segs[0].ownership == "private"


def test_drop_keeps_the_segment_but_takes_it_out_of_the_goal(tmp_path):
    ov = load_overrides(write(tmp_path, "segments:\n  seg-000003:\n    drop: true\n    note: paper street\n"))
    segs = classify([seg(3, "Paper Street")])
    assert apply_overrides(segs, ov)["dropped"] == 1
    assert segs[0].dropped


def test_name_and_category_can_be_corrected(tmp_path):
    ov = load_overrides(write(tmp_path, "segments:\n  seg-000004:\n    name: Fixed Road\n"
                                        "    category: collector\n"))
    segs = classify([seg(4, "Wrong Road")])
    apply_overrides(segs, ov)
    assert (segs[0].name, segs[0].category) == ("Fixed Road", "collector")


@pytest.mark.parametrize("body,msg", [
    ("segments:\n  seg-000001:\n    owner: private\n", "unknown field"),
    ("segments:\n  seg-000001:\n    ownership: the_crown\n", "unknown ownership"),
    ("segments:\n  seg-000001:\n    category: freeway\n", "unknown category"),
    ("streets:\n  '  ':\n    ownership: private\n", "not a usable street name"),
])
def test_bad_entries_fail_loudly(tmp_path, body, msg):
    with pytest.raises(ValueError, match=msg):
        load_overrides(write(tmp_path, body))
