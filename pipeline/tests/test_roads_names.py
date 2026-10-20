from bitrails.roads.names import (agency_name, base_key, canon_key, cobi_street_name, is_private_name,
                                  mismatch_kind, same_street)

COBI = "cobi_rd_streets"


def test_canon_key_abbreviates_and_keeps_order():
    assert canon_key("Northeast Dingley Road") == "ne dingley rd"
    assert canon_key("Dingley Rd NE") == "dingley rd ne"


def test_base_key_matches_the_same_street_across_sources():
    assert base_key("Northeast Dingley Road") == base_key("Dingley Rd NE") == "dingley rd"
    assert same_street("Euclid Avenue Northeast", "Euclid Ave")
    assert not same_street("Euclid Avenue Northeast", "Madison Avenue North")


def test_state_route_spellings_agree():
    assert same_street("State Highway 305", "State Route 305")


def test_cobi_name_assembles_four_columns_and_skips_the_00_placeholder():
    assert cobi_street_name({"PreDir": "NE", "Rd_Name": "New Brooklyn", "RD_Desig": "Rd",
                             "Suffix": " "}) == "NE New Brooklyn Rd"
    assert cobi_street_name({"PreDir": "00", "Rd_Name": "Ferncliff", "RD_Desig": "Avenue",
                             "Suffix": "NE"}) == "Ferncliff Avenue NE"


def test_pvt_is_a_private_marker_not_part_of_the_name():
    assert is_private_name("NE Warabi Ln PVT")
    assert not is_private_name("NE Warabi Ln")
    assert same_street("Northeast Warabi Lane", "NE Warabi Ln PVT")


def test_placeholder_names_are_not_names():
    # WSDOT writes the literal "PVT" in RoadName for private centerlines
    assert agency_name("wsdot_local_agency_public_road", {"RoadName": "PVT"}) is None
    assert agency_name("wsdot_local_agency_public_road", {"RoadName": "Saltair Dr NE"}) == "Saltair Dr NE"
    assert agency_name(COBI, {"Rd_Name": " ", "RD_Desig": " "}) is None


def test_mismatch_kinds():
    assert mismatch_kind("Northeast Dingley Road", "Dingley Rd NE") == "same"
    assert mismatch_kind("Northeast Eagle Harbor Drive", "NE Eagle Harbor Rd") == "type"
    assert mismatch_kind("Lovgreen Road Northeast", "Lovegren Rd NE") == "spelling"
    assert mismatch_kind("Northeast Wild Cherry Lane", "Foster St NE") == "different"
