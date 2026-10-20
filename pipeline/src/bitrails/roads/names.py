"""Street-name handling: agency name assembly, abbreviation-insensitive keys, display names.

OSM spells names out ("Northeast Dingley Road"); the City and WSDOT abbreviate and put the
direction elsewhere ("Dingley Rd NE"). Two keys bridge that:
  canon_key   abbreviation-normalised token sequence - "dingley rd ne" vs "ne dingley rd" differ
  base_key    canon_key with direction tokens dropped and the rest sorted, so both become
              "dingley rd" and the same street matches across sources
base_key is for cross-source comparison only. Grouping segments into roads uses canon_key plus
contiguity, so that North vs South halves of one name are not welded into a single road.
"""
from __future__ import annotations

import re

# spelled-out -> abbreviation (agency spellings are already the short form)
ABBR = {
    "avenue": "ave", "av": "ave", "road": "rd", "drive": "dr", "street": "st", "lane": "ln",
    "place": "pl", "court": "ct", "boulevard": "blvd", "circle": "cir", "loop": "lp",
    "terrace": "ter", "highway": "hwy", "route": "hwy", "sr": "hwy", "parkway": "pkwy",
    "crescent": "cres", "trail": "trl",
    "northeast": "ne", "northwest": "nw", "southeast": "se", "southwest": "sw",
    "north": "n", "south": "s", "east": "e", "west": "w",
    # agency shorthand that OSM spells out
    "pt": "point", "mt": "mount", "hts": "heights", "cntr": "center", "ctr": "center",
    "spgs": "springs", "spg": "spring", "hbr": "harbor", "bch": "beach", "crk": "creek",
}
DIRECTIONS = frozenset({"ne", "nw", "se", "sw", "n", "s", "e", "w"})
NULLS = frozenset({"", "00", "none", "null", "<null>", "n/a"})
# tokens that are markers, not part of the name: the City appends PVT to private roads
NOISE = frozenset({"pvt", "pvte", "private"})
STREET_TYPES = frozenset({"ave", "rd", "dr", "st", "ln", "pl", "ct", "blvd", "cir", "lp", "ter",
                          "hwy", "pkwy", "way", "trl", "walk", "loop", "run", "path", "cres"})
PRIVATE_MARKER = re.compile(r"\bpvte?\b", re.I)

# City of Bainbridge Island rd_Streets: name is assembled from four columns
COBI_NAME_FIELDS = ("PreDir", "Rd_Name", "RD_Desig", "Suffix")
# single-field names elsewhere (WSDOT LAPR / state routes, OSM)
NAME_FIELDS = ("name", "RoadName", "Street_Lab", "Road_Name", "STREET_FUL", "STREET", "NAME")


def clean(v) -> str | None:
    if v is None:
        return None
    s = str(v).strip()
    return None if s.casefold() in NULLS else s


def tokens(name: str | None) -> list[str]:
    if not name:
        return []
    s = re.sub(r"[^\w\s]", " ", name.casefold())
    out = [ABBR.get(t, t) for t in s.split() if t not in NULLS]
    return [t for t in out if t not in NOISE]


def canon_key(name: str | None) -> str:
    """Abbreviation-normalised token sequence (order kept)."""
    return " ".join(tokens(name))


def base_key(name: str | None) -> str:
    """Direction-free, order-free key for cross-source matching."""
    return " ".join(sorted(t for t in tokens(name) if t not in DIRECTIONS))


def same_street(a: str | None, b: str | None) -> bool:
    ka, kb = base_key(a), base_key(b)
    return bool(ka) and ka == kb


def is_private_name(name: str | None) -> bool:
    """The City marks private roads by appending PVT to the name ("NE Warabi Ln PVT")."""
    return bool(name and PRIVATE_MARKER.search(name))


def mismatch_kind(a: str | None, b: str | None) -> str:
    """How two spellings of one centerline's name differ: same | type | spelling | different."""
    if same_street(a, b):
        return "same"
    ta = [t for t in tokens(a) if t not in DIRECTIONS]
    tb = [t for t in tokens(b) if t not in DIRECTIONS]
    if sorted(t for t in ta if t not in STREET_TYPES) == sorted(t for t in tb if t not in STREET_TYPES):
        return "type"          # Eagle Harbor Drive vs Eagle Harbor Road
    sa, sb = set(ta), set(tb)
    if len(sa & sb) >= max(1, min(len(sa), len(sb)) - 1):
        return "spelling"      # Lovgreen vs Lovegren, Bay Hill vs Bayhill
    return "different"


def cobi_street_name(props: dict) -> str | None:
    """"NE New Brooklyn Rd" / "Ferncliff Ave NE" from rd_Streets' four name columns."""
    parts = [clean(props.get(f)) for f in COBI_NAME_FIELDS]
    joined = " ".join(p for p in parts if p)
    return joined or clean(props.get("Street_Lab"))


def agency_name(source_id: str, props: dict) -> str | None:
    """Best name a non-OSM road source offers for one of its features.

    A value that normalises to nothing is a placeholder, not a name: WSDOT's inventory writes the
    literal "PVT" in RoadName for private centerlines, and the City leaves blanks as " "."""
    if source_id.startswith("cobi_rd_streets"):
        name = cobi_street_name(props)
    elif (sr := clean(props.get("StateRouteNumber"))) and "RouteID" in props:
        name = f"State Route {sr}"
    else:
        name = next((c for f in NAME_FIELDS if (c := clean(props.get(f)))), None)
    return name if canon_key(name) else None
