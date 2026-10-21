package com.bainbridgetrails.play.fx

import com.bainbridgetrails.map.StatusLayerSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Band z-order in the map's style composition (engine PF-3, spike S3): UNDER_STATUS before StatusLayers (CASING inside
 * it), OVER_STATUS after, OVER_LABELS in Anchor.Top, and `LocationIndicatorLayer` composed after OVER_LABELS
 * (un-anchored layers composed later land above earlier Anchor.Top blocks). Plus the fx- id and GeoJSON helpers.
 */
class FxLayerOrderTest {
    @Test
    fun locationIndicatorComposedAfterOverLabels() {
        val order = mapContentOrder(play = true, location = true)
        assertEquals(
            listOf(
                MapContentPart.Fx(FxBand.UNDER_STATUS),
                MapContentPart.Status,
                MapContentPart.Fx(FxBand.OVER_STATUS),
                MapContentPart.Fx(FxBand.OVER_LABELS),
                MapContentPart.Location,
            ),
            order,
        )
        assertEquals(order.lastIndex, order.indexOf(MapContentPart.Location))
        assertTrue(order.indexOf(MapContentPart.Fx(FxBand.OVER_LABELS)) < order.indexOf(MapContentPart.Location))
    }

    @Test
    fun layerBandsFollowFxBandOrderAndCasingRidesInsideStatus() {
        val bands = mapContentOrder(play = true, location = false).filterIsInstance<MapContentPart.Fx>().map { it.band }
        assertEquals(bands.sortedBy { it.ordinal }, bands)
        assertFalse(FxBand.CASING in bands, "CASING is StatusLayers' casingFx slot, not a separate block")
        assertTrue(bands.all { it.isLayerTier })
    }

    @Test
    fun playOffIsTodaysOrder() {
        assertEquals(listOf(MapContentPart.Status, MapContentPart.Location), mapContentOrder(play = false, location = true))
        assertEquals(listOf(MapContentPart.Status), mapContentOrder(play = false, location = false))
    }

    @Test
    fun fxLayerIdsStartWithFxAndAreNeverTappableTrailLayers() {
        assertEquals("fx-treasure-glint-halo", FxLayerIds.of("treasure_glint", "halo"))
        assertEquals("fx-kraken-attack-casing", FxLayerIds.of("kraken_attack", "casing"))
        assertTrue(FxLayerIds.isFx("fx-treasure-glint-halo"))
        assertFalse(FxLayerIds.isFx("app-trail-complete"))
        assertFailsWith<IllegalArgumentException> { FxLayerIds.of("", "halo") }
        assertFailsWith<IllegalArgumentException> { FxLayerIds.of("treasure_glint", "") }
        val tappable = StatusLayerSpec.TRAILS.tappableLayerIds + StatusLayerSpec.ROADS.tappableLayerIds
        assertTrue(tappable.none { FxLayerIds.isFx(it) })
    }

    private fun live(seq: Long, anchor: FxAnchor) = FxLive(
        instance = FxInstance(
            FxSpec(
                id = "item-$seq", kind = "treasure_glint", anchor = anchor, zoom = 12.0..18.0, band = FxBand.OVER_STATUS,
                priority = FxPriority.AMBIENT, timeline = FxTimeline(100, 1_000, 100, 100, runLoopMs = 1_000, reducedAtMs = 500), loop = true,
            ),
            seq = seq, spawnedAtMs = 0,
        ),
        stage = FxStage.RUN, t = 0, reduced = false,
    )

    @Test
    fun geoJsonFeaturesCarryNumericIdsEqualToSeq() {
        val json = fxGeoJson(
            listOf(
                live(3, FxAnchor.Point(GeoPoint(-122.5, 47.6))),
                live(7, FxAnchor.Line(listOf(GeoPoint(-122.5, 47.6), GeoPoint(-122.51, 47.61)))),
                live(9, FxAnchor.Screen(0.5f, 0.5f)), // not geographic: skipped
            ),
        )
        val features = Json.parseToJsonElement(json).jsonObject.getValue("features").jsonArray.map { it.jsonObject }
        assertEquals(listOf(3L, 7L), features.map { it.getValue("id").jsonPrimitive.long })
        assertEquals(listOf("Point", "LineString"), features.map { (it.getValue("geometry") as JsonObject).getValue("type").jsonPrimitive.content })
        assertEquals("""{"type":"FeatureCollection","features":[]}""", fxGeoJson(emptyList()))
    }
}
