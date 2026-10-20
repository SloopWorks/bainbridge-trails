package com.bainbridgetrails.map

import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.data.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLogicTest {
    @Test
    fun boundsCoverEveryLine() {
        val b = boundsOf(listOf(listOf(listOf(-122.5, 47.6), listOf(-122.4, 47.65)), listOf(listOf(-122.55, 47.62))))
        assertEquals(GeoBounds(-122.55, 47.6, -122.4, 47.65), b)
        assertNull(boundsOf(emptyList()))
        assertNull(boundsOf(listOf(emptyList())))
    }

    @Test
    fun fitZoomIsClamped() {
        assertEquals(12.0, clampFitZoom(9.0))
        assertEquals(16.5, clampFitZoom(19.0))
        assertEquals(14.2, clampFitZoom(14.2))
    }

    @Test
    fun polylineDistance() {
        val line = listOf(ScreenPoint(0.0, 0.0), ScreenPoint(10.0, 0.0))
        assertEquals(5.0, distanceToPolyline(ScreenPoint(5.0, 5.0), line), 1e-9)
        assertEquals(5.0, distanceToPolyline(ScreenPoint(13.0, 4.0), line), 1e-9) // past the end
        assertEquals(5.0, distanceToPolyline(ScreenPoint(3.0, 4.0), listOf(ScreenPoint(0.0, 0.0))), 1e-9)
        assertTrue(distanceToPolyline(ScreenPoint(0.0, 0.0), emptyList()).isInfinite())
    }

    private val names = mapOf("a" to "Alder", "b" to "Birch", "c" to "Cedar", "d" to "Dogwood", "e" to "Elm")

    @Test
    fun noNamedHitClearsSelection() {
        assertEquals(TapResult.None, rankTap(emptyList()) { names.getValue(it) })
        assertEquals(TapResult.None, rankTap(listOf(SegmentHit(emptyList(), 3.0))) { names.getValue(it) })
    }

    @Test
    fun oneTrailSelects() {
        // Both hits are the same trail: nearest distance wins, still one trail.
        val r = rankTap(listOf(SegmentHit(listOf("a"), 9.0), SegmentHit(listOf("a"), 2.0))) { names.getValue(it) }
        assertEquals(TapResult.Select("a"), r)
    }

    @Test
    fun clearWinnerSelectsNearest() {
        val r = rankTap(listOf(SegmentHit(listOf("b"), 2.0), SegmentHit(listOf("a"), 14.0))) { names.getValue(it) }
        assertEquals(TapResult.Select("b"), r)
    }

    @Test
    fun closeCallOffersChoiceNearestFirstTiesByName() {
        // A shared segment (both trails at the same distance) + a third trail nearby.
        val r = rankTap(listOf(SegmentHit(listOf("c", "a"), 4.0), SegmentHit(listOf("b"), 10.0))) { names.getValue(it) }
        assertEquals(TapResult.Choose(listOf("a", "c", "b")), r)
    }

    @Test
    fun choiceListIsCappedAtFour() {
        val r = rankTap(listOf(SegmentHit(listOf("a", "b", "c", "d", "e"), 1.0))) { names.getValue(it) }
        assertEquals(TapResult.Choose(listOf("a", "b", "c", "d")), r)
    }

    @Test
    fun lengthsUnderATenthUseFeet() {
        assertEquals("1.4 mi", formatTrailLength(1.4, Units.MILES))
        assertEquals("0.1 mi", formatTrailLength(0.1, Units.MILES))
        assertEquals("100 ft", formatTrailLength(0.019, Units.MILES)) // 100.3 ft
        assertEquals("420 ft", formatTrailLength(0.08, Units.MILES)) // 422 ft
        assertEquals("2.3 km", formatTrailLength(1.4, Units.KILOMETRES))
        assertEquals("30 m", formatTrailLength(0.019, Units.KILOMETRES)) // 30.6 m
    }

    @Test
    fun mercatorOffsets() {
        val east = mercatorOffsetDp(-122.5, 47.6, -121.5, 47.6, 10.0)
        assertEquals(512.0 * 1024 / 360, east.x, 1e-6)
        assertEquals(0.0, east.y, 1e-9)
        val north = mercatorOffsetDp(-122.5, 47.6, -122.5, 47.61, 15.0)
        assertTrue(north.y < 0) // north is up
        assertEquals(0.0, north.x, 1e-9)
    }

    @Test
    fun coveredNeverReadsAsDoneEarly() {
        assertEquals("0.5" to "0.6 mi", formatCovered(0.999, 0.6, Units.MILES)) // 0.5994 would round to 0.6
        assertEquals("0.6" to "0.6 mi", formatCovered(1.0, 0.6, Units.MILES))
        assertEquals("0.3" to "0.4 mi", formatCovered(0.83, 0.36, Units.MILES)) // 0.299 → nearest tenth
        assertEquals("0.0" to "1.4 mi", formatCovered(0.0, 1.4, Units.MILES))
        assertEquals("210" to "420 ft", formatCovered(0.5, 0.08, Units.MILES))
    }

    @Test
    fun elevationFollowsUnits() {
        assertEquals("21 ft", formatElevation(6.4, Units.MILES))
        assertEquals("6 m", formatElevation(6.4, Units.KILOMETRES))
    }

    @Test
    fun islandCreditFromSegmentFlags() {
        fun seg(id: String, included: Boolean) = SegmentGeom(id, 10.0, included, emptyList(), listOf(listOf(0.0, 0.0), listOf(0.001, 0.0)))
        val d = Dataset(
            trails = listOf(
                TrailMeta("full", "Full", listOf("s1", "s2"), 0.1),
                TrailMeta("part", "Part", listOf("s1", "s3"), 0.1),
                TrailMeta("none", "None", listOf("s3"), 0.1),
            ),
            islandTotalM = 20.0,
            segments = listOf(seg("s1", true), seg("s2", true), seg("s3", false)),
        )
        assertEquals(IslandCredit.FULL, d.islandCredit("full"))
        assertEquals(IslandCredit.PARTIAL, d.islandCredit("part"))
        assertEquals(IslandCredit.NONE, d.islandCredit("none"))
        assertEquals(2, d.trailLines("part").size)
    }

    @Test
    fun offIslandDistance() {
        val inside = org.maplibre.spatialk.geojson.Position(longitude = -122.53, latitude = 47.64)
        assertEquals(0.0, distanceOutsideM(inside, IslandBounds))
        val seattle = org.maplibre.spatialk.geojson.Position(longitude = -122.33, latitude = 47.60)
        assertTrue(distanceOutsideM(seattle, IslandBounds) > 3_000)
    }
}
