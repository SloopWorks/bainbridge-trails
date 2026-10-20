import json
from shapely.geometry import LineString
import pytest

from bitrails.registry import Registry
from bitrails.segments import Segment, assign_ids

A = LineString([(-122.500, 47.600), (-122.499, 47.600)])          # ~75 m
B = LineString([(-122.500, 47.610), (-122.499, 47.610)])
C = LineString([(-122.500, 47.620), (-122.499, 47.620)])


def test_same_geometry_same_id_across_runs(tmp_path):
    p = tmp_path / "segments.json"
    r = Registry.load(p)
    ids = r.assign_many([A, B])
    r.finalize_run()
    r.save()
    r2 = Registry.load(p)
    assert r2.assign_many([B, A]) == [ids[1], ids[0]]
    r2.finalize_run()
    assert ids == ["seg-000001", "seg-000002"]


def test_reversed_line_same_id(tmp_path):
    r = Registry.load(tmp_path / "s.json")
    a = r.assign(A)
    r.finalize_run()
    r.save()
    r2 = Registry.load(tmp_path / "s.json")
    assert r2.assign(LineString(list(A.coords)[::-1])) == a


def test_small_shift_matches_by_hausdorff(tmp_path):
    r = Registry.load(tmp_path / "s.json")
    a = r.assign(A)
    r.finalize_run()
    r.save()
    r = Registry.load(tmp_path / "s.json")
    shifted = LineString([(-122.500, 47.60003), (-122.499, 47.60003)])  # ~3 m
    assert r.assign(shifted) == a


def test_new_geometry_gets_next_id_never_reused(tmp_path):
    p = tmp_path / "s.json"
    r = Registry.load(p)
    r.assign_many([A, B, C])
    r.finalize_run()
    r.save()
    r = Registry.load(p)
    r.assign_many([A, C])           # B unseen -> retired by finalize
    r.finalize_run()
    r.save()
    assert r.entries["seg-000002"]["retired"] is True
    r = Registry.load(p)
    new = r.assign(LineString([(-122.500, 47.630), (-122.499, 47.630)]))
    assert new == "seg-000004"


def test_explicit_retire_then_new_id(tmp_path):
    r = Registry.load(tmp_path / "s.json")
    r.assign_many([A, B, C])
    r.retire("seg-000002")
    assert r.assign(LineString([(-122.5, 47.63), (-122.499, 47.63)])) == "seg-000004"


def test_changed_geometry_records_supersedes(tmp_path):
    p = tmp_path / "s.json"
    r = Registry.load(p)
    old = r.assign(A)
    r.finalize_run()
    r.save()
    r = Registry.load(p)
    # first 60% of A: too short to match, but overlaps the old segment
    part = LineString([(-122.500, 47.600), (-122.49946, 47.600)])
    new = r.assign(part)
    r.finalize_run()
    assert new != old
    assert r.entries[old]["retired"] is True
    assert r.entries[new]["supersedes"] == [old]


def test_split_pieces_each_supersede_the_long_segment(tmp_path):
    p = tmp_path / "s.json"
    r = Registry.load(p)
    long_ = LineString([(-122.500, 47.600), (-122.496, 47.600)])     # ~300 m
    old = r.assign(long_)
    r.finalize_run()
    r.save()
    r = Registry.load(p)
    # four quarter pieces: each covers only 25% of the old line, but lies wholly on it
    xs = [-122.500, -122.499, -122.498, -122.497, -122.496]
    new = r.assign_many([LineString([(a, 47.600), (b, 47.600)]) for a, b in zip(xs, xs[1:])])
    r.finalize_run()
    assert old not in new and r.entries[old]["retired"] is True
    assert [r.entries[n]["supersedes"] for n in new] == [[old]] * 4


def test_save_is_deterministic(tmp_path):
    for name in ("x.json", "y.json"):
        r = Registry.load(tmp_path / name)
        r.assign_many([C, A, B] if name == "x.json" else [B, C, A])
        r.finalize_run()
        r.save()
    assert (tmp_path / "x.json").read_bytes() == (tmp_path / "y.json").read_bytes()


def test_assign_ids_replaces_provisional_ids(tmp_path):
    r = Registry.load(tmp_path / "s.json")
    segs = [Segment("s:1", A), Segment("s:2", B)]
    out = assign_ids(segs, r)
    assert [s.id for s in out] == ["seg-000001", "seg-000002"]
    assert [s.geometry for s in out] == [A, B]


def test_finalize_twice_or_assign_after_finalize_raises(tmp_path):
    r = Registry.load(tmp_path / "s.json")
    r.assign(A)
    r.finalize_run()
    with pytest.raises(RuntimeError):
        r.finalize_run()
    with pytest.raises(RuntimeError):
        r.assign(B)
    with pytest.raises(RuntimeError):
        assign_ids([Segment("s:1", B)], r)


def test_private_geometry_kept_out_of_registry_file(tmp_path):
    p = tmp_path / "registry" / "segments.json"
    r = Registry.load(p)
    pub, priv = r.assign_many([A, B], private=[False, True])
    r.finalize_run()
    r.save()
    data = json.loads(p.read_text())
    e = {x["id"]: x for x in data["segments"]}
    assert e[priv]["private"] is True and "geometry" not in e[priv]
    assert e[pub]["geometry"].startswith("LINESTRING") and not e[pub].get("private")
    side = json.loads((tmp_path / "registry" / "private" / "geometry.json").read_text())
    assert set(side) == {priv}
    # with sidecar: fuzzy (shifted) match still works for the private entry
    shifted = LineString([(x + 0.00002, y) for x, y in B.coords])
    r = Registry.load(p)
    assert r.assign_many([A, shifted], private=[False, True]) == [pub, priv]


def test_private_entry_matches_by_fingerprint_without_sidecar(tmp_path):
    p = tmp_path / "registry" / "segments.json"
    r = Registry.load(p)
    pub, priv = r.assign_many([A, B], private=[False, True])
    r.finalize_run()
    r.save()
    (tmp_path / "registry" / "private" / "geometry.json").unlink()  # fresh clone
    r = Registry.load(p)
    assert r.assign_many([A, B], private=[False, True]) == [pub, priv]  # exact fingerprint
    r.finalize_run()
    r.save()
    assert set(json.loads((tmp_path / "registry" / "private" / "geometry.json").read_text())) == {priv}


def test_assign_ids_marks_non_redistributable_private(tmp_path):
    p = tmp_path / "s.json"
    r = Registry.load(p)
    segs = [Segment("x:1", A, tags={"geometry_redistributable": True}),
            Segment("x:2", B, tags={"geometry_redistributable": False})]
    out = assign_ids(segs, r)
    r.save()
    e = {x["id"]: x for x in json.loads(p.read_text())["segments"]}
    assert "geometry" in e[out[0].id] and "geometry" not in e[out[1].id]


def test_private_entry_has_no_coordinate_like_text(tmp_path):
    import re
    p = tmp_path / "registry" / "segments.json"
    r = Registry.load(p)
    _, priv = r.assign_many([A, B], private=[False, True])
    r.finalize_run()
    r.save()
    e = next(x for x in json.loads(p.read_text())["segments"] if x["id"] == priv)
    assert "fingerprint" not in e and re.fullmatch(r"[0-9a-f]{64}", e["fingerprint_sha256"])
    assert not re.search(r"-?\d+\.\d{3,}", json.dumps(e))
    (tmp_path / "registry" / "private" / "geometry.json").unlink()
    assert Registry.load(p).assign_many([B], private=[True]) == [priv]  # hashed exact match


def test_legacy_private_entry_with_plain_fingerprint_migrates(tmp_path):
    p = tmp_path / "registry" / "segments.json"
    r = Registry.load(p)
    _, priv = r.assign_many([A, B], private=[False, True])
    r.finalize_run()
    r.save()
    data = json.loads(p.read_text())
    e = next(x for x in data["segments"] if x["id"] == priv)
    from bitrails.registry import fingerprint
    e["fingerprint"] = fingerprint(B)
    del e["fingerprint_sha256"]
    p.write_text(json.dumps(data))
    r = Registry.load(p)
    assert r.assign_many([A, B], private=[False, True])[1] == priv
    r.finalize_run()
    r.save()
    e = next(x for x in json.loads(p.read_text())["segments"] if x["id"] == priv)
    assert "fingerprint" not in e and "fingerprint_sha256" in e
