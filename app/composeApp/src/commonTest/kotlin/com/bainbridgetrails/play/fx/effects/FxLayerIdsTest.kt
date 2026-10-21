package com.bainbridgetrails.play.fx.effects

import com.bainbridgetrails.map.StatusLayerSpec
import com.bainbridgetrails.play.fx.FxLayerIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every L2-4 FX layer id is `fx-…` and never a tappable trail layer (engine PF-3, PF-9; product PF3-5). */
class FxLayerIdsTest {
    private val all: Map<String, List<String>> = mapOf(
        TreasureGlint.kind to TreasureGlint.layerIds,
        ExplorationFog.kind to ExplorationFog.layerIds,
        RiddleMarker.KIND to RiddleMarker.layerIds,
        KrakenInk.kind to KrakenInk.layerIds,
        KrakenLurk.kind to KrakenLurk.layerIds,
        FerryCrossing.KIND to FerryCrossing.layerIds,
        FrogRockStamp.kind to FrogRockStamp.layerIds,
    )

    @Test
    fun everyFxLayerIdStartsWithFxAndIsNotInTappableTrailLayerIds() {
        val tappable = StatusLayerSpec.TRAILS.tappableLayerIds + StatusLayerSpec.ROADS.tappableLayerIds
        for ((kind, ids) in all) {
            assertTrue(ids.isNotEmpty(), "$kind declares its layer ids")
            for (id in ids) {
                assertTrue(FxLayerIds.isFx(id), "$id starts with fx-")
                assertTrue(id.startsWith(FxLayerIds.PREFIX + kind.replace('_', '-') + "-"), "$id is namespaced by its kind")
                assertTrue(id !in tappable, "$id is not a tappable trail layer")
            }
        }
    }

    @Test
    fun layerIdsAreUniqueAcrossEffects() {
        val ids = all.values.flatten()
        assertEquals(ids.size, ids.toSet().size, "duplicate fx layer ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }
}
