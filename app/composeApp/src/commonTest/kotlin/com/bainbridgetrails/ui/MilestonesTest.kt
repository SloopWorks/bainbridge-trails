package com.bainbridgetrails.ui

import com.bainbridgetrails.data.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MilestonesTest {
    private val mile = 1609.344
    private val total = 160 * mile // island total: fractions below are miles / 160

    private fun walk(
        beforeMi: Double, afterMi: Double, newly: List<String> = emptyList(), completedAfter: Int = newly.size,
        cats: Map<String, String> = emptyMap(), done: Map<String, Int> = emptyMap(), counted: Map<String, Int> = emptyMap(),
        units: Units = Units.MILES, island: Boolean = false,
    ) = Milestones.crossed(
        WalkMilestoneInput(beforeMi * mile / total, afterMi * mile / total, total, units, newly, completedAfter, { cats[it] }, done, counted, island),
    )

    @Test fun distanceLadderIsOneThenEveryFive() {
        assertNull(Milestones.distanceReached(0.99))
        assertEquals(1, Milestones.distanceReached(1.0))
        assertEquals(1, Milestones.distanceReached(4.99))
        assertEquals(5, Milestones.distanceReached(5.0))
        assertEquals(15, Milestones.distanceReached(17.3))
        assertEquals(1, Milestones.nextDistance(0.4))
        assertEquals(5, Milestones.nextDistance(1.2))
        assertEquals(20, Milestones.nextDistance(15.0))
    }

    @Test fun e12_bannerOnCrossingFiveThenNothing() {
        assertEquals(listOf<Milestone>(Milestone.Distance(5, Units.MILES)), walk(4.8, 5.2))
        assertTrue(walk(5.2, 5.5).isEmpty())
    }

    @Test fun firstMileAndHighestOfSeveralCrossed() {
        assertEquals(listOf<Milestone>(Milestone.Distance(1, Units.MILES)), walk(0.0, 1.1))
        assertEquals(listOf<Milestone>(Milestone.Distance(10, Units.MILES)), walk(4.0, 11.0))
    }

    @Test fun kilometresUseTheirOwnLadder() {
        // 0.6 mi → 0.966 km, 0.7 mi → 1.127 km: the first km.
        assertEquals(listOf<Milestone>(Milestone.Distance(1, Units.KILOMETRES)), walk(0.6, 0.7, units = Units.KILOMETRES))
    }

    @Test fun roadsLadderFiresOnFirstAndTenth() {
        val first = walk(0.1, 0.2, newly = listOf("a"), completedAfter = 1, cats = mapOf("a" to "residential"), done = mapOf("residential" to 1), counted = mapOf("residential" to 345))
        assertEquals(listOf<Milestone>(Milestone.Roads(1)), first)
        val tenth = walk(2.0, 2.1, newly = listOf("j", "k"), completedAfter = 11, cats = mapOf("j" to "residential", "k" to "residential"), done = mapOf("residential" to 11), counted = mapOf("residential" to 345))
        assertEquals(listOf<Milestone>(Milestone.Roads(10)), tenth)
        val none = walk(2.0, 2.1, newly = listOf("j"), completedAfter = 12, cats = mapOf("j" to "residential"), done = mapOf("residential" to 12), counted = mapOf("residential" to 345))
        assertTrue(none.isEmpty())
    }

    @Test fun e13_typeMilestoneWhenTheLastOfATypeCompletes_rankedFirst() {
        val m = walk(
            9.0, 10.2, newly = listOf("sr305"), completedAfter = 40,
            cats = mapOf("sr305" to "state_route"), done = mapOf("state_route" to 2), counted = mapOf("state_route" to 2),
        )
        assertEquals(listOf(Milestone.TypeComplete("state_route"), Milestone.Distance(10, Units.MILES)), m)
        // Completing another type's road doesn't fire state routes again.
        val other = walk(10.2, 10.3, newly = listOf("r"), completedAfter = 41, cats = mapOf("r" to "residential"), done = mapOf("state_route" to 2, "residential" to 3), counted = mapOf("state_route" to 2, "residential" to 345))
        assertTrue(other.isEmpty())
    }

    @Test fun islandLeads() {
        val m = walk(159.0, 160.0, island = true)
        assertEquals(Milestone.Island, m.first())
    }

    @Test fun ladderAndStrip() {
        val ladder = Milestones.ladder(
            coveredM = 6.2 * mile, units = Units.MILES, completed = 12, counted = 430,
            completedByCategory = mapOf("state_route" to 2), countedByCategory = mapOf("state_route" to 2, "arterial" to 28),
        )
        val d = ladder.getValue(Milestones.LADDER_DISTANCE)
        assertEquals(listOf(1, 5, 10, 15, 20), d.map { (it.milestone as Milestone.Distance).value })
        assertEquals(listOf(MilestoneState.EARNED, MilestoneState.EARNED, MilestoneState.NEXT, MilestoneState.LOCKED, MilestoneState.LOCKED), d.map { it.state })
        val r = ladder.getValue(Milestones.LADDER_ROADS)
        assertEquals(MilestoneState.NEXT, r.first { (it.milestone as Milestone.Roads).count == 25 }.state)
        val strip = Milestones.strip(ladder)
        // Last earned (max 2: type first, then roads) + next distance + next roads.
        assertEquals(
            listOf(Milestone.TypeComplete("state_route"), Milestone.Roads(10), Milestone.Distance(10, Units.MILES), Milestone.Roads(25)),
            strip.map { it.milestone },
        )
        assertEquals(0.62, strip[2].fraction, 1e-9)
    }

    @Test fun roadsLadderStopsAtTheDatasetSize() {
        val ladder = Milestones.ladder(0.0, Units.MILES, 0, 30, emptyMap(), emptyMap())
        assertEquals(listOf(1, 10, 25), ladder.getValue(Milestones.LADDER_ROADS).map { (it.milestone as Milestone.Roads).count })
        assertNull(Milestones.nextRoads(25, 30))
    }

    @Test fun distanceLadderStopsAtTheIslandTotal() {
        // 162.5 mi island: the ladder ends at 160; nothing beyond the island total is offered as next.
        val near = Milestones.ladder(158.0 * mile, Units.MILES, 0, 430, emptyMap(), emptyMap(), maxDistance = 162)
        val d = near.getValue(Milestones.LADDER_DISTANCE).map { (it.milestone as Milestone.Distance).value }
        assertEquals(160, d.last())
        val all = Milestones.ladder(162.5 * mile, Units.MILES, 0, 430, emptyMap(), emptyMap(), maxDistance = 162)
        assertTrue(all.getValue(Milestones.LADDER_DISTANCE).all { it.state == MilestoneState.EARNED })
    }
}
