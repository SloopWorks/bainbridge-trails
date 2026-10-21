package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.play.claim.WalkDone
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.claim.ClaimKind
import com.bainbridgetrails.play.claim.GatedSummary
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.WalkDerived
import com.bainbridgetrails.play.schedule.ActiveItem
import com.bainbridgetrails.play.schedule.DerivedGoal
import com.bainbridgetrails.play.schedule.FeatureInfo
import com.bainbridgetrails.play.schedule.FeatureRef
import com.bainbridgetrails.play.schedule.Gate
import com.bainbridgetrails.play.schedule.PlaceIndex
import com.bainbridgetrails.play.schedule.SegRef
import com.bainbridgetrails.play.schedule.StretchRef
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.tracking.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** schedule.md § 5.3 (memoized plans per island day), product PF16-1 / PF16-3, PD-1. */
class PlayRepositoryTest {
    private val server = FakeServer()
    private val dir = DataFixtures.tmpDir("play-repo")
    private var system = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = DebugPlayClock(system = { system })

    /** Records what live sensing hands the claim logic (L1-6's SensingTracker plugs in here). */
    private class RecordingSink : SensingSink {
        val targets = mutableListOf<List<String>>()
        override fun onFix(fix: Fix, targets: List<ActiveItem>, now: Instant): List<SensedFind> {
            this.targets += targets.map { it.occurrence.itemId }
            return emptyList()
        }
        override fun reset() = Unit
        override suspend fun onGated(sessionId: String, kept: List<Fix>, summary: SessionSummary) = Unit
    }

    private val sink = RecordingSink()
    private val track = MutableStateFlow<List<Fix>>(emptyList())
    private var ids = 0

    private fun TestScope.repo(
        pack: String = PlayFixtures.pack,
        tideYears: IntRange = 2026..2029,
        walkDerived: suspend () -> WalkDerived? = { null },
        claims: PlayClaimQueue? = null,
        quietFeatures: Set<String> = emptySet(),
        places: PlaceIndex = PlayFixtures.EmptyPlaces,
        liveSink: SensingSink? = sink,
    ): PlayRepository {
        val d = StandardTestDispatcher(testScheduler)
        val content = PlayContentRepository(
            api = server.client(dataset = "roads"), dir = dir, bundled = { pack },
            bundledTides = { y -> if (y in tideYears) PlayFixtures.tideText(y) else null },
            playOn = { false }, now = { 0L }, io = d, compute = d,
        )
        return PlayRepository(
            scope = backgroundScope, goal = Goal.roads, clock = { clock }, content = content,
            places = { places }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-roads.json")) },
            store = PlayStateStore("$dir/roads-state.json", d), track = track, sink = liveSink, compute = d,
            walkDerived = walkDerived, newId = { "id-${ids++}" }, claims = claims,
            walkContext = { WalkContext(quietFeatures = quietFeatures) },
        )
    }

    private fun ids(r: PlayRepository) = r.agenda.value!!.active.map { it.occurrence.itemId }

    @Test
    fun fixedPlayClock() = runTest {
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-29T14:00")))
        val r = repo()
        val agenda = assertNotNull(r.refresh())
        assertEquals(LocalDate(2026, 10, 29), agenda.day.date)
        assertTrue("kraken-roads" in ids(r), "the first attack is on: ${ids(r)}")
        // The system clock moving does not move a fixed Play clock.
        system = Instant.parse("2027-06-01T12:00:00Z")
        assertEquals(LocalDate(2026, 10, 29), r.refresh()!!.day.date)
    }

    @Test
    fun dayRolloverRebuildsPlan() = runTest {
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-28T23:59")))
        val r = repo()
        r.refresh()
        val wed = assertNotNull(r.today.value)
        assertEquals(LocalDate(2026, 10, 28), wed.date)
        assertTrue(wed.occurrences.none { it.itemId == "kraken-roads" })

        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-29T00:01")))
        r.refresh()
        val thu = assertNotNull(r.today.value)
        assertNotSame(wed, thu)
        assertEquals(LocalDate(2026, 10, 29), thu.date)
        assertTrue(thu.occurrences.any { it.key == "kraken-roads@2026-10-29" })
    }

    @Test
    fun dstDay() = runTest {
        // Sun 2026-11-01: clocks fall back at 02:00. The attack began Thu 06:00 PDT and ends Sun 20:00 PST.
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-11-01T12:00")))
        val r = repo()
        val agenda = assertNotNull(r.refresh())
        assertEquals(LocalDate(2026, 11, 1), agenda.day.date)
        val kraken = agenda.active.single { it.occurrence.itemId == "kraken-roads" }.occurrence
        assertEquals(Instant.parse("2026-10-29T13:00:00Z"), kraken.start)
        assertEquals(Instant.parse("2026-11-02T04:00:00Z"), kraken.end)
        assertEquals(LocalDate(2026, 11, 1), r.today.value!!.date)
    }

    @Test
    fun killItemsHidesTreasureAndBlocksSensing() = runTest {
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-27T12:00")))
        val live = repo()
        live.refresh()
        assertTrue("treasure-weekly-roads" in ids(live))
        assertTrue(live.sensingTargets().any { it.occurrence.type == ItemType.treasure })

        val killed = repo(PlayFixtures.packWith(PlayFixtures.killItems("treasure-weekly-roads")))
        killed.start()
        runCurrent()
        assertTrue("treasure-weekly-roads" !in ids(killed), "PF16-1: gone from the map")
        assertTrue(killed.today.value!!.occurrences.none { it.itemId == "treasure-weekly-roads" })
        track.value = listOf(Fix(PlayFixtures.island("2026-10-27T12:00").toEpochMilliseconds(), -122.52, 47.63, 5.0))
        runCurrent()
        assertTrue(sink.targets.isNotEmpty(), "sensing ran")
        assertTrue(sink.targets.all { "treasure-weekly-roads" !in it }, "PF16-1: never sensed")
        killed.stop()
    }

    @Test
    fun expiredTideTableGateTideUnknown() = runTest {
        // Bundled tides end with 2026; Thu 2027-01-07 14:00 is inside the attack window and daylight.
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2027-01-07T14:00")))
        val r = repo(tideYears = 2026..2026)
        r.refresh()
        val kraken = r.agenda.value!!.active.single { it.occurrence.itemId == "kraken-roads" }
        assertEquals(Gate.TIDE_UNKNOWN, kraken.gate, "PF16-3: fail closed")
        assertTrue(r.sensingTargets().none { it.occurrence.itemId == "kraken-roads" })

        // The debug override "expired" does the same on a covered date.
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-29T14:00")))
        clock.setTide(PlayTideOverride.EXPIRED)
        val forced = repo()
        forced.refresh()
        assertEquals(Gate.TIDE_UNKNOWN, forced.agenda.value!!.active.single { it.occurrence.itemId == "kraken-roads" }.gate)
        // ... and "low" opens it.
        clock.setTide(PlayTideOverride.LOW)
        forced.refresh()
        assertEquals(Gate.OPEN, forced.agenda.value!!.active.single { it.occurrence.itemId == "kraken-roads" }.gate)
    }

    /**
     * PF1-1 / PF1-2 (review L2-7): the opt-in start balance is the repository's own job on start, not the Settings
     * flow's — it is credited when the Logbook has none, whatever the UI does, and only once ever.
     */
    @Test
    fun startCreditsStartBalanceWhenMissing() = runTest {
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-29T14:00")))
        val r = repo(walkDerived = { WalkDerived(coverage = 1_000, complete = 200, squares = 96) })
        r.start()
        runCurrent()
        assertEquals(1_296, r.awaitStartBalance(), "the marks of the entry this start wrote (the snackbar's)")
        val entries = r.store.state.value.logbook.entries
        assertEquals(listOf(LogKind.start_balance), entries.map { it.kind })
        assertEquals(1_296, entries.single().marks)
        assertEquals(LocalDate(2026, 10, 29), entries.single().date)

        // Off and on again: no second "Your walks so far" (idempotent), no snackbar.
        r.stop()
        r.start()
        runCurrent()
        assertNull(r.awaitStartBalance())
        assertEquals(1, r.store.state.value.logbook.entries.count { it.kind == LogKind.start_balance })
        r.stop()
    }

    private fun summary(id: String, start: String, end: String, completed: List<String>) = GatedSummary(
        sessionId = id, startedAtMs = PlayFixtures.island(start).toEpochMilliseconds(), endedAtMs = PlayFixtures.island(end).toEpochMilliseconds(),
        distanceM = 3_000.0, matchedM = 2_000.0, newlyCompleted = completed, verified = true, droppedVehicle = 0,
    )

    /**
     * Branch review round 1 P0-1: `ch-sweep` (roads: 5 completions Sat–Sun) is claimed by the walk whose completions
     * reach the target, counting the earlier walks of the window kept in the no-backup state; quiet features never
     * count, and a re-run walk never counts itself.
     */
    @Test
    fun weekendCompletionsOverTwoWalksClaimTheSweepOnce() = runTest {
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-11-01T12:00")))
        val q = PlayClaimQueue("$dir/claims-sweep.json", StandardTestDispatcher(testScheduler))
        val r = repo(claims = q, quietFeatures = setOf("road-quiet"))
        val sat = assertNotNull(r.claimWalk("s1", emptyList(), summary("s1", "2026-10-31T10:00", "2026-10-31T11:00", listOf("r1", "r2", "r3", "road-quiet"))))
        assertTrue(sat.claims.none { it.kind == ClaimKind.challenge }, "3 of 5")
        assertEquals(listOf(WalkDone("s1", PlayFixtures.island("2026-10-31T11:00").toEpochMilliseconds(), 3)), r.store.state.value.walks)

        val sun = assertNotNull(r.claimWalk("s2", emptyList(), summary("s2", "2026-11-01T10:00", "2026-11-01T11:00", listOf("r4", "r5"))))
        val ch = sun.claims.single { it.kind == ClaimKind.challenge }
        assertEquals("ch-sweep@2026-10-31", ch.key)
        assertEquals(LocalDate(2026, 10, 26), ch.weekId)
        val element = q.queued().single { it.sessionId == "s2" }
        assertEquals(listOf("ch-sweep@2026-10-31"), element.claims.filter { it.kind == "challenge" }.map { it.id }, "queued for the upload")
        val entry = r.store.state.value.logbook.entries.single { it.kind == LogKind.challenge }
        assertEquals(Triple("ch-sweep@2026-10-31", 100, "s2"), Triple(entry.contentId, entry.marks, entry.walkId))
        assertTrue("ch-sweep@2026-10-31" in r.store.state.value.found)

        // The hook re-runs for s2 (launch replay) and a third walk completes more: nothing claims twice.
        val again = assertNotNull(r.claimWalk("s2", emptyList(), summary("s2", "2026-11-01T10:00", "2026-11-01T11:00", listOf("r4", "r5"))))
        assertTrue(again.claims.none { it.kind == ClaimKind.challenge })
        val third = assertNotNull(r.claimWalk("s3", emptyList(), summary("s3", "2026-11-01T14:00", "2026-11-01T15:00", listOf("r6"))))
        assertTrue(third.claims.none { it.kind == ClaimKind.challenge })
        assertEquals(1, r.store.state.value.logbook.entries.count { it.kind == LogKind.challenge })
        assertEquals(listOf("s1", "s2", "s3"), r.store.state.value.walks.map { it.walkId })
    }

    /** The walk-derived input not ready yet (dataset still loading): the repository retries, Play stays usable. */
    @Test
    fun startBalanceRetriesUntilTheSnapshotIsReady() = runTest {
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-29T14:00")))
        var calls = 0
        val r = repo(walkDerived = { if (++calls < 3) null else WalkDerived(coverage = 500, complete = 0, squares = 10) })
        r.start()
        runCurrent()
        assertTrue(r.store.state.value.logbook.entries.isEmpty(), "nothing yet")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, calls)
        assertEquals(510, r.store.state.value.logbook.entries.single { it.kind == LogKind.start_balance }.marks)
        r.stop()
    }

    /** One kraken shore road (K5's first stretch, 1,158 m due north) and nothing else. */
    private object ManitouPlaces : PlaceIndex {
        const val ID = "road-manitou-beach-drive-northeast"
        val start = GeoPoint(lat = 47.6380, lon = -122.5060)
        val end = GeoMath.offset(start, 0.0, 1_158.0)
        private val info = FeatureInfo(ID, Goal.roads, category = "residential", public = true, ownership = "public_city", speedMph = 25)
        private val seg = SegRef.fromLine("rseg-manitou", listOf(start, end), 25, "residential")
        override fun segments(ref: FeatureRef): List<SegRef> = if (ref == StretchRef(ID) || ref == com.bainbridgetrails.play.schedule.RoadRef(ID)) listOf(seg) else emptyList()
        override fun point(ref: FeatureRef): GeoPoint? = null
        override fun feature(id: String): FeatureInfo? = info.takeIf { id == ID }
        override fun features(goal: Goal): List<FeatureInfo> = if (goal == Goal.roads) listOf(info) else emptyList()
    }

    /** Fixes every 2 s at 1.3 m/s along Manitou from [startMs] (real recording time), 8 m accuracy. */
    private fun manitouWalk(startMs: Long): List<Fix> {
        val n = (1_158.0 / 2.6).toInt()
        return (0..n).map { i ->
            val f = i.toDouble() / n
            val lat = ManitouPlaces.start.lat + f * (ManitouPlaces.end.lat - ManitouPlaces.start.lat)
            Fix(startMs + i * 2_000L, ManitouPlaces.start.lon, lat, 8.0)
        }
    }

    private fun walkSummary(id: String, fixes: List<Fix>) = SessionSummary(
        sessionId = id, islandBefore = 0.0, islandAfter = 0.0, trails = emptyList(), verified = true, droppedVehicle = 0,
        droppedSteps = 0, newlyCompleted = emptyList(), startedAt = fixes.first().timeMs, endedAt = fixes.last().timeMs,
        distanceM = 1_158.0, matchedM = 1_158.0,
    )

    /**
     * e2e run 1 F1: the debug clock ticking from Thu 2026-10-29 14:00 while the device is at Wed 2026-10-21 01:00 (night,
     * before the attack): a walk recorded "now" is evaluated at Play time, so the Finish pass claims the kraken stretch,
     * live sensing senses it, and the Logbook files it under the Play date.
     */
    @Test
    fun shiftedPlayClockClaimsAWalkRecordedNow() = runTest {
        system = PlayFixtures.island("2026-10-21T01:00")
        clock.startAt(PlayFixtures.island("2026-10-29T14:00"))
        clock.setTide(PlayTideOverride.LOW)
        val q = PlayClaimQueue("$dir/claims-shift.json", StandardTestDispatcher(testScheduler))
        val r = repo(claims = q, places = ManitouPlaces, liveSink = null)
        r.start()
        runCurrent()
        assertTrue(r.sensingTargets().any { it.occurrence.itemId == "kraken-roads" }, "the attack is on at Play time")

        val fixes = manitouWalk(system.toEpochMilliseconds())
        track.value = fixes
        runCurrent()
        assertTrue(r.sensed.value.any { it.startsWith("kraken-roads@2026-10-29") }, "live: ${r.sensed.value}")

        r.gatedHook("s1", fixes, walkSummary("s1", fixes))
        runCurrent()
        val ink = r.claimResults.value.getValue("s1").claims.single { it.kind == ClaimKind.ink }
        assertTrue(ink.key.startsWith("kraken-roads@2026-10-29#"), ink.key)
        val entry = r.store.state.value.logbook.entries.single { it.kind == LogKind.ink }
        assertEquals(LocalDate(2026, 10, 29), entry.date, "filed under the Play date")
        r.stop()
    }

    /** Offset 0 (the release clock, or the debug clock on Real) is a no-op: the same night walk claims nothing. */
    @Test
    fun zeroOffsetEvaluatesTheFixesAsRecorded() = runTest {
        assertEquals(0L, SystemPlayClock.offsetMs)
        system = PlayFixtures.island("2026-10-21T01:00")
        clock.set(PlayClockMode.Real)
        clock.setTide(PlayTideOverride.LOW)
        assertEquals(0L, clock.offsetMs)
        val q = PlayClaimQueue("$dir/claims-zero.json", StandardTestDispatcher(testScheduler))
        val r = repo(claims = q, places = ManitouPlaces, liveSink = null)
        r.start()
        runCurrent()
        val night = manitouWalk(system.toEpochMilliseconds())
        r.gatedHook("s1", night, walkSummary("s1", night))
        runCurrent()
        assertTrue(r.claimResults.value.getValue("s1").claims.none { it.kind == ClaimKind.ink })

        // The same walk really recorded on Thursday afternoon claims at its own time.
        val thu = manitouWalk(PlayFixtures.island("2026-10-29T14:00").toEpochMilliseconds())
        r.gatedHook("s2", thu, walkSummary("s2", thu))
        runCurrent()
        assertTrue(r.claimResults.value.getValue("s2").claims.any { it.kind == ClaimKind.ink })
        r.stop()
    }
}
