import pytest
from shapely.geometry import LineString

from bitrails.geo import to_utm, to_wgs
from bitrails.roads.split import MAX_M, TARGET_M, piece_count, split_long
from bitrails.segments import Segment, SourceRef

X0, Y0 = 525_000.0, 5_275_000.0


def line(*pts_m):
    return to_wgs(LineString([(X0 + x, Y0 + y) for x, y in pts_m]))


def seg(sid, geom, **tags):
    return Segment(sid, geom, [SourceRef("osm_overpass_roads", f"way/{sid}", dict(tags))], dict(tags))


@pytest.mark.parametrize("length,n", [(10, 1), (120, 1), (150, 1), (151, 2), (240, 2), (290, 2), (300, 3),
                                      (1108, 9), (1674, 14)])
def test_piece_count_targets_120_never_exceeds_150(length, n):
    assert piece_count(length) == n
    assert length / n <= MAX_M


def test_short_segment_is_untouched():
    s = seg("p:1", line((0, 0), (100, 0)), highway="residential")
    assert split_long([s]) == [s]


def test_long_segment_splits_into_equal_pieces_that_chain_exactly():
    g = line((0, 0), (300, 0), (300, 300))           # 600 m with a bend
    s = seg("p:7", g, highway="residential", name="Koura Rd NE")
    pieces = split_long([s])
    assert len(pieces) == 5
    lengths = [to_utm(p.geometry).length for p in pieces]
    assert all(abs(L - 120.0) < 0.01 for L in lengths)
    assert abs(sum(lengths) - 600.0) < 0.01
    # original endpoints kept bit-for-bit (junctions with unsplit neighbours stay shared)
    assert pieces[0].geometry.coords[0] == g.coords[0]
    assert pieces[-1].geometry.coords[-1] == g.coords[-1]
    for a, b in zip(pieces, pieces[1:]):
        assert a.geometry.coords[-1] == b.geometry.coords[0]
    # the bend vertex survives inside the piece that spans it
    assert any(len(p.geometry.coords) == 3 for p in pieces)
    assert [p.id for p in pieces] == [f"p:7/{k}" for k in range(1, 6)]
    assert all(p.tags == s.tags and p.source_refs == s.source_refs for p in pieces)


def test_split_is_direction_independent():
    a = split_long([seg("a", line((0, 0), (500, 0)))])
    b = split_long([seg("b", line((500, 0), (0, 0)))])
    key = lambda segs: sorted(tuple(sorted((round(x, 9), round(y, 9)) for x, y in (p.geometry.coords[0], p.geometry.coords[-1])))
                              for p in segs)
    assert key(a) == key(b)


def test_target_constant():
    assert TARGET_M == 120.0 and MAX_M == 150.0
