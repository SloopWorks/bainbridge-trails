package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.WalkDerived
import com.bainbridgetrails.play.schedule.ActiveItem
import com.bainbridgetrails.play.schedule.DerivedGoal
import com.bainbridgetrails.play.schedule.Gate
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
    ): PlayRepository {
        val d = StandardTestDispatcher(testScheduler)
        val content = PlayContentRepository(
            api = server.client(dataset = "roads"), dir = dir, bundled = { pack },
            bundledTides = { y -> if (y in tideYears) PlayFixtures.tideText(y) else null },
            playOn = { false }, now = { 0L }, io = d, compute = d,
        )
        return PlayRepository(
            scope = backgroundScope, goal = Goal.roads, clock = { clock }, content = content,
            places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-roads.json")) },
            store = PlayStateStore("$dir/roads-state.json", d), track = track, sink = sink, compute = d,
            walkDerived = walkDerived, newId = { "id-${ids++}" },
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
}
