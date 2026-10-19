package com.bainbridgetrails.coverage

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometryTest {
    private fun haversine(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val r = 6_371_008.8
        val p1 = lat1 * PI / 180
        val p2 = lat2 * PI / 180
        val dp = p2 - p1
        val dl = (lon2 - lon1) * PI / 180
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(h))
    }

    @Test
    fun projectionDistanceMatchesHaversine() {
        val proj = LocalProjection()
        val a = proj.toXy(-122.55, 47.60)
        val b = proj.toXy(-122.50, 47.68)
        val d = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
        val h = haversine(-122.55, 47.60, -122.50, 47.68)
        assertTrue(abs(d - h) / h < 0.001, "d=$d h=$h")
    }

    @Test
    fun projectionRoundTrip() {
        val proj = LocalProjection()
        val xy = proj.toXy(-122.51, 47.66)
        val (lon, lat) = proj.toLonLat(xy)
        assertEquals(-122.51, lon, 1e-9)
        assertEquals(47.66, lat, 1e-9)
    }

    @Test
    fun distanceToSegmentEndpointsAndInterior() {
        val a = Xy(0.0, 0.0)
        val b = Xy(10.0, 0.0)
        assertEquals(3.0, distanceToSegment(Xy(5.0, 3.0), a, b), 1e-9)
        assertEquals(5.0, distanceToSegment(Xy(13.0, 4.0), a, b), 1e-9)
        assertEquals(5.0, distanceToSegment(Xy(-3.0, -4.0), a, b), 1e-9)
    }

    @Test
    fun axialDiffIgnoresDirection() {
        assertEquals(0.0, axialDiffDeg(10.0, 190.0))
        assertEquals(80.0, axialDiffDeg(0.0, 100.0))
    }

    @Test
    fun resampleCountAndLengths() {
        val ticks = resample(listOf(Xy(0.0, 0.0), Xy(23.0, 0.0)), 5.0)
        assertEquals(5, ticks.size)
        assertEquals(23.0, ticks.sumOf { it.lengthM }, 1e-9)
    }

    @Test
    fun resampleShortLineOneTick() {
        val ticks = resample(listOf(Xy(0.0, 0.0), Xy(0.0, 2.0)), 5.0)
        assertEquals(1, ticks.size)
        assertEquals(2.0, ticks[0].lengthM)
    }
}
