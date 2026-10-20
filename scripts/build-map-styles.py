#!/usr/bin/env python3
"""Generate the app's MapLibre style templates (topo, minimal-light, dark).

Output: app/composeApp/src/commonMain/composeResources/files/styles/{topo,minimal-light,dark}.json
Edit palettes / layers here and re-run; the JSON files are committed generated output.

    python3 scripts/build-map-styles.py

Templates use double-brace placeholders (basemap, trails, contours, hillshade) that the app replaces
with local pmtiles:// URLs (StyleTemplate.kt). Never emit a double-brace token anywhere else: an unknown
placeholder is a hard error at runtime.

Trail status / not-counted / halo / dim layers are drawn by the app (Compose LineLayers over the
"trails" source, decisions.md TL-1); this script only emits the base map and status-independent cues.
See docs/spikes/map-render/styles.md.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/composeApp/src/commonMain/composeResources/files/styles"

GLYPHS = "https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf"
FONT_REGULAR = ["Noto Sans Regular"]
FONT_MEDIUM = ["Noto Sans Medium"]
FONT_ITALIC = ["Noto Sans Italic"]

STATUSES = ["not_started", "partway", "nearly_done", "complete"]

COUNTED = ["==", ["get", "included"], True]
NOT_COUNTED = ["!=", ["get", "included"], True]

# --- roads dataset (the roads prototype, docs/roads/plan.md) ---------------------------------------
# The "trails" source is whichever dataset the app installed (DatasetProfile): data/dist/trails.pmtiles or
# data/roads/dist/roads.pmtiles, both with layer "segments". These categories exist only in the roads dataset, so
# the layers below are inert on the trail dataset and no second style is needed.
ROAD_CATS = ("state_route", "arterial", "collector", "residential", "lane", "driveway")
IS_ROAD = ["in", ["get", "category"], ["literal", list(ROAD_CATS)]]
PRIVATE = ["==", ["get", "ownership"], "private"]
# Class factor on the bed width: a state route reads wider than a residential street at the same zoom.
# MapLibre requires the zoom interpolation to be the outermost expression of a paint property, so the factor is
# applied per stop (a nested ["*", interpolate, match] is invalid and the native renderer drops the layer).
ROAD_CLASS_FACTOR = {"state_route": 2.1, "arterial": 1.7, "collector": 1.35}


def road_bed_width(stops: dict[float, float]) -> list:
    expr: list = ["interpolate", ["linear"], ["zoom"]]
    for z, w in stops.items():
        match: list = ["match", ["get", "category"]]
        for cat, f in ROAD_CLASS_FACTOR.items():
            match += [cat, round(w * f, 2)]
        expr += [z, match + [w]]
    return expr


# --- status encoding (shared by all styles; colors per style) --------------------------------------
# Width stops (zoom -> px). Encoding never relies on color alone:
#   complete     solid, thickest
#   nearly_done  solid, medium, dark casing
#   partway      long dash, medium-thin
#   not_started  round dots, thin
WIDTH = {
    "complete": {11: 1.8, 13: 3.2, 15: 5.6, 17: 9.0},
    "nearly_done": {11: 1.3, 13: 2.3, 15: 3.8, 17: 6.2},
    "partway": {11: 1.2, 13: 2.0, 15: 3.2, 17: 5.0},
    "not_started": {11: 1.1, 13: 1.7, 15: 2.6, 17: 4.0},
}
DASH = {"partway": [2.6, 1.4], "not_started": [0.01, 1.9]}
CASING_EXTRA = 2.2  # nearly_done casing (px beyond line width)
HALO_EXTRA = 3.0  # land-colored halo under every counted trail


def interp(stops: dict[float, float | list], curve: list | None = None) -> list:
    expr: list = ["interpolate", curve or ["linear"], ["zoom"]]
    for z, v in stops.items():
        expr += [z, v]
    return expr


def width(status: str, extra: float = 0.0) -> list:
    return interp({z: round(w + extra, 2) for z, w in WIDTH[status].items()})


def road_width(stops: dict[float, float]) -> list:
    return interp(stops, ["exponential", 1.6])


def line(id_, source, source_layer, paint, filter_=None, layout=None, minzoom=None, maxzoom=None) -> dict:
    layer = {"id": id_, "type": "line", "source": source}
    if source_layer:
        layer["source-layer"] = source_layer
    if minzoom is not None:
        layer["minzoom"] = minzoom
    if maxzoom is not None:
        layer["maxzoom"] = maxzoom
    if filter_ is not None:
        layer["filter"] = filter_
    layer["layout"] = {"line-cap": "round", "line-join": "round", **(layout or {})}
    layer["paint"] = paint
    return layer


def fill(id_, source_layer, color, filter_=None, opacity=None, minzoom=None) -> dict:
    layer = {"id": id_, "type": "fill", "source": "basemap", "source-layer": source_layer}
    if minzoom is not None:
        layer["minzoom"] = minzoom
    if filter_ is not None:
        layer["filter"] = filter_
    paint = {"fill-color": color}
    if opacity is not None:
        paint["fill-opacity"] = opacity
    layer["paint"] = paint
    return layer


POLYGON = ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], True, False]


def kind_in(*kinds) -> list:
    return ["in", ["get", "kind"], ["literal", list(kinds)]]


WOODLAND = ("forest", "wood", "nature_reserve", "national_park", "protected_area", "park")
OPEN_GREEN = ("grass", "meadow", "golf_course", "recreation_ground", "playground", "garden", "cemetery",
              "village_green", "allotments")
SAND = ("beach", "sand")
WETLAND = ("wetland", "marsh", "swamp", "bog")
FARMLAND = ("farmland", "orchard", "vineyard", "farmyard")


def build(p: dict) -> dict:
    """p: palette + feature switches for one style."""
    sources = {
        "basemap": {"type": "vector", "url": "{{basemap}}"},
        "trails": {"type": "vector", "url": "{{trails}}"},
    }
    if p.get("contours"):
        sources["contours"] = {"type": "vector", "url": "{{contours}}"}
    if p.get("hillshade"):
        sources["hillshade"] = {"type": "raster", "url": "{{hillshade}}", "tileSize": 256}

    L: list[dict] = []
    L.append({"id": "background", "type": "background", "paint": {"background-color": p["water"]}})
    L.append(fill("earth", "earth", p["land"]))
    L.append(fill("landuse-farm", "landuse", p["farm"], kind_in(*FARMLAND)))
    L.append(fill("landuse-open", "landuse", p["open_green"], kind_in(*OPEN_GREEN)))
    L.append(fill("landuse-wood", "landuse", p["wood"], kind_in(*WOODLAND)))
    L.append(fill("landuse-wetland", "landuse", p["wetland"], kind_in(*WETLAND)))
    L.append(fill("landuse-sand", "landuse", p["sand"], kind_in(*SAND)))

    if p.get("hillshade"):
        hs = p["hillshade"]
        L.append({
            "id": "hillshade", "type": "raster", "source": "hillshade",
            "paint": {
                "raster-opacity": interp(hs["opacity"]),
                "raster-brightness-min": hs.get("bmin", 0.0),
                "raster-brightness-max": hs.get("bmax", 1.0),
                "raster-contrast": hs.get("contrast", 0.0),
                "raster-fade-duration": 0,
            },
        })

    L.append(fill("water", "water", p["water"], POLYGON))
    # Coastline = outline of the land polygons (water-polygon outlines include extract/tile edges).
    L.append(line("shore", "basemap", "earth", {
        "line-color": p["shore"],
        "line-width": interp({10: 0.4, 14: 0.9, 18: 1.6}),
    }, POLYGON, minzoom=10))

    # Signature: nautical-chart "water lines" - concentric offsets of the coastline into the water.
    for i, (offset, opacity) in enumerate(p.get("ripples", {}).get("lines", [])):
        L.append(line(f"shore-ripple-{i}", "basemap", "earth", {
            "line-color": p["ripples"]["color"],
            "line-opacity": opacity,
            "line-width": interp({10: 0.5, 15: 0.8}),
            "line-offset": interp({10: offset * 0.6, 14: offset, 17: offset * 1.4}),
        }, POLYGON, minzoom=10))

    if p.get("contours"):
        c = p["contours"]
        L.append(line("contour-minor", "contours", "contours", {
            "line-color": c["color"],
            "line-opacity": interp({13: 0.0, 13.5: c["minor_opacity"]}),
            "line-width": interp({13: 0.4, 17: 0.8}),
        }, ["all", ["!=", ["get", "index"], True], [">", ["get", "elev_m"], 0]], minzoom=13))
        L.append(line("contour-index", "contours", "contours", {
            "line-color": c["color"],
            "line-opacity": c["index_opacity"],
            "line-width": interp({11: 0.5, 14: 1.0, 17: 1.6}),
        }, ["all", ["==", ["get", "index"], True], [">", ["get", "elev_m"], 0]]))

    if p.get("buildings"):
        L.append(fill("buildings", "buildings", p["buildings"], minzoom=13,
                      opacity=interp({13: 0.0, 14: 1.0})))

    r = p["roads"]
    minor = ("minor_road", "other")
    major = ("major_road",)
    hwy = ("highway",)
    if r.get("casing"):
        L.append(line("roads-minor-casing", "basemap", "roads", {
            "line-color": r["casing"],
            "line-width": road_width({12: 0.8, 15: 3.2, 18: 13}),
        }, kind_in(*minor), minzoom=12))
        L.append(line("roads-major-casing", "basemap", "roads", {
            "line-color": r["casing"],
            "line-width": road_width({10: 1.2, 15: 5.0, 18: 17}),
        }, kind_in(*major, *hwy)))
    L.append(line("roads-minor", "basemap", "roads", {
        "line-color": r["minor"],
        "line-width": road_width({12: 0.4, 15: 2.0, 18: 10}),
    }, kind_in(*minor), minzoom=11))
    L.append(line("roads-major", "basemap", "roads", {
        "line-color": r["major"],
        "line-width": road_width({10: 0.7, 15: 3.6, 18: 14}),
    }, kind_in(*major)))
    L.append(line("roads-highway", "basemap", "roads", {
        "line-color": r["highway"],
        "line-width": road_width({10: 0.9, 15: 3.8, 18: 14}),
    }, kind_in(*hwy)))
    L.append(line("ferry", "basemap", "roads", {
        "line-color": p["ferry"],
        "line-width": interp({10: 0.6, 15: 1.4}),
        "line-dasharray": [3, 2.5],
    }, kind_in("ferry")))

    # ---- roads dataset (inert on the trail dataset) ------------------------------------------------
    # Counted roads get a category-coloured bed under the app's status lines, so class is readable while coverage
    # keeps the status ramp. Uncounted roads are the style's job entirely: private ones dashed, the ownership
    # holdouts (unknown / lane / driveway, out of the goal until settled) dotted and fainter.
    rd = p["road_dataset"]
    L.append(line("roads-ds-bed", "trails", "segments", {
        "line-color": ["match", ["get", "category"],
                       "state_route", rd["state_route"], "arterial", rd["arterial"],
                       "collector", rd["collector"], rd["residential"]],
        "line-width": road_bed_width({10: 1.0, 13: 2.8, 15: 5.5, 18: 11.0}),
        "line-opacity": rd.get("bed_opacity", 1.0),
    }, ["all", COUNTED, IS_ROAD], minzoom=10))
    L.append(line("roads-ds-private", "trails", "segments", {
        "line-color": rd["private"],
        "line-opacity": rd["muted_opacity"],
        "line-width": interp({10: 0.6, 14: 1.6, 18: 3.4}),
        "line-dasharray": [2.2, 1.8],
    }, ["all", NOT_COUNTED, IS_ROAD, PRIVATE], minzoom=10))
    L.append(line("roads-ds-holdout", "trails", "segments", {
        "line-color": rd["holdout"],
        "line-opacity": rd["muted_opacity"] * 0.75,
        "line-width": interp({10: 0.5, 14: 1.3, 18: 2.6}),
        "line-dasharray": [0.01, 2.4],
    }, ["all", NOT_COUNTED, IS_ROAD, ["!", PRIVATE]], minzoom=10))

    # ---- trails -----------------------------------------------------------------------------------
    # decisions.md TL-1: status, not-counted, selected-halo and dim layers are NOT in the style. The app
    # declares them as Compose LineLayers over this style's "trails" source (source-layer "segments"),
    # one layer per status bucket filtered by segment id, so coverage changes never reload the style.
    # Only the status-independent category cue stays here. The "trails" palette entries below are the
    # reference the app's layer colors mirror (app/composeApp/.../map/StatusLayers.kt).
    t = p["trails"]
    # multi_use_path: paved shared path gets a wide pale "bed" underneath (category cue).
    L.append(line("trails-mup-bed", "trails", "segments", {
        "line-color": t["mup_bed"],
        "line-width": interp({12: 2.5, 15: 9.0, 18: 15.0}),
        "line-opacity": t.get("mup_bed_opacity", 1.0),
    }, ["all", COUNTED, ["==", ["get", "category"], "multi_use_path"]], minzoom=12))

    # ---- labels (online glyphs; offline -> labels silently absent, map unaffected) ----------------
    lab = p["labels"]
    if p.get("contours"):
        L.append({
            "id": "contour-label", "type": "symbol", "source": "contours", "source-layer": "contours",
            "minzoom": 14, "filter": ["all", ["==", ["get", "index"], True], [">", ["get", "elev_m"], 0]],
            "layout": {
                "symbol-placement": "line",
                "text-field": ["concat", ["to-string", ["get", "elev_m"]], " m"],
                "text-font": FONT_ITALIC,
                "text-size": 10,
                "symbol-spacing": 320,
            },
            "paint": {"text-color": p["contours"]["label"], "text-halo-color": p["land"], "text-halo-width": 1.4},
        })
    L.append({
        "id": "water-label", "type": "symbol", "source": "basemap", "source-layer": "water",
        "filter": ["all", ["==", ["geometry-type"], "Point"], ["has", "name"]],
        "layout": {
            "text-field": ["get", "name"],
            "text-font": FONT_ITALIC,
            "text-size": interp({10: 11, 15: 14}),
            "text-letter-spacing": 0.08,
            "text-max-width": 7,
        },
        "paint": {"text-color": lab["water"], "text-halo-color": p["water"], "text-halo-width": 1.0},
    })
    L.append({
        "id": "trail-label", "type": "symbol", "source": "trails", "source-layer": "segments",
        "minzoom": 14, "filter": ["all", COUNTED, ["has", "name"]],
        "layout": {
            "symbol-placement": "line",
            "text-field": ["get", "name"],
            "text-font": FONT_MEDIUM,
            "text-size": interp({14: 11, 17: 13}),
            "symbol-spacing": 420,
            "text-max-angle": 25,
            "text-padding": 6,
            "text-offset": [0, 1.0],
        },
        "paint": {"text-color": lab["trail"], "text-halo-color": lab["halo"], "text-halo-width": 1.6},
    })
    L.append({
        "id": "place-label", "type": "symbol", "source": "basemap", "source-layer": "places",
        # Towns/hamlets/neighbourhoods only (OSM farms are tagged as localities), at their min_zoom.
        "filter": ["all", kind_in("locality", "neighbourhood", "macrohood"),
                   ["!", ["in", ["get", "kind_detail"], ["literal", ["farm", "isolated_dwelling", "allotments"]]]],
                   ["<=", ["get", "min_zoom"], ["+", ["zoom"], 0.5]]],
        "layout": {
            "text-field": ["get", "name"],
            "text-font": FONT_MEDIUM,
            "text-size": ["match", ["get", "kind_detail"], ["town", "city"], 14, ["village", "suburb"], 12, 11],
            "symbol-sort-key": ["get", "sort_key"],
            "text-letter-spacing": 0.04,
            "text-max-width": 8,
        },
        "paint": {"text-color": lab["place"], "text-halo-color": lab["halo"], "text-halo-width": 1.4},
    })

    return {
        "version": 8,
        "name": f"Bainbridge Trails - {p['name']}",
        "metadata": {
            "bainbridgetrails:generated": "scripts/build-map-styles.py - edit there, not here",
            "bainbridgetrails:status": "status layers are app-side Compose LineLayers over source trails/segments (TL-1)",
        },
        "glyphs": GLYPHS,
        "sources": sources,
        "layers": L,
    }


# --- palettes ------------------------------------------------------------------------------------
# Status ramp (all styles): cool/quiet -> hot/saturated as completion rises.
#   not_started warm-gray dots, partway gold dashes, nearly_done tangerine + dark casing, complete violet.
#   Violet (blue-heavy) stays a vivid blue under deutan/protan simulation, so "done" keeps its pop.

TOPO = {
    "name": "topo",
    "land": "#ECEFE6", "water": "#9EBFC7", "shore": "#7FA2AB",
    "wood": "#D4E0C6", "open_green": "#E2E9D7", "farm": "#ECEFE6", "wetland": "#CFDDD2", "sand": "#E9E4D0",
    "buildings": "#D9DCD2",
    "hillshade": {"opacity": {10: 0.36, 15: 0.26}, "bmin": 0.12, "bmax": 1.0, "contrast": 0.15},
    "contours": {"color": "#9C845C", "minor_opacity": 0.32, "index_opacity": 0.55, "label": "#86704C"},
    "roads": {"casing": "#C3C7BC", "minor": "#FFFFFF", "major": "#FFFFFF", "highway": "#F4E7C0"},
    "ferry": "#5E8691",
    "ripples": {"color": "#6F97A2", "lines": [(-3.0, 0.75), (-6.5, 0.5), (-11.0, 0.28)]},
    "trails": {
        "status": {"not_started": "#6E6A64", "partway": "#C98A00", "nearly_done": "#E2562A",
                   "complete": "#9B1FA8"},
        "casing": "#5A1F0C", "halo": "#ECEFE6", "halo_opacity": 0.85, "mup_bed": "#FFFFFF",
        "mup_bed_opacity": 0.85,
        "not_counted": {"color": "#7D8679", "opacity": 0.75, "beach": "#4F8797", "closed": "#A6564A"},
    },
    "labels": {"water": "#4C7682", "trail": "#2F3A33", "place": "#46504A", "halo": "#ECEFE6"},
    # Roads dataset: a cool steel family for class, so it never reads as the status ramp (gold/tangerine/violet).
    "road_dataset": {
        "state_route": "#5F86A6", "arterial": "#85A6BE", "collector": "#A6BFCB", "residential": "#C2D1D1",
        "bed_opacity": 1.0, "private": "#8E9890", "holdout": "#A2998A", "muted_opacity": 0.85,
    },
}

MINIMAL_LIGHT = {
    "name": "minimal-light",
    # Chart inversion: white water, soft gray land - the island reads as a shape, trails as the ink.
    "land": "#E8EBE7", "water": "#FBFCFC", "shore": "#9DB5BF",
    "wood": "#E1E6DE", "open_green": "#E4E8E1", "farm": "#E8EBE7", "wetland": "#E0E6E3", "sand": "#ECEBE5",
    "buildings": "#DEE1DC",
    "roads": {"minor": "#F7F8F6", "major": "#FFFFFF", "highway": "#FFFFFF"},
    "ferry": "#9DB5BF",
    "ripples": {"color": "#9DB5BF", "lines": [(-4.0, 0.45), (-9.0, 0.22)]},
    "trails": {
        "status": {"not_started": "#66625C", "partway": "#C08300", "nearly_done": "#DE4F22",
                   "complete": "#931B9F"},
        "casing": "#4A1A09", "halo": "#E8EBE7", "mup_bed": "#FFFFFF",
        "not_counted": {"color": "#9AA19A", "opacity": 0.85, "beach": "#7FA6B3", "closed": "#BB8077"},
    },
    "labels": {"water": "#6F8E9A", "trail": "#3A4247", "place": "#5F676C", "halo": "#E8EBE7"},
    "road_dataset": {
        "state_route": "#6E95B3", "arterial": "#93B2C6", "collector": "#B3C8D1", "residential": "#CDD9D8",
        "bed_opacity": 1.0, "private": "#9AA29B", "holdout": "#ADA596", "muted_opacity": 0.85,
    },
}

DARK = {
    "name": "dark",
    "land": "#1D2925", "water": "#0E1F27", "shore": "#2C4450",
    "wood": "#21312A", "open_green": "#22302A", "farm": "#212B26", "wetland": "#1F302C", "sand": "#2A2F29",
    "buildings": "#26322D",
    "hillshade": {"opacity": {10: 0.30, 15: 0.22}, "bmin": 0.0, "bmax": 0.55, "contrast": 0.1},
    "contours": {"color": "#5F7A6B", "minor_opacity": 0.22, "index_opacity": 0.4, "label": "#7D978A"},
    "roads": {"minor": "#34413B", "major": "#46544D", "highway": "#56625A"},
    "ferry": "#3D6372",
    "ripples": {"color": "#2E5564", "lines": [(-3.0, 0.7), (-6.5, 0.42), (-11.0, 0.22)]},
    "trails": {
        "status": {"not_started": "#A9A49B", "partway": "#F0B43C", "nearly_done": "#FF7B47",
                   "complete": "#D45CFF"},
        "casing": "#0B1410", "halo": "#1D2925", "halo_opacity": 0.8, "mup_bed": "#33413B",
        "complete_glow": 0.35,
        "not_counted": {"color": "#5E6E66", "opacity": 0.8, "beach": "#3F7486", "closed": "#8A5048"},
    },
    "labels": {"water": "#5F8694", "trail": "#DCE6E1", "place": "#A9B8B1", "halo": "#1D2925"},
    "road_dataset": {
        "state_route": "#6E90A8", "arterial": "#567A90", "collector": "#42606F", "residential": "#33505A",
        "bed_opacity": 1.0, "private": "#5A6861", "holdout": "#5E5A4E", "muted_opacity": 0.85,
    },
}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for palette, name in ((TOPO, "topo"), (MINIMAL_LIGHT, "minimal-light"), (DARK, "dark")):
        text = json.dumps(build(palette), indent=1) + "\n"
        (OUT / f"{name}.json").write_text(text)
        print(f"wrote {OUT.relative_to(ROOT)}/{name}.json ({len(text) // 1024} KB)")


if __name__ == "__main__":
    main()
