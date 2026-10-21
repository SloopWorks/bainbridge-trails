package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.TestPack
import com.bainbridgetrails.play.config.TestPack.plus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** schedule.md §3.4 #3: at most one hero active at any instant, at most one hero start per island day, loser suppressed. */
class HeroConflictTest {
    private val start = LocalDate(2027, 1, 1)

    /** Every hero occurrence (by key) seen in [days] day plans of [goal]. */
    private fun heroes(s: PlayScheduler, goal: Goal, days: Int = 400): Map<String, Occurrence> {
        val out = LinkedHashMap<String, Occurrence>()
        for (i in 0 until days) for (o in s.dayPlan(start.plus(i, DateTimeUnit.DAY), goal).occurrences) if (o.hero) out[o.key] = o
        return out
    }

    private fun assertNoOverlap(occs: Collection<Occurrence>) {
        val sorted = occs.sortedBy { it.start }
        for ((a, b) in sorted.zipWithNext()) {
            assertTrue(a.end!! <= b.start, "${a.key} overlaps ${b.key}")
            assertTrue(a.key.substringAfter('@') != b.key.substringAfter('@'), "two heroes start on ${a.key.substringAfter('@')}")
        }
    }

    @Test
    fun `at most 1 hero active at any instant over 400 days`() {
        val s = PlayScheduler(PACK, TestPack.index, null, null)
        val hs = heroes(s, Goal.roads)
        assertEquals(58, hs.size, "one attack a week: the 2026-12-31 attack, 52 in 2027, 5 to 2028-02-04")
        assertNoOverlap(hs.values)
    }

    @Test
    fun `a conflicting hero yields to the higher priority`() {
        val oct31 = TestPack.kraken.copy(id = "kraken-oct31", priority = 10, yields = true)
        val s = PlayScheduler(PACK.plus(oct31), TestPack.index, null, null)
        val hs = heroes(s, Goal.roads, days = 60)
        assertTrue(hs.keys.none { it.startsWith("kraken-oct31@") }, "the lower priority attack is suppressed, not shifted")
        assertNoOverlap(hs.values)
        // equal priority: the lower id wins
        val twin = TestPack.kraken.copy(id = "kraken-aaa", yields = true)
        val s2 = PlayScheduler(PACK.plus(twin), TestPack.index, null, null)
        val hs2 = heroes(s2, Goal.roads, days = 30)
        assertTrue(hs2.keys.all { it.startsWith("kraken-aaa@") }, hs2.keys.toString())
    }
}
