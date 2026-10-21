package com.bainbridgetrails.play.fx

import com.bainbridgetrails.play.fx.effects.KrakenAttack
import com.bainbridgetrails.play.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * e2e run 1 F2 (iOS crash "Can't represent a width of 89917 and height of 89917 in Constraints" under
 * `GeographicLayout.measure`): an overlay part's side never exceeds [FxOverlayExtent.MAX_PX], and a part that would be
 * many times larger is not composed at all (its Layer tier draws it).
 */
class FxOverlayExtentTest {
    @Test
    fun overlayExtentPxIsClamped() {
        assertTrue(FxOverlayExtent.overlayExtentPx(18.0, 50.0) < FxOverlayExtent.MAX_PX, "a 50 m part at z18 is not clamped")
        for (z in listOf(10.0, 14.0, 18.0, 20.0, 22.0)) for (m in listOf(1.0, 100.0, 1_000.0, 6_000.0, 50_000.0)) for (d in listOf(1f, 2f, 3f)) {
            val px = FxOverlayExtent.overlayExtentPx(z, m, density = d)
            assertTrue(px in 0f..FxOverlayExtent.MAX_PX, "z$z ${m}m ×$d → $px")
        }
        assertEquals(FxOverlayExtent.MAX_PX, FxOverlayExtent.overlayExtentPx(18.0, 6_000.0, density = 3f))
    }

    @Test
    fun partSideClampedOrSkipped() {
        assertEquals(240f, FxOverlayExtent.partDp(240f, density = 3f), "a normal rig keeps its side")
        val clamped = assertNotNull(FxOverlayExtent.partDp(2_000f, density = 3f))
        assertTrue(clamped * 3f <= FxOverlayExtent.MAX_PX, "$clamped dp")
        // The crash: 89 917 px at 3× (a water point kilometres from the stretch it grips).
        assertNull(FxOverlayExtent.partDp(89_917f / 3f, density = 3f))
        assertNull(FxOverlayExtent.partDp(Float.NaN, density = 3f))
        assertNull(FxOverlayExtent.partDp(Float.POSITIVE_INFINITY, density = 2f))
    }

    @Test
    fun krakenRigFarFromItsStretchIsSkipped() {
        val water = GeoPoint(lat = 47.6300, lon = -122.5700)
        val road = listOf(GeoPoint(lat = 47.6300, lon = -122.5000), GeoPoint(lat = 47.6310, lon = -122.4990))
        val anchor = KrakenAttack.anchor(water, road)
        val dp = KrakenAttack.extentDp(anchor, KrakenAttack.targets(anchor, road, 3), zoom = 18.0)
        assertNull(FxOverlayExtent.partDp(dp, density = 3f), "a 5 km rig is Layer-tier only ($dp dp)")
    }
}
