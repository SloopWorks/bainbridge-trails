package com.bainbridgetrails.play.geo

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.schedule.JvmDatasetPlaceIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** README glossary / PD-10: one grid, 0.01° lat × 0.015° lon, anchored at 47.55 N, 122.62 W. */
class ChartSquareTest {
    @Test
    fun `anchor 47_55 -122_62`() {
        assertEquals(ChartSquare(0, 0), ChartSquare.of(47.55, -122.62))
        assertEquals(ChartSquare(0, 0), ChartSquare.of(47.559999, -122.605001))
        assertEquals(ChartSquare(1, 1), ChartSquare.of(47.56, -122.605))
        assertEquals(ChartSquare(-1, -1), ChartSquare.of(47.5499, -122.6201))
        assertEquals(ChartSquare(7, 7), ChartSquare.of(47.62371, -122.51283)) // the Bainbridge ferry terminal
        assertEquals("7:7", ChartSquare(7, 7).id)
        assertEquals(ChartSquare(7, 7), ChartSquare.parse("7:7"))
        assertEquals(GeoPoint(lat = 47.62, lon = -122.515), ChartSquare(7, 7).southWest)
        // floating-point edges land in the square they name (micro-degree arithmetic, as the pack build does)
        assertEquals(ChartSquare(5, 0), ChartSquare.of(47.60, -122.62))
        assertEquals(ChartSquare(0, 3), ChartSquare.of(47.55, -122.575))
    }

    @Test
    fun `62 trails squares - 83 roads squares`() {
        val index = JvmDatasetPlaceIndex.loadOrSkip("ChartSquareTest")
        val trails = ChartSquare.covering(index.includedSegments(Goal.trails).map { it.line })
        val roads = ChartSquare.covering(index.includedSegments(Goal.roads).map { it.line })
        println("ChartSquareTest: ${trails.size} trails squares, ${roads.size} roads squares")
        assertEquals(62, trails.size)
        assertEquals(83, roads.size)
        // parity with the pack build's derived files, when present
        for ((goal, squares) in listOf(Goal.trails to trails, Goal.roads to roads)) {
            val f = File("../../data/play/derived/${goal.name}.json").takeIf { it.exists() } ?: continue
            val doc = Json.parseToJsonElement(f.readText()).jsonObject
            val derived = doc["segmentSquares"]?.jsonObject ?: continue
            val ids = derived.values.flatMap { v -> (v as? JsonArray)?.map { it.jsonPrimitive.content } ?: listOf(v.jsonPrimitive.content) }
            assertEquals(ids.toSet(), squares.map { it.id }.toSet(), "$goal vs ${f.path} segmentSquares")
            doc["chartSquares"]?.jsonPrimitive?.intOrNull?.let { assertEquals(it, squares.size, "$goal vs ${f.path} chartSquares") }
        }
    }
}
