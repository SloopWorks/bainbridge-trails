package com.bainbridgetrails.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI review 2026-10-21 P2-1 (UX-9): the medallion mark grows with the font scale up to 1.3× and no further, like the
 * Me hero's `statHero` cap, so "4,101" / "16.1" never clip in the 64dp medallion at font scale 2.0. At ≤ 1.3 the
 * factor is exactly 1 (roads medallions render unchanged).
 */
class MilestoneMarkScaleTest {
    @Test
    fun `no change at or below 1_3`() {
        for (scale in listOf(0.85f, 1f, 1.15f, 1.3f)) assertEquals(1f, medallionMarkScale(scale), "scale $scale")
    }

    @Test
    fun `effective growth is capped at 1_3 above it`() {
        for (scale in listOf(1.5f, 1.8f, 2f)) {
            assertEquals(1.3f, medallionMarkScale(scale) * scale, 1e-5f, "scale $scale")
        }
        assertEquals(0.65f, medallionMarkScale(2f), 1e-5f)
    }
}
