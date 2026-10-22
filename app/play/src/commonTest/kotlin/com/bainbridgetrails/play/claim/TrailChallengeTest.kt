package com.bainbridgetrails.play.claim

import com.bainbridgetrails.play.claim.ClaimFixture.at
import com.bainbridgetrails.play.config.A11y
import com.bainbridgetrails.play.config.ChallengeMetric
import com.bainbridgetrails.play.config.ChallengeScope
import com.bainbridgetrails.play.config.ChallengeSpec
import com.bainbridgetrails.play.config.Copy
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.TestPack
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.TrailProgress
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.rules.TrailTables
import com.bainbridgetrails.play.schedule.PlayScheduler
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `ch-climb` / `ch-parkhop` on the phone (brief § 2 challenges, AC 4; plan D5): P = completions inside the window
 * (this walk + Logbook `complete` entries dated in it), target = min(default, remaining + P), claim when
 * P ≥ target > 0 and the occurrence is unclaimed. Climb compares integer decimetres (pack target 150 m = 1,500 dm).
 */
class TrailChallengeTest {
    private val climb = TestPack.challenge.copy(
        id = "ch-climb", goals = listOf(Goal.trails), `when` = TestPack.weekly("mon", "00:00", "24:00", 7),
        challenge = ChallengeSpec(ChallengeScope.solo, ChallengeMetric.climb, target = mapOf(Goal.trails to 150)),
        copy = Copy("Uphill week", "Finish trails that climb {target} between them this week."), a11y = A11y("Uphill week", "Uphill week."),
    )
    private val parkhop = TestPack.challenge.copy(
        id = "ch-parkhop", goals = listOf(Goal.trails), `when` = TestPack.weekly("sat", "00:00", "24:00", 2),
        challenge = ChallengeSpec(ChallengeScope.solo, ChallengeMetric.parks, target = mapOf(Goal.trails to 3)),
        copy = Copy("Park hop", "Finish a trail in different parks this weekend."), a11y = A11y("Park hop", "Park hop."),
    )
    private val config = ClaimFixture.config.copy(items = listOf(climb, parkhop))
    private val ev = ClaimEvaluator(config, ClaimFixture.index)
    private val s = PlayScheduler(config, ClaimFixture.index, ClaimFixture.lowTide, null)

    /** Five Play parks; a quiet trail; a Pritchard Park trail (not a Play park); a big park-less trail as remaining climb. */
    private val tables = TrailTables(
        segmentLengthM = emptyMap(),
        trailSegments = emptyMap(),
        quietSegments = emptySet(),
        quietFeatures = setOf("t-quiet"),
        featurePark = mapOf(
            "t-a" to "Alpha Park", "t-d" to "Alpha Park", "t-b" to "Beta Park", "t-c" to "Gamma Park", "t-e" to "Delta Park",
            "t-x" to "Echo Park", "t-quiet" to "Beta Park", "t-pritchard" to "Pritchard Park",
        ),
        featureGainM = mapOf(
            "t-a" to 100.0, "t-b" to 49.9, "t-c" to 0.1, "t-d" to 20.0, "t-e" to 0.0, "t-x" to 3.0,
            "t-quiet" to 66.5, "t-pritchard" to 30.0, "t-big" to 500.0,
        ),
        parks = setOf("Alpha Park", "Beta Park", "Delta Park", "Echo Park", "Gamma Park"),
    )

    private val tue = LocalDate(2026, 10, 27)
    private val sat = LocalDate(2026, 10, 31)
    private val sun = LocalDate(2026, 11, 1)
    private val mon = LocalDate(2026, 11, 2)
    private val climbKey = "ch-climb@2026-10-26"
    private val hopKey = "ch-parkhop@2026-10-31"

    private fun done(date: LocalDate, vararg ids: String) =
        ids.map { LogEntry("c-$it", LogKind.complete, it, "Completed on this walk", date, 100, walkId = "w-$date", contentId = it) }

    private fun claims(
        end: Instant,
        thisWalk: List<String>,
        earlier: List<LogEntry> = emptyList(),
        completed: Set<String> = (thisWalk + earlier.mapNotNull { it.contentId }).toSet(),
        found: Set<String> = emptySet(),
        t: TrailTables = tables,
    ): List<Claim> {
        val date = com.bainbridgetrails.play.time.IslandTime.dateOf(end)
        return ev.trailChallenges(listOf(s.dayPlan(date, Goal.trails)), end, TrailChallengeInput(t, completed, thisWalk, Logbook(earlier)), found)
    }

    @Test
    fun `149_9 m no claim, 150_0 m claims 100`() {
        assertTrue(claims(at(tue, 11), listOf("t-a", "t-b")).none { it.itemId == "ch-climb" })
        val c = claims(at(tue, 11), listOf("t-a", "t-b", "t-c")).single { it.itemId == "ch-climb" }
        assertEquals(climbKey, c.key)
        assertEquals(ClaimKind.challenge, c.kind)
        assertEquals(100, c.marks)
        assertEquals(LocalDate(2026, 10, 26), c.weekId)
        // the claim pass carries it too
        val r = ev.evaluate(emptyList(), listOf(s.dayPlan(tue, Goal.trails)), walkEnd = at(tue, 11),
            trails = TrailChallengeInput(tables, setOf("t-a", "t-b", "t-c"), listOf("t-a", "t-b", "t-c"), Logbook()))
        assertEquals(listOf(climbKey), r.claims.map { it.key })
    }

    @Test
    fun `2 parks no claim, 3 claims`() {
        assertTrue(claims(at(sat, 11), listOf("t-a", "t-b")).none { it.itemId == "ch-parkhop" })
        val c = claims(at(sun, 11), listOf("t-c"), earlier = done(sat, "t-a", "t-b")).single { it.itemId == "ch-parkhop" }
        assertEquals(hopKey, c.key)
        assertEquals(100, c.marks)
    }

    @Test
    fun `completion outside window ignored`() {
        // climb: t-c on the Sunday before the Monday window does not count
        assertTrue(claims(at(tue, 11), listOf("t-a", "t-b"), earlier = done(LocalDate(2026, 10, 25), "t-c")).none { it.itemId == "ch-climb" })
        // parks: a Friday completion does not count toward the weekend
        assertTrue(claims(at(sat, 11), listOf("t-a", "t-b"), earlier = done(LocalDate(2026, 10, 30), "t-c")).none { it.itemId == "ch-parkhop" })
        // a walk finishing Mon 00:05 is outside the weekend window
        assertTrue(claims(at(mon, 0, 5), listOf("t-c"), earlier = done(sat, "t-a", "t-b")).none { it.itemId == "ch-parkhop" })
    }

    @Test
    fun `quiet trail and Pritchard Park add nothing`() {
        assertEquals(1_000, TrailProgress.of(listOf("t-a", "t-quiet"), tables).climbDm)
        assertTrue(claims(at(tue, 11), listOf("t-a", "t-quiet", "t-b")).none { it.itemId == "ch-climb" }, "149.9 m + quiet 66.5 m")
        val p = TrailProgress.of(listOf("t-a", "t-b", "t-quiet", "t-pritchard"), tables)
        assertEquals(setOf("Alpha Park", "Beta Park"), p.parks)
        assertTrue(claims(at(sat, 11), listOf("t-a", "t-b", "t-quiet", "t-pritchard")).none { it.itemId == "ch-parkhop" })
    }

    @Test
    fun `last park with two trails, one finished this window → target 2, no claim`() {
        // review P1-A: the Worker adds P's count to remaining (it keeps no park names), so a park hopped this window
        // that still has an unfinished trail counts twice; the phone must agree or it claims what the server refuses
        val before = setOf("t-b", "t-c", "t-e", "t-x") // every park done but Alpha Park (t-a, t-d)
        val mid = before + "t-a"
        val p = TrailProgress.of(listOf("t-a"), tables)
        assertEquals(setOf("Alpha Park"), p.parks)
        assertEquals(2, TrailRewards.challengeTarget(ChallengeMetric.parks, 3, mid, p, tables))
        assertTrue(claims(at(sat, 11), listOf("t-a"), completed = mid).none { it.itemId == "ch-parkhop" })
        // finishing Alpha Park's last trail empties remaining: R = 0 + 1 → target 1, claims
        assertEquals(1, TrailRewards.challengeTarget(ChallengeMetric.parks, 3, mid + "t-d", TrailProgress.of(listOf("t-a", "t-d"), tables), tables))
        assertEquals(listOf(hopKey), claims(at(sun, 11), listOf("t-d"), earlier = done(sat, "t-a"), completed = mid + "t-d").map { it.key })
    }

    @Test
    fun `same park twice counts once`() {
        assertEquals(setOf("Alpha Park", "Beta Park"), TrailProgress.of(listOf("t-a", "t-d", "t-b"), tables).parks)
        assertTrue(claims(at(sat, 11), listOf("t-a", "t-d", "t-b")).none { it.itemId == "ch-parkhop" })
        // the same trail in the Logbook and in this walk counts once
        assertTrue(claims(at(sat, 15), listOf("t-a", "t-b"), earlier = done(sat, "t-a", "t-b")).none { it.itemId == "ch-parkhop" })
    }

    @Test
    fun `P above target and unclaimed claims`() {
        // an earlier walk in the window reached 200.0 m but its claim never happened: a later walk claims it, even one
        // that completes nothing (no prior < target gate, review P2-6)
        val earlier = done(tue, "t-a", "t-b", "t-c", "t-d")
        assertEquals(listOf(climbKey), claims(at(LocalDate(2026, 10, 29), 11), emptyList(), earlier = earlier).map { it.key })
    }

    @Test
    fun `claims once per occurrence`() {
        val earlier = done(tue, "t-a", "t-b", "t-c")
        assertTrue(claims(at(LocalDate(2026, 10, 29), 11), listOf("t-d"), earlier = earlier, found = setOf(climbKey)).isEmpty())
        // next week is a new occurrence: last week's completions do not count toward it
        assertTrue(claims(at(LocalDate(2026, 11, 3), 11), emptyList(), earlier = earlier).isEmpty())
    }

    @Test
    fun `120 m remaining → target 120`() {
        // everything is completed but t-b2 (70.0 m) and t-b3 (50.0 m): remaining 1,200 dm at the window start
        val t = tables.copy(featureGainM = mapOf("t-done" to 900.0, "t-b2" to 70.0, "t-b3" to 50.0), featurePark = emptyMap())
        val before = setOf("t-done")
        assertEquals(1_200, TrailRewards.challengeTarget(ChallengeMetric.climb, 1_500, before, TrailProgress.NONE, t))
        // finishing t-b2 this week moves 700 dm from remaining to P: R stays 1,200
        val mid = before + "t-b2"
        val p = TrailProgress.of(listOf("t-b2"), t)
        assertEquals(1_200, TrailRewards.challengeTarget(ChallengeMetric.climb, 1_500, mid, p, t))
        assertTrue(claims(at(tue, 11), listOf("t-b2"), completed = mid, t = t).isEmpty())
        assertEquals(listOf(climbKey), claims(at(tue, 15), listOf("t-b3"), earlier = done(tue, "t-b2"), completed = mid + "t-b3", t = t).map { it.key })
        // parks: one Play park left (the quiet trail and Pritchard Park never count) → target 1
        val parks = TrailRewards.challengeTarget(ChallengeMetric.parks, 3, setOf("t-a", "t-d", "t-b", "t-c", "t-x"), TrailProgress.NONE, tables)
        assertEquals(1, parks) // Delta Park (t-e)
    }

    @Test
    fun `nothing remaining → no claim`() {
        val all = tables.featureGainM.keys
        assertEquals(0, TrailRewards.challengeTarget(ChallengeMetric.climb, 1_500, all, TrailProgress.NONE, tables))
        assertEquals(0, TrailRewards.challengeTarget(ChallengeMetric.parks, 3, all, TrailProgress.NONE, tables))
        assertTrue(claims(at(sat, 11), emptyList(), completed = all).isEmpty())
        assertTrue(TrailRewards.claims(0, 0).not())
        assertTrue(TrailRewards.claims(1_500, 1_500))
    }
}
