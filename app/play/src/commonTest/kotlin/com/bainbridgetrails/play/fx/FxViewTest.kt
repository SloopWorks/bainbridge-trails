package com.bainbridgetrails.play.fx

import com.bainbridgetrails.play.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FxViewTest {
    private val view = FxView(zoom = 15.0, west = -122.52, south = 47.62, east = -122.50, north = 47.64)

    @Test
    fun `contains points, lines and areas inside the padded bounds`() {
        assertTrue(view.contains(FxAnchor.Point(GeoPoint(lat = 47.63, lon = -122.51))))
        assertFalse(view.contains(FxAnchor.Point(GeoPoint(lat = 47.63, lon = -122.40))))
        // 10 % padding: a pin just below the bottom edge still draws its lifted head on screen.
        assertTrue(view.contains(FxAnchor.Point(GeoPoint(lat = 47.619, lon = -122.51))))
        assertTrue(view.contains(FxAnchor.Line(listOf(GeoPoint(lat = 47.63, lon = -122.60), GeoPoint(lat = 47.63, lon = -122.40)))))
        assertFalse(view.contains(FxAnchor.Line(listOf(GeoPoint(lat = 47.70, lon = -122.60), GeoPoint(lat = 47.70, lon = -122.40)))))
        val ring = listOf(GeoPoint(lat = 47.5, lon = -122.6), GeoPoint(lat = 47.5, lon = -122.4), GeoPoint(lat = 47.7, lon = -122.4), GeoPoint(lat = 47.7, lon = -122.6))
        assertTrue(view.contains(FxAnchor.Area(listOf(ring))), "an area covering the view")
        assertTrue(view.contains(FxAnchor.Screen(0.5f, 0.5f)))
    }
}
