package com.bainbridgetrails.play.claim

import com.bainbridgetrails.play.claim.ClaimFixture.MANITOU_M
import com.bainbridgetrails.play.claim.ClaimFixture.at
import com.bainbridgetrails.play.claim.ClaimFixture.chest
import com.bainbridgetrails.play.claim.ClaimFixture.frogRock
import com.bainbridgetrails.play.claim.ClaimFixture.inMemorialZone
import com.bainbridgetrails.play.claim.ClaimFixture.manitouEnd
import com.bainbridgetrails.play.claim.ClaimFixture.manitouStart
import com.bainbridgetrails.play.claim.ClaimFixture.passBy
import com.bainbridgetrails.play.claim.ClaimFixture.plan
import com.bainbridgetrails.play.claim.ClaimFixture.thu
import com.bainbridgetrails.play.claim.ClaimFixture.walk
import com.bainbridgetrails.play.geo.GeoMath
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** product §3.6, §4; PF5, PF7, PF8, PF10. Keys are the scheduler's for [ClaimFixture]'s pack. */
class ClaimEvaluatorTest {
    private val ev = ClaimFixture.evaluator()
    private val week = LocalDate(2026, 10, 26)
    private val treasureKey = "treasure-test@2026-10-26#0"
    private val manitouKey = "kraken-roads@2026-10-29#0"
    private val noon = at(thu, 12)
    private val thuPlan = plan(thu)

    @Test
    fun `walk 15 m at 10 m accuracy 1_3 mps claims +25`() {
        val fixes = passBy(chest, noon, northM = 15.0, speed = 1.3, accuracyM = 10.0)
        val r = ev.evaluate(fixes, listOf(thuPlan))
        val c = r.claims.single()
        assertEquals(treasureKey, c.key)
        assertEquals(ClaimKind.treasure, c.kind)
        assertEquals(25, c.marks)
        assertEquals(week, c.weekId)
        assertTrue(r.unclaimed.isEmpty())

        // PF5-1: the live find moment comes with the first fix inside the radius (well within 5 s).
        val tracker = SensingTracker(ev)
        var sensedAt: Long? = null
        for (f in fixes) {
            val s = tracker.onFix(f, thuPlan)
            if (s.isNotEmpty() && sensedAt == null) {
                assertEquals(treasureKey, s.single().key)
                sensedAt = f.timeMs
            }
        }
        val firstInside = fixes.first { GeoMath.distanceM(it.point, chest) <= 20.0 }.timeMs
        assertEquals(firstInside, sensedAt)
        assertEquals(setOf(treasureKey), tracker.sensed)
    }

    @Test
    fun `12 mps drive claims nothing`() {
        val fixes = passBy(chest, noon, northM = 5.0, speed = 12.0, stepS = 1, beforeM = 400.0)
        val r = ev.evaluate(fixes, listOf(thuPlan))
        assertTrue(r.claims.isEmpty(), "${r.claims}")
        assertEquals(listOf(Unclaimed(treasureKey, "treasure-test", UnclaimedReason.VEHICLE)), r.unclaimed)
        val tracker = SensingTracker(ev)
        assertTrue(fixes.all { tracker.onFix(it, thuPlan).isEmpty() }, "no find moment on a drive")
    }

    @Test
    fun `accuracy 35 m near 20 m treasure not sensed`() {
        val fixes = passBy(chest, noon, northM = 5.0, accuracyM = 35.0)
        val tracker = SensingTracker(ev)
        assertTrue(fixes.all { tracker.onFix(it, thuPlan).isEmpty() })
        val r = ev.evaluate(fixes, listOf(thuPlan), sensed = tracker.sensed)
        assertTrue(r.claims.isEmpty())
        assertEquals(UnclaimedReason.ACCURACY, r.unclaimed.single().reason)
    }

    @Test
    fun `kraken 1158 of 1158 m → band 95`() {
        val fixes = walk(manitouStart, manitouEnd, noon)
        val r = ev.evaluate(fixes, listOf(thuPlan))
        val c = r.claims.single()
        assertEquals(manitouKey, c.key)
        assertEquals(ClaimKind.ink, c.kind)
        assertEquals(95, c.band)
        assertEquals(80, c.marks)
        assertEquals(week, c.weekId)
        assertTrue(r.stretchShares.getValue(manitouKey) > 0.99, "${r.stretchShares}")
    }

    @Test
    fun `50 pct → nothing`() {
        val half = GeoMath.offset(manitouStart, MANITOU_M / 2, 0.0)
        val r = ev.evaluate(walk(manitouStart, half, noon), listOf(thuPlan))
        assertTrue(r.claims.isEmpty(), "${r.claims}")
        assertTrue(r.unclaimed.isEmpty(), "under 60 % is progress, not a failed claim")
        val share = r.stretchShares.getValue(manitouKey)
        assertTrue(share in 0.49..0.55, "share $share") // the sheet shows "50 % walked — 60 % repels it"
        // ≥ 60 % pays the lowest bucket
        val more = GeoMath.offset(manitouStart, MANITOU_M * 0.65, 0.0)
        val c = ev.evaluate(walk(manitouStart, more, noon), listOf(thuPlan)).claims.single()
        assertEquals(60, c.band)
        assertEquals(60, c.marks)
    }

    @Test
    fun `tide above 2_44 m → play_unclaimed_tide`() {
        val high = plan(thu, tides = ClaimFixture.highTide)
        val r = ev.evaluate(walk(manitouStart, manitouEnd, noon), listOf(high))
        assertTrue(r.claims.isEmpty())
        val u = r.unclaimed.single()
        assertEquals(manitouKey, u.key)
        assertEquals(UnclaimedReason.TIDE, u.reason)
        assertEquals("play_unclaimed_tide", u.reason.copyKey)
        // no tide table at all fails closed the same way (PF16-3)
        assertEquals(UnclaimedReason.TIDE, ev.evaluate(walk(manitouStart, manitouEnd, noon), listOf(plan(thu, tides = null))).unclaimed.single().reason)
    }

    @Test
    fun `after civil dusk → dark`() {
        val dusk = assertNotNull(plan(thu).sun.dusk)
        val late = at(thu, 19, 30)
        assertTrue(late > dusk)
        val r = ev.evaluate(passBy(chest, late, northM = 5.0), listOf(thuPlan))
        assertTrue(r.claims.isEmpty())
        assertEquals(listOf(Unclaimed(treasureKey, "treasure-test", UnclaimedReason.DARK)), r.unclaimed)
        assertEquals("play_unclaimed_dark", UnclaimedReason.DARK.copyKey)
    }

    @Test
    fun `inside quiet zone → nothing — visit stamp`() {
        val fixes = passBy(inMemorialZone, noon, northM = 0.0, beforeM = 60.0, afterM = 60.0)
        val r = ev.evaluate(fixes, listOf(thuPlan))
        assertTrue(r.claims.isEmpty(), "${r.claims}")
        assertTrue(r.unclaimed.isEmpty(), "${r.unclaimed}")
        assertEquals(listOf(QuietVisit("exclusion-memorial")), r.visits)
        val tracker = SensingTracker(ev)
        assertTrue(fixes.all { tracker.onFix(it, thuPlan).isEmpty() }, "nothing sensed in a quiet zone")
    }

    @Test
    fun `riddle unsolved still claims`() {
        val r = ev.evaluate(passBy(frogRock, noon, northM = 10.0), listOf(thuPlan), found = emptySet(), hints = emptyMap())
        val c = r.claims.single { it.kind == ClaimKind.riddle }
        assertEquals("riddle-frog@2026-10-26", c.key)
        assertEquals(40, c.marks)
        assertEquals(20, ev.evaluate(passBy(frogRock, noon, northM = 10.0), listOf(thuPlan), hints = mapOf("riddle-frog" to 2)).claims.single { it.kind == ClaimKind.riddle }.marks)
    }

    @Test
    fun `idempotent per session and content`() {
        // out and back past the chest, and the whole stretch twice: one claim each
        val out = passBy(chest, noon, northM = 5.0)
        val back = ClaimFixture.walk(GeoMath.offset(chest, 150.0, 0.0), GeoMath.offset(chest, -150.0, 0.0), noon + 10.minutes, northM = 5.0)
        val kraken = walk(manitouStart, manitouEnd, noon + 30.minutes) +
            walk(manitouEnd, manitouStart, noon + 50.minutes)
        val fixes = out + back + kraken
        val plans = listOf(thuPlan)
        val r = ev.evaluate(fixes, plans)
        assertEquals(listOf(manitouKey, treasureKey), r.claims.map { it.key })
        assertEquals(r, ev.evaluate(fixes.shuffled(kotlin.random.Random(3)), plans), "same session, same claims")
        val again = ev.evaluate(fixes, plans, found = r.claims.map { it.key }.toSet())
        assertTrue(again.claims.isEmpty() && again.unclaimed.isEmpty(), "already claimed content is never claimed or listed again")
    }
}
