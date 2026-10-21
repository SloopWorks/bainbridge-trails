package com.bainbridgetrails.play.claim

import com.bainbridgetrails.play.claim.ClaimFixture.at
import com.bainbridgetrails.play.config.Goal
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** `ch-sweep` (product §4.5, schedule.md §7.3): a walk counts when it **finished** inside Sat 00:00 → Mon 00:00. */
class SweepTest {
    private val ev = ClaimFixture.evaluator()
    private val s = ClaimFixture.scheduler()
    private val sat = LocalDate(2026, 10, 31)
    private val sun = LocalDate(2026, 11, 1)
    private val mon = LocalDate(2026, 11, 2)
    private fun plans(vararg d: LocalDate) = d.map { s.dayPlan(it, Goal.trails) }
    private fun walk(end: Instant, completed: Int) = WalkDone("w-${end.toEpochMilliseconds()}", end.toEpochMilliseconds(), completed)

    @Test
    fun `3 trails Sat+Sun → +100`() {
        val earlier = listOf(walk(at(sat, 10), 1), walk(at(sat, 15), 1))
        val end = at(sun, 11)
        val c = ev.challenges(plans(sun), end, completedThisWalk = 1, earlier = earlier).single()
        assertEquals("ch-sweep@2026-10-31", c.key)
        assertEquals(ClaimKind.challenge, c.kind)
        assertEquals(100, c.marks)
        assertEquals(LocalDate(2026, 10, 26), c.weekId)
        // the walk that reaches 3 claims it; earlier and later walks do not
        assertTrue(ev.challenges(plans(sat), at(sat, 15), 1, earlier.take(1)).isEmpty())
        val all = earlier + walk(end, 1)
        assertTrue(ev.challenges(plans(sun), at(sun, 16), 2, all).isEmpty(), "already reached")
        assertTrue(ev.challenges(plans(sun), end, 1, earlier, found = setOf(c.key)).isEmpty(), "claimed once")
        // a walk last Friday never counts toward this weekend
        assertTrue(ev.challenges(plans(sun), end, 1, listOf(walk(at(LocalDate(2026, 10, 30), 18), 1), earlier[0])).isEmpty())
    }

    @Test
    fun `walk finishing Mon 00_05 does not count`() {
        val earlier = listOf(walk(at(sat, 10), 1), walk(at(sun, 15), 1))
        assertTrue(ev.challenges(plans(sun, mon), at(mon, 0, 5), completedThisWalk = 1, earlier = earlier).isEmpty())
        // the same walk finishing at Sun 23:55 does
        assertEquals(100, ev.challenges(plans(sun), at(sun, 23, 55), 1, earlier).single().marks)
    }

    @Test
    fun `the claim pass carries the challenge its walk reaches`() {
        val earlier = listOf(walk(at(sat, 10), 1), walk(at(sat, 15), 1))
        val end = at(sun, 11)
        val r = ev.evaluate(emptyList(), plans(sun), walkEnd = end, completedThisWalk = 1, earlier = earlier)
        assertEquals(listOf("ch-sweep@2026-10-31"), r.claims.map { it.key })
        assertTrue(ev.evaluate(emptyList(), plans(sun), walkEnd = end, completedThisWalk = 0, earlier = earlier).claims.isEmpty())
        assertTrue(ev.evaluate(emptyList(), plans(sun)).claims.isEmpty(), "no walk end: no challenges")
    }
}
