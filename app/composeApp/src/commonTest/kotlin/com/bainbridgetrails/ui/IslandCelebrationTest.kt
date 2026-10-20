package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.ProgressSnapshot
import com.bainbridgetrails.coverage.TrailProgress
import com.bainbridgetrails.coverage.TrailStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IslandCelebrationTest {
    private fun t(id: String, status: TrailStatus) = TrailProgress(id, id, if (status == TrailStatus.COMPLETE) 0.9 else 0.3, status, 1.0)
    private fun snap(island: Double, vararg trails: TrailProgress) = ProgressSnapshot(trails.toList(), island, trails.count { it.status == TrailStatus.COMPLETE }, 0.0)
    private val counted = setOf("a", "b")

    @Test
    fun wholeWhenEveryIncludedMetreIsCoveredOrEveryCountedTrailIsComplete() {
        assertTrue(IslandCelebration.isWhole(snap(1.0, t("a", TrailStatus.PARTWAY)), counted))
        // Non-counted "nc" unfinished doesn't matter.
        assertTrue(IslandCelebration.isWhole(snap(0.93, t("a", TrailStatus.COMPLETE), t("b", TrailStatus.COMPLETE), t("nc", TrailStatus.PARTWAY)), counted))
        assertFalse(IslandCelebration.isWhole(snap(0.93, t("a", TrailStatus.COMPLETE), t("b", TrailStatus.NEARLY_DONE)), counted))
        assertFalse(IslandCelebration.isWhole(snap(0.0), counted))
    }

    @Test
    fun showsOnlyForTheWalkThatMadeTheIslandWhole() {
        val whole = snap(0.95, t("a", TrailStatus.COMPLETE), t("b", TrailStatus.COMPLETE), t("nc", TrailStatus.COMPLETE))
        assertTrue(IslandCelebration.show(island = false, whole, listOf("b"), counted)) // completed the last counted trail
        assertFalse(IslandCelebration.show(island = false, whole, listOf("nc"), counted)) // already whole: a normal celebration
        assertTrue(IslandCelebration.show(island = true, snap(0.2), emptyList(), counted)) // crossing flag / debug preview
        assertFalse(IslandCelebration.show(island = false, snap(0.5, t("a", TrailStatus.COMPLETE), t("b", TrailStatus.PARTWAY)), listOf("a"), counted))
    }
}
