package com.bainbridgetrails.play.fx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FxProjectionTest {
    private val ferry = GeoPoint(-122.51283, 47.62371)           // Bainbridge terminal reference point
    private val points = listOf(
        GeoPoint(-122.5194, 47.6253),                              // Winslow
        GeoPoint(-122.5704, 47.6721),                              // Frog Rock area
        GeoPoint(-122.4977, 47.5853),                              // Fort Ward / south end
        GeoPoint(-122.5532, 47.7020),                              // north end
    )

    /** Hand formula: x = (λ+180)/360, y = (1 − ln(tan φ + sec φ)/π)/2, both × 512·2^z. */
    private fun handX(lon: Double, z: Double) = (lon + 180.0) / 360.0 * 512.0 * 2.0.pow(z)
    private fun handY(lat: Double, z: Double): Double {
        val phi = lat * PI / 180.0
        return (1 - ln(tan(phi) + 1 / cos(phi)) / PI) / 2 * 512.0 * 2.0.pow(z)
    }

    @Test
    fun `offsets match hand-computed Web Mercator at z12 z15 z18`() {
        val out = FloatArray(points.size * 2)
        for (z in listOf(12.0, 15.0, 18.0)) {
            FxProjection.offsetsDp(ferry, points, z, out)
            points.forEachIndexed { i, q ->
                val wx = handX(q.lon, z) - handX(ferry.lon, z)
                val wy = handY(q.lat, z) - handY(ferry.lat, z)
                assertTrue(abs(FxProjection.offsetXDp(ferry.lon, q.lon, z) - wx) < 1e-6, "x z$z #$i")
                assertTrue(abs(FxProjection.offsetYDp(ferry.lat, q.lat, z) - wy) < 1e-6, "y z$z #$i")
                assertEquals(wx.toFloat(), out[2 * i], abs(wx.toFloat()) * 1e-6f + 1e-3f)
                assertEquals(wy.toFloat(), out[2 * i + 1], abs(wy.toFloat()) * 1e-6f + 1e-3f)
            }
        }
        // North is up: a point north of the anchor has a negative y offset; east has positive x.
        FxProjection.offsetsDp(ferry, listOf(GeoPoint(ferry.lon + 0.01, ferry.lat + 0.01)), 15.0, out)
        assertTrue(out[0] > 0f && out[1] < 0f)
    }

    /** Verbatim copy of composeApp map/MapLogic.kt `mercatorOffsetDp` (WORLD_DP = 512.0); :play cannot depend on it. */
    private fun mapLogicMercatorOffsetDp(tapLon: Double, tapLat: Double, lon: Double, lat: Double, zoom: Double): Pair<Double, Double> {
        val world = 512.0 * 2.0.pow(zoom)
        fun x(l: Double) = (l + 180.0) / 360.0
        fun y(p: Double): Double {
            val s = sin(p * PI / 180.0)
            return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
        }
        return Pair((x(lon) - x(tapLon)) * world, (y(lat) - y(tapLat)) * world)
    }

    @Test
    fun `parity with MapLogic mercatorOffsetDp within 1e-9`() {
        for (z in listOf(10.0, 12.0, 13.5, 15.0, 16.37, 18.0, 20.0)) {
            for (q in points) {
                val (ex, ey) = mapLogicMercatorOffsetDp(ferry.lon, ferry.lat, q.lon, q.lat, z)
                assertTrue(abs(FxProjection.offsetXDp(ferry.lon, q.lon, z) - ex) <= 1e-9, "x z$z")
                assertTrue(abs(FxProjection.offsetYDp(ferry.lat, q.lat, z) - ey) <= 1e-9, "y z$z")
            }
        }
    }

    @Test
    fun `metresToDp at 47 point 6 deg`() {
        // 512·2^z dp per 2πR·cos φ metres (R = 6 378 137 m): 100 m at z15 ≈ 62.0857 dp; 25 m at z18 ≈ 124.1714 dp.
        assertEquals(62.08568636304259, FxProjection.metresToDp(100.0, 47.6, 15.0), 1e-9)
        assertEquals(124.17137272608518, FxProjection.metresToDp(25.0, 47.6, 18.0), 1e-9)
    }
}
