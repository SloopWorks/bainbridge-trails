package com.bainbridgetrails.play.fx.effects

import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreasureGlintTest {
    private fun spec(i: Int) = TreasureGlint.spec("treasure-fay@2026-W43#$i", GeoPoint(-122.508 + i * 0.0003, 47.701))

    @Test
    fun geoJsonNumericIdsEqualSeq() {
        val live = (1..8).map { live(spec(it), seq = 100L + it * 7) }
        val features = Json.parseToJsonElement(TreasureGlint.geoJson(live)).jsonObject["features"]!!.jsonArray
        assertEquals(live.map { it.instance.seq }, features.map { it.jsonObject["id"]!!.jsonPrimitive.long })
        // At most 6 glint (effects §3.7); the rest stay matte.
        assertEquals(TreasureGlint.MAX_GLINTING, features.count { it.jsonObject["properties"]!!.jsonObject["glint"]!!.jsonPrimitive.int == 1 })
    }

    @Test
    fun phaseIn0to1() {
        val live = (1..50).map { live(spec(it), seq = it.toLong()) }
        val features = Json.parseToJsonElement(TreasureGlint.geoJson(live)).jsonObject["features"]!!.jsonArray
        val phases = features.map { it.jsonObject["properties"]!!.jsonObject["phase"]!!.jsonPrimitive.double }
        assertTrue(phases.all { it >= 0.0 && it < 1.0 }, "phase in [0, 1): $phases")
        assertTrue(phases.toSet().size > 40, "phases are de-synced by seed")
        for (now in listOf(0L, 1L, 3_999L, 4_000L, 123_456L)) assertTrue(TreasureGlint.globalT(now) in 0.0..1.0)
    }

    @Test
    fun emptyListValid() {
        val json = Json.parseToJsonElement(TreasureGlint.geoJson(emptyList())).jsonObject
        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        assertEquals(0, json["features"]!!.jsonArray.size)
    }

    @Test
    fun reducedOrDemotedNeverGlint() {
        val live = listOf(live(spec(1), 1, reduced = true), live(spec(2), 2, demoted = true))
        val features = Json.parseToJsonElement(TreasureGlint.geoJson(live)).jsonObject["features"]!!.jsonArray
        assertTrue(features.all { it.jsonObject["properties"]!!.jsonObject["glint"]!!.jsonPrimitive.int == 0 })
    }

    @Test
    fun specIsAmbientLoopingOverLabels() {
        val s = spec(1)
        assertEquals(TreasureGlint.kind, s.kind)
        assertEquals(FxPriority.AMBIENT, s.priority)
        assertEquals(FxBand.OVER_LABELS, s.band)
        assertTrue(s.timeline.runLoopMs != null)
    }
}
