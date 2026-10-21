package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.play.fx.effects.KrakenInk
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.ui.components.IslandGeometry
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * e2e run 1 F6: the roads kraken label was drawn inland on Falk Rd NE. The water point was the middle of the whole
 * coast pool (Manitou + Gertie Johnson) pushed 180 m from the island's centre; it is now beside the attack's longest
 * stretch, in the water, ≥ [Coast.MIN_OFFSHORE_M] off the coastline.
 */
class KrakenWaterPointTest {
    private val dir = "src/commonMain/composeResources/files"

    private fun read(name: String): String? =
        Path("$dir/$name").takeIf { SystemFileSystem.exists(it) }?.let { p -> SystemFileSystem.source(p).buffered().use { it.readString() } }

    @Test
    fun syntheticShoreRoadWaterIsOffshore() {
        // A 2 km square island; a shore road 30 m inside its east edge.
        val ring = listOf(
            doubleArrayOf(-122.53, 47.63), doubleArrayOf(-122.5034, 47.63), doubleArrayOf(-122.5034, 47.648),
            doubleArrayOf(-122.53, 47.648), doubleArrayOf(-122.53, 47.63),
        )
        val coast = Coast(listOf(ring))
        val edge = GeoPoint(lat = 47.639, lon = -122.5034)
        val a = GeoMath.offset(edge, -30.0, -300.0)
        val b = GeoMath.offset(edge, -30.0, 300.0)
        assertTrue(coast.isLand(a))
        val w = PlayMapEffects.water(listOf(KrakenInk.Stretch("road-shore", listOf(listOf(a, b)))), coast)
        assertFalse(coast.isLand(w), "water: $w")
        assertTrue(coast.distanceM(w) >= 20.0, "${coast.distanceM(w)} m off the coast")
        assertTrue(GeoMath.distanceM(w, GeoMath.offset(edge, -30.0, 0.0)) < 200.0, "beside its stretch")
    }

    /**
     * Every coast pool of both goals, with the real derived stretches and segment geometry: the kraken marker (the water
     * point) is in the water ≥ 20 m off the coastline and near its main stretch. Needs the synced dataset
     * (`scripts/sync-app-assets.sh`, gitignored like the :coverage real-data tests); without it only the bundled
     * coastline is checked.
     */
    @Test
    fun everyKrakenMarkerIsOffshoreOnTheRealData() {
        val coast = Coast(IslandGeometry.parseCoastline(assertNotNull(read("coastline.json"))))
        assertFalse(coast.isEmpty)
        var checked = 0
        for ((goal, segFile) in listOf("roads" to "roads_coverage_segments.json", "trails" to "coverage_segments.json")) {
            val segJson = read(segFile) ?: continue
            val segs = Json.parseToJsonElement(segJson).jsonObject["segments"]!!.jsonArray.associate { s ->
                val o = s.jsonObject
                o["id"]!!.jsonPrimitive.content to o["coords"]!!.jsonArray.map { c ->
                    GeoPoint(lon = c.jsonArray[0].jsonPrimitive.double, lat = c.jsonArray[1].jsonPrimitive.double)
                }
            }
            val derived = Json.parseToJsonElement(assertNotNull(read("play/derived-$goal.json"))).jsonObject
            val stretches = derived["stretches"]!!.jsonObject
            for ((pool, c) in derived["coasts"]!!.jsonObject) {
                val ids = c.jsonObject["stretches"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                val attack = ids.map { id ->
                    val lines = stretches[id]!!.jsonObject["segments"]!!.jsonArray.mapNotNull { segs[it.jsonArray[0].jsonPrimitive.content] }
                    KrakenInk.Stretch(id, lines)
                }
                val w = PlayMapEffects.water(attack, coast)
                assertFalse(coast.isLand(w), "$goal $pool: water point $w is on land")
                assertTrue(coast.distanceM(w) >= 20.0, "$goal $pool: ${coast.distanceM(w)} m off the coast")
                val nearest = attack.flatMap { s -> s.lines.flatten() }.minOf { GeoMath.distanceM(it, w) }
                assertTrue(nearest < 600.0, "$goal $pool: $nearest m from its stretches")
                checked++
            }
        }
        println("KrakenWaterPointTest: $checked coast pools checked on the real data")
    }
}
