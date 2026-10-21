package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Recurrences
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * product §4.3.2 / PD-8: one coast per goal per week by fixed rotation, epoch = the first attack's Thursday 2026-10-29.
 * Roads K5, K2, K6, K4, K7, K3; trails T4, T2, T3 (L4-1: T5 has no ≥ 100 m stretch, so the trails rotation has 3 coasts).
 */
class KrakenRotationTest {
    private val config = RealPack.config
    private fun placement(goal: Goal) = config.items.first { it.id == "kraken-${goal.name}" }.where.getValue(goal)
    private val epoch = LocalDate(2026, 10, 29)
    private fun thu(week: Int) = epoch.plus(7 * week, DateTimeUnit.DAY)

    @Test
    fun `kraken-roads@2026-10-29 is K5`() {
        assertEquals("pool:coast-k5", KrakenRotation.pool(placement(Goal.roads), LocalDate(2026, 10, 29)))
        val plan = RealPack.scheduler().dayPlan(LocalDate(2026, 10, 29), Goal.roads)
        assertNotNull(plan.occurrences.singleOrNull { it.key == "kraken-roads@2026-10-29" })
    }

    @Test
    fun `kraken-roads@2026-11-05 is K2`() {
        assertEquals("pool:coast-k2", KrakenRotation.pool(placement(Goal.roads), LocalDate(2026, 11, 5)))
    }

    @Test
    fun `wraps after 6`() {
        val roads = listOf("k5", "k2", "k6", "k4", "k7", "k3").map { "pool:coast-$it" }
        for (w in 0 until 18) assertEquals(roads[w % 6], KrakenRotation.pool(placement(Goal.roads), thu(w)), "week $w")
        assertEquals("pool:coast-k5", KrakenRotation.pool(placement(Goal.roads), LocalDate(2026, 12, 10)))
        // a date inside the window (Sunday) belongs to its Thursday's coast; before the epoch the rotation runs backwards
        assertEquals(0, KrakenRotation.index(epoch, LocalDate(2026, 11, 1), 6))
        assertEquals(5, KrakenRotation.index(epoch, LocalDate(2026, 10, 22), 6))
    }

    @Test
    fun `trails after 3`() {
        val trails = listOf("t4", "t2", "t3").map { "pool:coast-$it" }
        for (w in 0 until 9) assertEquals(trails[w % 3], KrakenRotation.pool(placement(Goal.trails), thu(w)), "week $w")
        assertEquals("pool:coast-t4", KrakenRotation.pool(placement(Goal.trails), LocalDate(2026, 11, 19)))
    }

    @Test
    fun `stretches of the first attacks with the datasets`() {
        val s = RealPack.withData("KrakenRotationTest")
        val roads = s.dayPlan(LocalDate(2026, 10, 29), Goal.roads).occurrences.single { it.itemId == "kraken-roads" }
        assertEquals(
            listOf(
                "kraken-roads@2026-10-29#0" to "stretch:road-manitou-beach-drive-northeast",
                "kraken-roads@2026-10-29#1" to "stretch:road-northeast-gertie-johnson-road",
            ),
            roads.placements.map { it.key to it.ref },
        )
        val trails = s.dayPlan(LocalDate(2026, 10, 29), Goal.trails).occurrences.single { it.itemId == "kraken-trails" }
        assertEquals(listOf("stretch:trail-fairy-dell-trail", "stretch:trail-schel-chelb-trail"), trails.placements.map { it.ref })
        assertEquals(true, roads.placements.all { it.segmentIds.isNotEmpty() && it.point != null })
    }

    @Test
    fun `window Thu 06_00 to Sun 20_00 across DST`() {
        val rec = config.effectiveWhen(config.items.first { it.id == "kraken-roads" })!!
        fun iso(d: LocalDate) = Recurrences.windowsOn(config, rec, d).single().let { Recurrences.islandIso(it.start) + " " + Recurrences.islandIso(it.end) }
        assertEquals("2027-03-11T06:00-08:00 2027-03-14T20:00-07:00", iso(LocalDate(2027, 3, 11)))
        assertEquals("2027-11-04T06:00-07:00 2027-11-07T20:00-08:00", iso(LocalDate(2027, 11, 4)))
    }
}
