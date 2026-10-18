import random

from shapely.geometry import LineString, Polygon

from bitrails.fetch import Source
from bitrails.geo import to_utm, to_wgs
from bitrails.reconcile import Conflict, reconcile, write_conflict_report
from bitrails.segments import Segment, SourceRef

OSM = "osm_overpass_trails"
AG = "bimprd_trails_agol_2022"
LEG = "bimprd_trails_2015_legacy"
X0, Y0 = 525_000.0, 5_275_000.0  # UTM 10N, on Bainbridge

SOURCES = {
    OSM: Source(id=OSM, name="osm", kind="overpass", endpoint=None, redistribution="yes", geometry_rank=1),
    AG: Source(id=AG, name="ag", kind="arcgis_rest", endpoint=None, redistribution="unknown", geometry_rank=3),
    LEG: Source(id=LEG, name="leg", kind="arcgis_rest", endpoint=None, redistribution="unknown", geometry_rank=5),
}


def line(*pts_m):
    """WGS84 LineString from metre offsets relative to (X0, Y0)."""
    return to_wgs(LineString([(X0 + x, Y0 + y) for x, y in pts_m]))


def seg(source, n, geom, **tags):
    return Segment(f"{source}:{n}", geom, [SourceRef(source, f"f{n}", dict(tags))], dict(tags))


def total_m(segs):
    return sum(to_utm(s.geometry).length for s in segs)


def kinds(result):
    return [c.kind for c in result.conflicts]


def test_duplicate_across_sources_merges_once():
    osm = [seg(OSM, 1, line((0, 0), (500, 0)), highway="path", name="Gazzam Lake Trail"),
           seg(OSM, 2, line((500, 0), (1000, 0)), highway="path", name="Gazzam Lake Trail")]
    # same trail from BIMPRD, offset 3 m, segmented differently (one long feature)
    agency = [seg(AG, 1, line((0, 3), (1000, 3)), TRAILNAME="Gazzam Lake Trail", PARK="Gazzam Lake")]
    alone = reconcile({OSM: osm}, SOURCES)
    both = reconcile({OSM: osm, AG: agency}, SOURCES)
    assert abs(total_m(both.segments) - total_m(alone.segments)) < 1e-6
    assert [s.id for s in both.segments] == [f"{OSM}:1", f"{OSM}:2"]
    for s in both.segments:
        assert s.tags["geometry_redistributable"] is True
        assert s.tags["name"] == "Gazzam Lake Trail"
        assert s.tags["agency:park"] == "Gazzam Lake"
        assert {r.source_id for r in s.source_refs} == {OSM, AG}
        assert s.geometry.equals(next(o for o in osm if o.id == s.id).geometry)  # geometry from OSM
    assert "missing_in_osm" not in kinds(both)


def test_geometry_from_highest_rank_regardless_of_input_order():
    osm = [seg(OSM, 1, line((0, 0), (300, 0)), highway="path")]
    ag = [seg(AG, 1, line((0, 4), (300, 4)), TRAILNAME="X")]
    leg = [seg(LEG, 1, line((0, -4), (300, -4)), TRAIL_NAME="Y")]
    r = reconcile({LEG: leg, AG: ag, OSM: osm}, SOURCES)
    assert [s.id for s in r.segments] == [f"{OSM}:1"]
    assert r.segments[0].tags["name"] == "X"  # better-ranked agency name wins


def test_name_mismatch_reported():
    osm = [seg(OSM, 1, line((0, 0), (400, 0)), highway="path", name="Gazzam Lake Trail"),
           seg(OSM, 2, line((0, 100), (400, 100)), highway="path", name="Spring Trail."),
           seg(OSM, 3, line((0, 200), (400, 200)), highway="path")]
    agency = [seg(AG, 1, line((0, 2), (400, 2)), TRAILNAME="Gazzam Loop"),
              seg(AG, 2, line((0, 102), (400, 102)), TRAILNAME="SPRING"),  # same after normalization
              seg(AG, 3, line((0, 202), (400, 202)), TRAILNAME="Ravine")]  # OSM unnamed: no mismatch
    r = reconcile({OSM: osm, AG: agency}, SOURCES)
    nm = [c for c in r.conflicts if c.kind == "name_mismatch"]
    assert len(nm) == 1
    assert nm[0].segment_ids == (f"{OSM}:1",)
    assert "Gazzam Lake Trail" in nm[0].detail and "Gazzam Loop" in nm[0].detail
    s1 = next(s for s in r.segments if s.id == f"{OSM}:1")
    assert s1.tags["name"] == "Gazzam Loop" and s1.tags["osm:name"] == "Gazzam Lake Trail"


def test_agency_only_trail_kept_and_flagged_missing_in_osm():
    osm = [seg(OSM, 1, line((0, 0), (500, 0)), highway="path")]
    agency = [seg(AG, 1, line((0, 300), (400, 300)), TRAILNAME="Hidden Spur")]
    r = reconcile({OSM: osm, AG: agency}, SOURCES)
    assert len(r.segments) == 2
    only = next(s for s in r.segments if s.source_refs[0].source_id == AG)
    assert only.tags["geometry_redistributable"] is False
    assert only.tags["name"] == "Hidden Spur"
    assert abs(to_utm(only.geometry).length - 400) < 1
    miss = [c for c in r.conflicts if c.kind == "missing_in_osm"]
    assert [c.segment_ids for c in miss] == [(only.id,)]
    osm_seg = next(s for s in r.segments if s.id == f"{OSM}:1")
    assert osm_seg.tags["geometry_redistributable"] is True


def test_partial_overlap_keeps_uncovered_remainder_and_length_mismatch():
    osm = [seg(OSM, 1, line((0, 0), (600, 0)), highway="path")]
    agency = [seg(AG, 1, line((0, 2), (1000, 2)), TRAILNAME="Long Trail")]  # 40% not in OSM
    r = reconcile({OSM: osm, AG: agency}, SOURCES)
    rem = [s for s in r.segments if s.source_refs[0].source_id == AG]
    assert len(rem) == 1
    assert 380 < to_utm(rem[0].geometry).length < 400  # 1000 - 600 - 10 m buffer
    # island total: no doubled geometry
    assert abs(total_m(r.segments) - 600 - to_utm(rem[0].geometry).length) < 1e-6
    assert "missing_in_osm" in kinds(r)
    lm = [c for c in r.conflicts if c.kind == "length_mismatch"]
    assert len(lm) == 1 and "Long Trail" in lm[0].detail


def test_mostly_covered_agency_segment_counts_as_matched():
    osm = [seg(OSM, 1, line((0, 0), (800, 0)), highway="path")]
    agency = [seg(AG, 1, line((0, 2), (1000, 2)), TRAILNAME="Mostly")]  # 80% covered
    r = reconcile({OSM: osm, AG: agency}, SOURCES)
    assert [s.id for s in r.segments] == [f"{OSM}:1"]
    assert "missing_in_osm" not in kinds(r)


def test_geometry_offset_reported_for_matched_pair():
    osm = [seg(OSM, 1, line((0, 0), (1000, 0)), highway="path")]
    # 5 m offset except a 20 m bump over 100 m (<30% of length): still matched, Hausdorff ~20 m
    agency = [seg(AG, 1, line((0, 5), (450, 5), (500, 20), (550, 5), (1000, 5)), TRAILNAME="Bumpy")]
    near = [seg(AG, 2, line((0, 1005), (1000, 1005)), TRAILNAME="Straight")]
    osm.append(seg(OSM, 2, line((0, 1000), (1000, 1000)), highway="path"))
    r = reconcile({OSM: osm, AG: agency + near}, SOURCES)
    off = [c for c in r.conflicts if c.kind == "geometry_offset"]
    assert [c.segment_ids for c in off] == [(f"{OSM}:1",)]


def test_missing_in_agency_inside_park():
    park = to_wgs(Polygon([(X0 - 50, Y0 - 50), (X0 + 1050, Y0 - 50), (X0 + 1050, Y0 + 450), (X0 - 50, Y0 + 450)]))
    osm = [seg(OSM, 1, line((0, 0), (500, 0)), highway="path"),
           seg(OSM, 2, line((0, 200), (500, 200)), highway="footway", footway="sidewalk"),
           seg(OSM, 3, line((0, 400), (500, 400)), highway="track"),
           seg(OSM, 4, line((0, 2000), (500, 2000)), highway="path")]  # outside park
    agency = [seg(AG, 1, line((0, 2), (500, 2)), TRAILNAME="Known")]
    r = reconcile({OSM: osm, AG: agency}, SOURCES, parks=[("Test Park", park)])
    mia = [c for c in r.conflicts if c.kind == "missing_in_agency"]
    assert len(mia) == 1
    assert mia[0].segment_ids == (f"{OSM}:3",)
    assert "Test Park" in mia[0].detail


def test_deterministic_output_order():
    osm = [seg(OSM, n, line((0, 100 * n), (400, 100 * n)), highway="path", name=f"T{n}") for n in range(1, 9)]
    agency = [seg(AG, n, line((0, 100 * n + 3), (400, 100 * n + 3)), TRAILNAME=f"Other {n}") for n in range(1, 9)]
    agency.append(seg(AG, 99, line((5000, 0), (5400, 0)), TRAILNAME="Far"))
    base = reconcile({OSM: osm, AG: agency}, SOURCES)
    rng = random.Random(4)
    for _ in range(3):
        o, a = osm[:], agency[:]
        rng.shuffle(o)
        rng.shuffle(a)
        r = reconcile({AG: a, OSM: o}, SOURCES)
        assert [s.id for s in r.segments] == [s.id for s in base.segments]
        assert [s.geometry.wkb for s in r.segments] == [s.geometry.wkb for s in base.segments]
        assert r.conflicts == base.conflicts
    assert base.conflicts == sorted(base.conflicts, key=lambda c: (c.kind, c.segment_ids, c.detail))
    assert all(isinstance(c, Conflict) for c in base.conflicts)


def test_write_conflict_report(tmp_path):
    osm = [seg(OSM, 1, line((0, 0), (400, 0)), highway="path", name="A Trail")]
    agency = [seg(AG, 1, line((0, 2), (400, 2)), TRAILNAME="B"),
              seg(AG, 2, line((0, 900), (400, 900)), TRAILNAME="Far")]
    r = reconcile({OSM: osm, AG: agency}, SOURCES)
    out = tmp_path / "reports" / "conflicts.md"
    write_conflict_report(r, out)
    text = out.read_text()
    assert text.startswith("# ")
    assert "| name_mismatch | 1 |" in text and "| missing_in_osm | 1 |" in text
    assert "## name_mismatch" in text and f"`{OSM}:1`" in text


def test_length_mismatch_per_named_trail_only_and_dedupes_agency_duplicates():
    osm = [seg(OSM, 1, line((0, 0), (500, 0)), highway="path"),
           seg(OSM, 2, line((0, 300), (500, 300)), highway="path")]
    agency = [seg(AG, 1, line((0, 2), (480, 2)), TRAILNAME="Dup"),
              seg(AG, 2, line((0, 2), (480, 2)), TRAILNAME="Dup"),   # duplicate feature in agency data
              seg(AG, 3, line((0, 302), (300, 302)))]                  # unnamed: no length_mismatch
    r = reconcile({OSM: osm, AG: agency}, SOURCES)
    assert "length_mismatch" not in kinds(r)  # 480 vs 500 = 4%, and unnamed one skipped


def test_name_mismatch_only_against_geometry_source_names():
    osm = [seg(OSM, 1, line((0, 0), (500, 0)), highway="path")]
    ag = [seg(AG, 1, line((0, 300), (500, 300)), TRAILNAME="Alpha")]   # agency-only canonical
    leg = [seg(LEG, 1, line((0, 303), (500, 303)), TRAIL_NAME="Beta")]
    r = reconcile({OSM: osm, AG: ag, LEG: leg}, SOURCES)
    assert "name_mismatch" not in kinds(r)
    assert sum(k == "missing_in_osm" for k in kinds(r)) == 1


def test_crossing_agency_stub_does_not_rename_osm_segment():  # review b1
    osm = [seg(OSM, 1, line((0, 0), (500, 0)), highway="path", name="Y Trail")]
    ag = [seg(AG, 1, line((250, -12.5), (250, 12.5)), TRAILNAME="X Trail")]  # 25 m perpendicular stub
    leg = [seg(LEG, 1, line((0, 0), (500, 0)), TRAIL_NAME="Y Trail")]
    r = reconcile({OSM: osm, AG: ag, LEG: leg}, SOURCES)
    s1 = next(s for s in r.segments if s.id == f"{OSM}:1")
    assert s1.tags["name"] == "Y Trail" and s1.tags["agency:name"] == "Y Trail"
    assert AG not in {ref.source_id for ref in s1.source_refs}
    assert not [c for c in r.conflicts if c.kind in ("name_mismatch", "length_mismatch", "geometry_offset")]


def test_crossing_osm_connector_not_renamed_by_agency_trail():  # review b2
    osm = [seg(OSM, 1, line((250, -12.5), (250, 12.5)), highway="footway", name="Connector"),
           seg(OSM, 2, line((0, 0), (500, 0)), highway="path")]
    ag = [seg(AG, 1, line((0, 0), (500, 0)), TRAILNAME="Big Trail")]
    park = to_wgs(Polygon([(X0 - 50, Y0 - 50), (X0 + 550, Y0 - 50), (X0 + 550, Y0 + 50), (X0 - 50, Y0 + 50)]))
    r = reconcile({OSM: osm, AG: ag}, SOURCES, parks=[("P", park)])
    by = {s.id: s for s in r.segments}
    assert by[f"{OSM}:1"].tags["name"] == "Connector" and "agency:name" not in by[f"{OSM}:1"].tags
    assert by[f"{OSM}:2"].tags["name"] == "Big Trail"
    assert "name_mismatch" not in kinds(r) and "length_mismatch" not in kinds(r)
    mia = [c for c in r.conflicts if c.kind == "missing_in_agency"]
    assert [c.segment_ids for c in mia] == [(f"{OSM}:1",)]  # connector has no real agency match


def test_alongside_partial_overlap_trims_agency_remainder():
    osm = [seg(OSM, 1, line((0, 0), (400, 0)), highway="path"),
           # runs 100 m along the agency trail then turns away: < 70% of shorter, but alongside
           seg(OSM, 2, line((600, 0), (700, 0), (700, 200)), highway="path")]
    agency = [seg(AG, 1, line((0, 2), (1000, 2)), TRAILNAME="Long")]
    r = reconcile({OSM: osm, AG: agency}, SOURCES)
    rem = [s for s in r.segments if s.source_refs[0].source_id == AG]
    assert len(rem) == 2  # 410..590 and 710..1000; 600..700 not duplicated
    assert 465 < total_m(rem) < 475


def test_cli_reconcile_writes_no_report_unless_asked(tmp_path, capsys):
    from pathlib import Path
    from bitrails import cli
    root = Path(__file__).resolve().parents[2]
    committed = root / "data" / "reports" / "conflicts.md"
    before = committed.read_bytes()
    assert cli.main(["reconcile"]) == 0
    assert committed.read_bytes() == before and "report:" not in capsys.readouterr().out
    out = tmp_path / "c.md"
    assert cli.main(["reconcile", "--report", str(out)]) == 0
    assert out.read_text().startswith("# Reconcile conflict report")
