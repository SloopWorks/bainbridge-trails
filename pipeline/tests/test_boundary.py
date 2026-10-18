from pathlib import Path

from shapely.geometry import Point

from bitrails.boundary import island_polygon

FIX = Path(__file__).parent / "fixtures" / "osm_island_boundary.json"


def test_island_polygon_contains_known_points():
    poly = island_polygon(FIX)
    assert poly.contains(Point(-122.546, 47.649))  # Grand Forest
    assert not poly.contains(Point(-122.332, 47.606))  # Seattle
    assert not poly.contains(Point(-122.5720, 47.7130))  # Agate Pass bridge midpoint


def test_island_polygon_is_valid_and_nonempty():
    poly = island_polygon(FIX)
    assert poly.is_valid and poly.area > 0
