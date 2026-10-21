package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Place
import com.bainbridgetrails.play.config.Placement
import com.bainbridgetrails.play.config.TestPack
import com.bainbridgetrails.play.config.TestPack.replace
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** schedule.md §2.1–2.2: same inputs → same island; editing item B never moves item A; the salt only moves personal items. */
class DeterminismTest {
    private val pack = TestPack.base.copy(epoch = LocalDate(2026, 10, 29))
    private val start = LocalDate(2027, 1, 4)
    private fun days(n: Int) = (0 until n).map { start.plus(it, DateTimeUnit.DAY) }

    @Test
    fun `same inputs give identical plans`() {
        val a = PlayScheduler(pack, TestPack.index, null, null)
        val b = PlayScheduler(pack, TestPack.index, null, null)
        for (goal in Goal.entries) for (d in days(30)) assertEquals(a.dayPlan(d, goal), b.dayPlan(d, goal), "$goal $d")
        // order of evaluation does not matter (memoized draws)
        val c = PlayScheduler(pack, TestPack.index, null, null)
        for (d in days(30).reversed()) assertEquals(a.dayPlan(d, Goal.roads), c.dayPlan(d, Goal.roads), "$d reversed")
    }

    @Test
    fun `editing item B leaves A`() {
        val edited = pack
            .copy(places = pack.places + ("frog-2" to Place(47.70, -122.53, radiusM = 60)))
            .replace(TestPack.egg.copy(where = mapOf(Goal.roads to Placement(at = "place:frog-2"))))
            .replace(TestPack.challenge.copy(marks = 150))
        val a = PlayScheduler(pack, TestPack.index, null, null)
        val b = PlayScheduler(edited, TestPack.index, null, null)
        var compared = 0
        for (d in days(28)) {
            val pa = a.dayPlan(d, Goal.roads).occurrences.filter { it.itemId == "treasure-weekly-roads" || it.itemId == "kraken-roads" }
            val pb = b.dayPlan(d, Goal.roads).occurrences.filter { it.itemId == "treasure-weekly-roads" || it.itemId == "kraken-roads" }
            assertEquals(pa, pb, "$d")
            compared += pa.size
        }
        assertTrue(compared >= 28, "treasures were compared ($compared)")
        assertNotEquals(
            a.dayPlan(start, Goal.roads).occurrences.first { it.itemId == "egg-frog" }.placements,
            b.dayPlan(start, Goal.roads).occurrences.first { it.itemId == "egg-frog" }.placements,
        )
    }

    @Test
    fun `a different salt changes only personal items`() {
        val config = pack.replace(TestPack.owl.copy(enabled = true))
        val s = PlayScheduler(config, TestPack.index, null, null)
        var personalMoved = false
        for (d in days(7)) {
            val p1 = s.dayPlan(d, Goal.trails, salt = 1uL)
            val p2 = s.dayPlan(d, Goal.trails, salt = 2uL)
            assertEquals(p1.occurrences.filter { it.scope.name == "island" }, p2.occurrences.filter { it.scope.name == "island" }, "$d")
            val o1 = p1.occurrences.single { it.itemId == "lore-owl" }
            val o2 = p2.occurrences.single { it.itemId == "lore-owl" }
            if (o1.placements != o2.placements) personalMoved = true
        }
        assertTrue(personalMoved, "the owl moves with the salt")
    }
}
