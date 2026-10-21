package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.TestPack
import com.bainbridgetrails.play.config.TestPack.withPool
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Branch review round 1 P1-8: a kraken placement's `#n` is the stretch's position in the coast pool as written (the
 * Worker's `rotationStretches` indexes the unfiltered `DERIVED[dataset].coasts` list), so a stretch that does not
 * resolve on this device leaves a gap instead of shifting the later keys onto the wrong stretches.
 */
class KrakenPlacementIndexTest {
    private val thu = LocalDate(2026, 10, 29)

    private fun kraken(vararg members: String): List<ResolvedPlacement> {
        val pack = TestPack.base.withPool("coast-k1", *members, goal = Goal.roads)
        return PlayScheduler(pack, TestPack.index, null, null).dayPlan(thu, Goal.roads)
            .occurrences.single { it.itemId == "kraken-roads" }.placements
    }

    @Test
    fun unresolvedStretchKeepsTheLaterIndices() {
        val all = kraken("stretch:road-safe-coast", "stretch:road-safe-a")
        assertEquals(listOf("kraken-roads@2026-10-29#0", "kraken-roads@2026-10-29#1"), all.map { it.key })

        val gap = kraken("stretch:road-not-on-this-device", "stretch:road-safe-coast", "stretch:road-safe-a")
        assertEquals(listOf("kraken-roads@2026-10-29#1", "kraken-roads@2026-10-29#2"), gap.map { it.key })
        assertEquals(listOf(1, 2), gap.map { it.index })
        assertEquals(listOf("stretch:road-safe-coast", "stretch:road-safe-a"), gap.map { it.ref })
    }

    @Test
    fun keyWidthFollowsThePoolNotTheResolvedCount() {
        val members = arrayOf("stretch:road-safe-coast") + Array(9) { "stretch:road-missing-$it" } + "stretch:road-safe-a"
        assertEquals(listOf("kraken-roads@2026-10-29#00", "kraken-roads@2026-10-29#10"), kraken(*members).map { it.key })
    }
}
