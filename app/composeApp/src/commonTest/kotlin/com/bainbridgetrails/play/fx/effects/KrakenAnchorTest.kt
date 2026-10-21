package com.bainbridgetrails.play.fx.effects

import com.bainbridgetrails.play.fx.FxProjection
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.KrakenTimeline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KrakenAnchorTest {
    // A N–S shore road at Manitou (lon −122.5050), the Sound to the east.
    private val road = listOf(GeoPoint(-122.5050, 47.6590), GeoPoint(-122.5050, 47.6605), GeoPoint(-122.5050, 47.6620))

    private fun metres(a: GeoPoint, b: GeoPoint): Double =
        hypot((a.lon - b.lon) * 111_195.0 * cos(a.lat * PI / 180.0), (a.lat - b.lat) * 111_195.0)

    /** Distance from [p] to the polyline [line] (metres, local equirectangular). */
    private fun toLine(p: GeoPoint, line: List<GeoPoint>): Double {
        val k = 111_195.0 * cos(p.lat * PI / 180.0)
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val ax = (line[i].lon - p.lon) * k; val ay = (line[i].lat - p.lat) * 111_195.0
            val bx = (line[i + 1].lon - p.lon) * k; val by = (line[i + 1].lat - p.lat) * 111_195.0
            val dx = bx - ax; val dy = by - ay
            val u = (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(ax + dx * u, ay + dy * u))
        }
        return best
    }

    @Test
    fun `anchor is the water point ≥ 60 m offshore`() {
        // Content's water point 30 m off the road: pushed out to 60 m on the same (water) side.
        val near = GeoPoint(-122.5046, 47.6606)
        val a = KrakenAttack.anchor(near, road)
        assertTrue(toLine(a, road) >= 60.0 - 0.5, "≥ 60 m offshore: ${toLine(a, road)}")
        assertTrue(a.lon > road[0].lon, "stays on the water side (east)")
        // A water point already offshore is the anchor, unchanged.
        val far = GeoPoint(-122.5026, 47.6604)
        assertTrue(toLine(far, road) > 60.0)
        assertEquals(far, KrakenAttack.anchor(far, road))
        // The intro's anchor comes from the attack's water instance, not the road midpoint vertex.
        assertTrue(metres(a, road[1]) >= 60.0 - 0.5)
    }

    @Test
    fun `grip targets sit on the road 25 m apart`() {
        val anchor = KrakenAttack.anchor(GeoPoint(-122.5030, 47.6606), road)
        val targets = KrakenAttack.targets(anchor, road, count = 3)
        assertEquals(3, targets.size)
        for (t in targets) assertTrue(toLine(t, road) < 0.5, "on the road")
        assertEquals(25.0, metres(targets[0], targets[1]), 0.5)
        assertEquals(25.0, metres(targets[1], targets[2]), 0.5)
    }

    @Test
    fun `extent covers rig at z18`() {
        val anchor = KrakenAttack.anchor(GeoPoint(-122.5030, 47.6606), road)
        val targets = KrakenAttack.targets(anchor, road, count = 3)
        val extent = KrakenAttack.extentDp(anchor, targets, zoom = 18.0)
        val half = extent / 2f
        for (t in targets) {
            val dx = FxProjection.offsetXDp(anchor.lon, t.lon, 18.0) * KrakenTimeline.OVERSHOOT
            val dy = FxProjection.offsetYDp(anchor.lat, t.lat, 18.0) * KrakenTimeline.OVERSHOOT
            // The tip plus its widest stroke, curl and splash margin stays inside the box.
            assertTrue(abs(dx) + KrakenAttack.MARGIN_DP <= half && abs(dy) + KrakenAttack.MARGIN_DP <= half, "target ($dx, $dy) outside ±$half")
        }
        // The rig is the whole effect: at z18 the anchor is ≥ 60 m (≈ 300 dp) from the road.
        assertTrue(half >= FxProjection.metresToDp(60.0, anchor.lat, 18.0))
        // Lower zooms need no more than z18.
        assertTrue(KrakenAttack.extentDp(anchor, targets, zoom = 15.0) <= extent)
    }

    @Test
    fun `intro timeline matches the layer grip and reveal`() {
        assertEquals(KrakenInk.introTimeline, KrakenTimeline.attack)
        for (t in 0..2_600 step 25) {
            assertEquals(KrakenInk.gripAt(t), KrakenTimeline.grip(t), 1e-6f, "grip t=$t")
            assertEquals(KrakenInk.revealAt(t), KrakenTimeline.reveal(t), 1e-6f, "reveal t=$t")
        }
    }
}
