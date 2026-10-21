package com.bainbridgetrails.play.fx.effects

import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.geo.ChartSquare
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplorationFogTest {
    /** A 3 × 3 block of chart squares around Winslow (the memorial zone sits in 6:7). */
    private val squares = (5..7).flatMap { r -> (6..8).map { c -> ChartSquare(r, c) } }

    /** A rough 200 m circle (24-gon) at the memorial (a quiet zone's shape: a buffer polygon). */
    private val memorial: List<GeoPoint> = (0 until 24).map { i ->
        val a = 2 * kotlin.math.PI * i / 24
        GeoPoint(-122.50985 + 0.0027 * kotlin.math.cos(a), 47.61400 + 0.0018 * kotlin.math.sin(a))
    }

    @Test
    fun noFogPolygonInsideAQuietZone() {
        val specs = ExplorationFog.specs(squares, lifted = emptySet(), quiet = listOf(memorial))
        assertTrue(specs.isNotEmpty())
        for (s in specs) {
            val ring = (s.anchor as FxAnchor.Area).rings.single()
            // Sample a 9 × 9 grid inside every fog polygon: none of it may fall inside the zone.
            val minLon = ring.minOf { it.lon }; val maxLon = ring.maxOf { it.lon }
            val minLat = ring.minOf { it.lat }; val maxLat = ring.maxOf { it.lat }
            for (i in 0..8) for (j in 0..8) {
                val p = GeoPoint(minLon + (maxLon - minLon) * i / 8, minLat + (maxLat - minLat) * j / 8)
                assertTrue(!inside(p, memorial), "${s.id} covers $p inside the quiet zone")
            }
            // And no zone vertex lies inside the fog polygon.
            assertTrue(memorial.none { it.lon > minLon && it.lon < maxLon && it.lat > minLat && it.lat < maxLat }, "${s.id} overlaps the zone")
        }
        // The square holding the zone keeps the fog outside it (cut out, not dropped whole).
        val zoneSquare = ChartSquare.of(47.614, -122.50985).id
        assertTrue(specs.any { ExplorationFog.squareOf(it) == zoneSquare }, "fog remains around the zone in $zoneSquare")
    }

    @Test
    fun liftedSquaresExcluded() {
        val lifted = setOf(squares[0].id, squares[4].id)
        val specs = ExplorationFog.specs(squares, lifted = lifted, quiet = emptyList())
        val fogged = specs.map { ExplorationFog.squareOf(it) }.toSet()
        assertEquals(squares.map { it.id }.toSet() - lifted, fogged)
        // Without a quiet zone a square is one polygon.
        assertEquals(squares.size - lifted.size, specs.size)
        assertTrue(specs.all { it.band == FxBand.UNDER_STATUS && it.kind == ExplorationFog.kind })
        // Deterministic ids.
        assertEquals(specs.map { it.id }, ExplorationFog.specs(squares, lifted, emptyList()).map { it.id })
    }

    private fun inside(p: GeoPoint, poly: List<GeoPoint>): Boolean {
        var c = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) c = !c
            j = i
        }
        return c
    }
}
