"""Regression checks for the island-only styles; no tile data or GIS dependencies needed."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("map_styles", ROOT / "scripts/build-map-styles.py")
styles = importlib.util.module_from_spec(spec)
spec.loader.exec_module(styles)


def inside_ring(point, ring):
    x, y = point
    inside = False
    for a, b in zip(ring, ring[1:]):
        if (a[1] > y) != (b[1] > y) and x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]:
            inside = not inside
    return inside


class IslandStylesTest(unittest.TestCase):
    def test_detailed_outline_preserves_coast_at_street_zoom(self):
        # Points a few metres inland from max-zoom earth. The hero's 20m simplification clips them out.
        ring = styles.ISLAND["coordinates"][0]
        for p in ((-122.5534878, 47.7208853), (-122.5601211, 47.6735820), (-122.5049561, 47.5931172)):
            self.assertTrue(inside_ring(p, ring), p)

    def test_all_styles_hide_nearby_land_with_an_opaque_exterior(self):
        for palette in (styles.TOPO, styles.MINIMAL_LIGHT, styles.DARK):
            with self.subTest(style=palette["name"]):
                style = styles.build(palette)
                layers = style["layers"]
                mask = next(l for l in layers if l["id"] == "island-exterior")
                self.assertEqual(palette["water"], mask["paint"]["fill-color"])
                self.assertEqual(1, mask["paint"]["fill-opacity"])
                geometry = style["sources"][mask["source"]]["data"]
                self.assertEqual("Polygon", geometry["type"])
                outer, hole = geometry["coordinates"]
                def area2(ring):
                    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:]))
                self.assertGreater(area2(outer), 0)
                self.assertLess(area2(hole), 0)
                def masked(p):
                    return inside_ring(p, outer) and not inside_ring(p, hole)
                for point in ((-122.61, 47.65), (-122.57, 47.73), (-122.50, 47.55), (-122.33, 47.60)):
                    self.assertTrue(masked(point), point)  # Kitsap, Suquamish, Blake Island, Seattle
                for point in ((-122.52, 47.625), (-122.55, 47.65), (-122.515, 47.70)):
                    self.assertFalse(masked(point), point)  # Winslow, Grand Forest, north island
                index = layers.index(mask)
                self.assertGreater(index, max(i for i, l in enumerate(layers) if l["type"] != "symbol" and l["id"] not in ("island-exterior", "shore")))
                self.assertLess(index, next(i for i, l in enumerate(layers) if l["id"] == "shore"))

    def test_shore_has_no_vector_tile_edges_and_labels_are_island_only(self):
        for palette in (styles.TOPO, styles.MINIMAL_LIGHT, styles.DARK):
            style = styles.build(palette)
            self.assertFalse(any(l["id"].startswith("shore-ripple") for l in style["layers"]),
                             "offset shore copies cross in narrow inlets")
            for layer in style["layers"]:
                if layer["id"] == "shore":
                    self.assertNotIn("source-layer", layer)
                    self.assertEqual("geojson", style["sources"][layer["source"]]["type"])
                    self.assertEqual("LineString", style["sources"][layer["source"]]["data"]["type"])
                if layer["type"] == "symbol":
                    self.assertIn('"within"', json.dumps(layer["filter"]), layer["id"])

    def test_committed_templates_match_the_generator(self):
        for palette in (styles.TOPO, styles.MINIMAL_LIGHT, styles.DARK):
            actual = json.loads((styles.OUT / (palette["name"] + ".json")).read_text())
            self.assertEqual(styles.build(palette), actual)


if __name__ == "__main__":
    unittest.main()
