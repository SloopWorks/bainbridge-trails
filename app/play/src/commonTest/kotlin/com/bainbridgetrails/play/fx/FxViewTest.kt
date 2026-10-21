package com.bainbridgetrails.play.fx

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FxViewTest {
    private val view = FxView(zoom = 15.0, west = -122.52, south = 47.62, east = -122.50, north = 47.64)

    @Test
    fun `contains points, lines and areas inside the padded bounds`() {
        assertTrue(view.contains(FxAnchor.Point(GeoPoint(-122.51, 47.63))))
        assertFalse(view.contains(FxAnchor.Point(GeoPoint(-122.40, 47.63))))
        // 10 % padding: a pin just below the bottom edge still draws its lifted head on screen.
        assertTrue(view.contains(FxAnchor.Point(GeoPoint(-122.51, 47.619))))
        assertTrue(view.contains(FxAnchor.Line(listOf(GeoPoint(-122.60, 47.63), GeoPoint(-122.40, 47.63)))))
        assertFalse(view.contains(FxAnchor.Line(listOf(GeoPoint(-122.60, 47.70), GeoPoint(-122.40, 47.70)))))
        val ring = listOf(GeoPoint(-122.6, 47.5), GeoPoint(-122.4, 47.5), GeoPoint(-122.4, 47.7), GeoPoint(-122.6, 47.7))
        assertTrue(view.contains(FxAnchor.Area(listOf(ring))), "an area covering the view")
        assertTrue(view.contains(FxAnchor.Screen(0.5f, 0.5f)))
    }
}
