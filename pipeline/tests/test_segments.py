import json

from shapely.geometry import LineString, box

from bitrails.fetch import Source
from bitrails.geo import to_utm
from bitrails.segments import normalize_source, split_at_junctions

ISLAND = box(-122.6, 47.5, -122.4, 47.7)


def src(kind="arcgis_rest"):
    return Source(id="s", name="s", kind=kind, endpoint=None)


def test_split_at_junctions_t_intersection():
    stem = LineString([(-122.5, 47.600), (-122.5, 47.601)])
    bar = LineString([(-122.501, 47.601), (-122.5, 47.601), (-122.499, 47.601)])
    # stem top end touches bar's middle vertex; bar must be split there
    out = split_at_junctions([stem, bar])
    assert len(out) == 3


def test_split_at_junctions_crossing_without_shared_vertex():
    a = LineString([(-122.501, 47.600), (-122.499, 47.600)])
    b = LineString([(-122.500, 47.5995), (-122.500, 47.6005)])
    assert len(split_at_junctions([a, b])) == 4


def test_loop_way_yields_valid_segment(tmp_path):
    ring = [(-122.5, 47.6), (-122.499, 47.6), (-122.499, 47.601), (-122.5, 47.601), (-122.5, 47.6)]
    p = tmp_path / "o.json"
    p.write_text(json.dumps({"elements": [{"type": "way", "id": 7,
        "geometry": [{"lon": x, "lat": y} for x, y in ring], "tags": {"highway": "path"}}]}))
    segs = normalize_source(src("overpass"), p, ISLAND)
    assert len(segs) == 1
    g = segs[0].geometry
    assert g.is_valid and g.is_ring and to_utm(g).length > 100
    assert segs[0].id == "s:1"
    assert segs[0].source_refs[0].feature_id == "way/7"


def test_clip_to_island_drops_offisland_part(tmp_path):
    feats = [
        {"type": "Feature", "id": 1, "properties": {"OBJECTID": 10},
         "geometry": {"type": "MultiLineString", "coordinates": [
             [[-122.5, 47.6], [-122.49, 47.6]],            # inside
             [[-122.3, 47.6], [-122.29, 47.6]]]}},         # off island
        {"type": "Feature", "id": 2, "properties": {"OBJECTID": 2},
         "geometry": {"type": "LineString", "coordinates": [[-122.41, 47.65], [-122.39, 47.65]]}},  # crosses east edge
        {"type": "Feature", "id": 3, "properties": {"OBJECTID": 3}, "geometry": None},
    ]
    p = tmp_path / "a.geojson"
    p.write_text(json.dumps({"type": "FeatureCollection", "features": feats}))
    segs = normalize_source(src(), p, ISLAND)
    assert len(segs) == 2
    # sorted by feature id: OBJECTID 2 first
    assert [s.source_refs[0].feature_id for s in segs] == ["2", "10"]
    assert all(ISLAND.buffer(1e-9).contains(s.geometry) for s in segs)
    assert segs[0].geometry.bounds[2] <= -122.4 + 1e-9


def test_deterministic(tmp_path):
    feats = [{"type": "Feature", "properties": {"OBJECTID": i},
              "geometry": {"type": "LineString", "coordinates": [[-122.5, 47.6 + i * 0.001], [-122.49, 47.6 + i * 0.001]]}}
             for i in (3, 1, 2)]
    p = tmp_path / "a.geojson"
    p.write_text(json.dumps({"features": feats}))
    a = normalize_source(src(), p, ISLAND)
    b = normalize_source(src(), p, ISLAND)
    assert [(s.id, s.geometry.wkt) for s in a] == [(s.id, s.geometry.wkt) for s in b]


def test_missing_feature_id_falls_back_to_index(tmp_path):
    feats = [{"type": "Feature", "properties": {},
              "geometry": {"type": "LineString", "coordinates": [[-122.5, 47.6 + i * 0.001], [-122.49, 47.6 + i * 0.001]]}}
             for i in range(2)]
    p = tmp_path / "a.geojson"
    p.write_text(json.dumps({"features": feats}))
    segs = normalize_source(src(), p, ISLAND)
    assert [s.source_refs[0].feature_id for s in segs] == ["idx0", "idx1"]
