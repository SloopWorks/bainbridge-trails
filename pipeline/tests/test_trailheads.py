from bitrails.trailheads import trailheads_doc

PAT = r"\b(road|rd)\.?$"
LAT = 47.65


def lon(m):  # metres east of -122.5
    return -122.5 + m / (111320 * 0.6743)


def seg(i, a, b, category="agency_trail", name=None, red=True):
    return {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[lon(a), LAT], [lon(b), LAT]]},
            "properties": {"id": i, "category": category, "name": name, "geometry_redistributable": red}}


def trail(i, name, park, segs, heads=()):
    return {"id": i, "name": name, "park": park, "segment_ids": segs,
            "trailheads": [{"type": "Point", "coordinates": [lon(x), LAT]} for x in heads]}


def doc(*trails):
    return {"trails": list(trails)}


def run(trails, feats, st):
    return trailheads_doc(doc(*trails), st, feats, PAT, "v1")["trailheads"]


def test_agencyTrailheadKept():
    r = run([trail("t1", "A", "Park", ["s1"], heads=[0])], [seg("s1", 0, 500)], {"s1": ["t1"]})
    assert len(r) == 1 and r[0]["source"] == "agency" and r[0]["trail_ids"] == ["t1"] and r[0]["name"] == "Park"
    assert r[0]["id"].startswith("th-") and len(r[0]["id"]) == 9


def test_roadAccessEndpointDetected():
    feats = [seg("s1", 0, 500), seg("r1", -10, -300, "connector", "Foo Road"),
             seg("c1", 500, 900, "connector", "Cut Through")]
    r = run([trail("t1", "A", None, ["s1"])], feats, {"s1": ["t1"]})
    assert [t["source"] for t in r] == ["road_access"] and r[0]["trail_ids"] == ["t1"]
    # non-road-named connector and far road endpoint -> nothing at the 500 end
    assert len(r) == 1
    # trail-less multi_use_path also counts
    feats = [seg("s1", 0, 500), seg("m1", 510, 900, "multi_use_path")]
    r = run([trail("t1", "A", None, ["s1"])], feats, {"s1": ["t1"]})
    assert len(r) == 1 and r[0]["source"] == "road_access"


def test_clusteringMerges():
    t = [trail("t1", "A", None, ["s1"], heads=[0]), trail("t2", "B", None, ["s2"])]
    feats = [seg("s1", 0, 500), seg("s2", 60, 700), seg("r1", 55, 55 - 200, "connector", "Foo Rd")]
    r = run(t, feats, {"s1": ["t1"], "s2": ["t2"]})
    near = [x for x in r if abs(x["lon"] - lon(0)) < 1e-4]
    assert len(near) == 1 and near[0]["source"] == "agency" and near[0]["trail_ids"] == ["t1", "t2"]
    assert near[0]["lon"] == lon(0)


def test_nameFallbacks():
    mk = lambda park, n1, n2: run([trail("t1", n1, park, ["s1"], heads=[0]), trail("t2", n2, park, ["s2"], heads=[10])],
                                  [seg("s1", 0, 500), seg("s2", 10, 600)], {"s1": ["t1"], "s2": ["t2"]})[0]["name"]
    assert mk("Park", "A", "B") == "Park"
    assert mk(None, "A", "B") == "A"
    assert mk(None, "", "") == "Trail access"


def test_onlyDistTrails():
    feats = [seg("s1", 0, 500, red=False), seg("s2", 0, 400), seg("r1", -5, -300, "connector", "Foo Road", red=False)]
    # s1 not redistributable; r1 not redistributable; s2 belongs to no dist trail
    assert run([trail("t1", "A", None, ["s1"])], feats, {"s1": ["t1"]}) == []
