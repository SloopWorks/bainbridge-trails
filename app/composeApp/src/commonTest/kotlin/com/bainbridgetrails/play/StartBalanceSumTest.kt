package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.TrailRewardInput
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.rules.WalkDerived
import com.bainbridgetrails.play.schedule.DerivedGoal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Play trails v1.1 plan D4, review P2-5, ux § 6: the PlayWake start-balance number is one sum — the `start_balance`,
 * `start_trails` and `island` entries this turn-on wrote — in the opt-in day's order.
 */
class StartBalanceSumTest {
    private val server = FakeServer()
    private val dir = DataFixtures.tmpDir("play-start-sum")
    private val clock = DebugPlayClock(system = { Instant.parse("2026-10-27T19:00:00Z") })
    private var input: TrailRewardInput? = TrailFixtures.bothDoneIsland

    init {
        server.on("GET /v1/play/content", Reply(304, ""))
    }

    private fun TestScope.repo(goal: Goal = Goal.trails): PlayRepository {
        val d = StandardTestDispatcher(testScheduler)
        val content = PlayContentRepository(
            api = server.client(), dir = dir, bundled = { PlayFixtures.pack },
            bundledTides = { PlayFixtures.tideText(it) }, playOn = { true }, now = { 0L }, io = d, compute = d,
        )
        var n = 0
        return PlayRepository(
            scope = backgroundScope, goal = goal, clock = { clock }, content = content,
            places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-${goal.name}.json")) },
            store = PlayStateStore("$dir/${goal.name}-state.json", d), track = MutableStateFlow<List<Fix>>(emptyList()), compute = d,
            walkDerived = { WalkDerived(coverage = 900, complete = 50, squares = 25) }, newId = { "id-${++n}" },
            trailTables = { TrailFixtures.tables }, trailInput = { input },
        )
    }

    @Test
    fun `awaitStartBalance = start_balance + start_trails + island`() = runTest {
        val r = repo()
        r.start()
        runCurrent()
        val entries = r.store.state.value.logbook.entries
        assertEquals(listOf(LogKind.start_balance, LogKind.start_trails, LogKind.island), entries.map { it.kind })
        val qualifying = TrailRewards.qualifying(TrailFixtures.bothDoneIsland, TrailFixtures.tables)
        assertEquals(975 + qualifying.sumOf { TrailRewards.marksOf(it) }, entries.sumOf { it.marks })
        assertEquals(entries.sumOf { it.marks }, r.awaitStartBalance(), "one PlayWake number")
        assertNull(r.awaitStartBalance(), "reported once")

        // Off and on with nothing new: nothing written, no number.
        r.stop()
        r.start()
        runCurrent()
        assertNull(r.awaitStartBalance())
        assertEquals(entries, r.store.state.value.logbook.entries)
        r.stop()
    }

    @Test
    fun `a later turn-on reports the trails catch-up alone`() = runTest {
        input = TrailFixtures.hayanoDone
        val r = repo()
        r.start()
        runCurrent()
        assertEquals(975 + 200, r.awaitStartBalance())
        r.stop()
        // Peter's walked while Play was off: climb:100, high ground, first steps in Gazzam (+150).
        input = TrailFixtures.input(mapOf("seg-h1" to 201.0, "seg-h2" to 201.0, "seg-p1" to 1_110.0), setOf(TrailFixtures.HAYANO, TrailFixtures.PETERS))
        r.start()
        runCurrent()
        assertEquals(150, r.awaitStartBalance())
        val last = r.store.state.value.logbook.entries.last()
        assertEquals(LogKind.start_trails, last.kind)
        assertEquals(com.bainbridgetrails.play.rules.LogCopy.trailCatchUp.what, last.what)
        r.stop()
    }

    @Test
    fun `roads writes start_balance only`() = runTest {
        val r = repo(Goal.roads)
        r.start()
        runCurrent()
        assertEquals(listOf(LogKind.start_balance), r.store.state.value.logbook.entries.map { it.kind })
        assertEquals(975, r.awaitStartBalance())
        r.stop()
    }
}
