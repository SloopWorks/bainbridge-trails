package com.bainbridgetrails.play.geo

import com.bainbridgetrails.play.config.Place
import com.bainbridgetrails.play.fx.FxProjection
import com.bainbridgetrails.play.schedule.FakePlaceIndex
import com.bainbridgetrails.play.schedule.SegRef
import com.bainbridgetrails.play.schedule.TrailheadRef
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the single [GeoPoint] against a silent lat/lon swap: one point, built with named arguments, means the same
 * place to the effects projection, the schedule's place index and geometry, and the pack JSON.
 */
class GeoPointTest {
    private val p = GeoPoint(lat = 47.6, lon = -122.5)
    private val d = 0.01
    private val east = GeoPoint(lat = 47.6, lon = -122.5 + d)
    private val north = GeoPoint(lat = 47.6 + d, lon = -122.5)

    @Test
    fun `FxProjection reads lat as north and lon as east`() {
        val out = FloatArray(4)
        FxProjection.offsetsDp(p, listOf(east, north), 15.0, out)
        assertTrue(out[0] > 0f && abs(out[1]) < 1e-3f, "east point moves +x only: ${out.toList()}")
        assertTrue(abs(out[2]) < 1e-3f && out[3] < 0f, "north point moves -y only: ${out.toList()}")
        assertEquals(FxProjection.offsetXDp(p.lon, east.lon, 15.0).toFloat(), out[0])
        assertEquals(FxProjection.offsetYDp(p.lat, north.lat, 15.0).toFloat(), out[3])
    }

    @Test
    fun `PlaceIndex and GeoMath read the same point the same way`() {
        val index = FakePlaceIndex(emptyList(), trailheads = mapOf("th" to p))
        val back = index.point(TrailheadRef("th"))
        assertEquals(47.6, back?.lat)
        assertEquals(-122.5, back?.lon)
        // A degree of latitude is longer than a degree of longitude at 47.6°N.
        assertTrue(GeoMath.distanceM(p, north) > GeoMath.distanceM(p, east) * 1.4)
        val seg = SegRef.fromLine("s", listOf(p, north))
        assertEquals(47.6 + d / 2, seg.midLat, 1e-9)
        assertEquals(-122.5, seg.midLon, 1e-9)
    }

    @Test
    fun `pack JSON landmark decodes to the same point`() {
        val place = Json.decodeFromString(Place.serializer(), """{"lat":47.7,"lon":-122.6,"landmark":{"lat":47.6,"lon":-122.5}}""")
        assertEquals(p, place.landmark)
    }
}
