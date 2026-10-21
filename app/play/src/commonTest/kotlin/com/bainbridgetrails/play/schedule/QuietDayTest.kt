package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.TestPack
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** product §4.6.3 / PD-6: on `exclusion-remembrance` (Mar 30) Play shows only the quiet remembrance card. */
class QuietDayTest {
    private val s = PlayScheduler(PACK, TestPack.index, null, null)
    private val loud = setOf(ItemType.obstacle, ItemType.treasure, ItemType.challenge)

    @Test
    fun `Mar 30 has no kraken treasure or challenge occurrence`() {
        // 2027-03-30 is a Tuesday (inside a treasure week), 2028-03-30 a Thursday (a kraken start), 2030-03-30 a Saturday (ch-sweep)
        for (date in listOf(LocalDate(2027, 3, 30), LocalDate(2028, 3, 30), LocalDate(2030, 3, 30))) for (goal in Goal.entries) {
            val plan = s.dayPlan(date, goal)
            assertTrue(plan.quietDay, "$date")
            assertTrue(plan.occurrences.none { it.type in loud }, "$date $goal: ${plan.occurrences.map { it.key }}")
            assertEquals(listOf("lore-memorial"), plan.occurrences.map { it.itemId }, "$date $goal")
            assertTrue(plan.occurrences.all { it.marks == 0 && it.resolvable.isEmpty() })
        }
    }

    @Test
    fun `the days around Mar 30 are ordinary`() {
        assertFalse(s.dayPlan(LocalDate(2027, 3, 29), Goal.roads).quietDay)
        assertTrue(s.dayPlan(LocalDate(2027, 3, 31), Goal.roads).occurrences.any { it.itemId == "treasure-weekly-roads" })
        assertTrue(s.dayPlan(LocalDate(2028, 3, 31), Goal.roads).occurrences.any { it.key == "kraken-roads@2028-03-30" })
    }

    @Test
    fun `agenda on Mar 30 shows only the remembrance card`() {
        val noon = Instant.parse("2027-03-30T19:00:00Z")
        val a = s.agenda(noon, PlayContext(Goal.roads))
        assertTrue(a.day.quietDay)
        assertEquals(listOf("lore-memorial"), a.active.map { it.occurrence.itemId })
        assertEquals(Gate.VIEW, a.active.single().gate)
    }
}

/** The test pack with an epoch: its permanent items start there (schedule.md §4.4). */
internal val PACK = TestPack.base.copy(epoch = LocalDate(2026, 10, 29))
