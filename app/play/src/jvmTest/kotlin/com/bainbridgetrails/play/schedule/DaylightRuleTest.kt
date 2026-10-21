package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.TestPack
import com.bainbridgetrails.play.config.TestPack.replace
import com.bainbridgetrails.play.time.IslandTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** schedule.md §3.4 #1 (PD-5): nothing is resolvable outside [civil dawn, civil dusk] ∩ 06:00–21:00. */
class DaylightRuleTest {
    private fun days2027() = (0 until 365).map { LocalDate(2027, 1, 1).plus(it, DateTimeUnit.DAY) }

    private fun assertInsideDaylight(s: PlayScheduler, goal: Goal): Int {
        var spans = 0
        for (d in days2027()) {
            val plan = s.dayPlan(d, goal)
            val lo = maxOf(plan.sun.dawn!!, IslandTime.instantOf(d, LocalTime(6, 0)))
            val hi = minOf(plan.sun.dusk!!, IslandTime.instantOf(d, LocalTime(21, 0)))
            for (o in plan.occurrences) for (span in o.resolvable + o.daySpans) {
                assertTrue(span.start >= lo && span.end <= hi, "$d ${o.key}: $span outside [$lo, $hi]")
                assertTrue(span.start >= o.start && (o.end == null || span.end <= o.end), "$d ${o.key}: $span outside its window")
                spans++
            }
        }
        return spans
    }

    @Test
    fun `no occurrence resolvable outside daylight and play hours in 2027`() {
        val s = RealPack.scheduler()
        for (goal in Goal.entries) assertTrue(assertInsideDaylight(s, goal) > 3000, "$goal spans checked")
    }

    @Test
    fun `no creature outside sunrise plus 30 and sunset minus 30`() {
        val s = PlayScheduler(PACK.replace(TestPack.owl.copy(enabled = true)), TestPack.index, null, null)
        for (d in days2027()) {
            val plan = s.dayPlan(d, Goal.trails)
            if (plan.quietDay) continue
            val owl = plan.occurrences.single { it.itemId == "lore-owl" }
            for (span in owl.resolvable) {
                assertTrue(span.start >= plan.sun.sunrise!! + 30.minutes && span.end <= plan.sun.sunset!! - 30.minutes, "$d $span")
            }
            assertTrue(owl.resolvable.isNotEmpty(), "$d")
        }
    }

    @Test
    fun `overnight items flagged sleeping`() {
        val s = RealPack.scheduler()
        val night = Instant.parse("2027-01-06T07:00:00Z") // Tue 2027-01-05 23:00 PST
        val treasure = s.agenda(night, PlayContext(Goal.roads)).active.single { it.occurrence.itemId == "treasure-weekly-roads" }
        assertTrue(treasure.sleeping)
        val noon = Instant.parse("2027-01-05T20:00:00Z")
        assertEquals(Gate.OPEN, s.agenda(noon, PlayContext(Goal.roads)).active.single { it.occurrence.itemId == "treasure-weekly-roads" }.gate)
        val dawnish = Instant.parse("2027-01-05T13:30:00Z") // 05:30 PST: after the 05:00 Monday start, before dawn
        assertTrue(s.agenda(dawnish, PlayContext(Goal.roads)).active.single { it.occurrence.itemId == "treasure-weekly-roads" }.sleeping)
        // view-only cards are never "sleeping": they are not sensed at all
        assertTrue(s.agenda(night, PlayContext(Goal.roads)).active.filter { it.occurrence.itemId == "lore-yama" }.all { it.gate == Gate.VIEW })
    }
}
