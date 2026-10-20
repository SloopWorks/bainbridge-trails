package com.bainbridgetrails.graph

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CelebrationTest {
    @Test
    fun islandWholeOnlyWhenThisWalkCrossedOneHundredPercent() {
        assertTrue(AppGraph.Celebration.islandReached(before = 0.98, after = 1.0))
        assertFalse(AppGraph.Celebration.islandReached(before = 0.5, after = 0.97))
        assertFalse(AppGraph.Celebration.islandReached(before = 1.0, after = 1.0)) // already whole
    }

    @Test
    fun floatingPointSumsJustUnderOneStillCount() {
        // Summing per-segment fractions can land a hair under 1.0 when every segment is covered.
        assertTrue(AppGraph.Celebration.islandReached(before = 0.9, after = 1.0 - 1e-12))
        assertFalse(AppGraph.Celebration.islandReached(before = 1.0 - 1e-12, after = 1.0)) // was already whole
        assertFalse(AppGraph.Celebration.islandReached(before = 0.9, after = 1.0 - 1e-6))
    }
}
