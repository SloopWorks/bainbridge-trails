package com.bainbridgetrails.ui.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.play.PlayContentRepository
import com.bainbridgetrails.play.PlayFixtures
import com.bainbridgetrails.play.PlayGate
import com.bainbridgetrails.play.PlayRepository
import com.bainbridgetrails.play.PlayStateStore
import com.bainbridgetrails.play.DebugPlayClock
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.WalkDerived
import com.bainbridgetrails.play.schedule.DerivedGoal
import com.bainbridgetrails.ui.play.sheets.PlayOptInGraph
import com.bainbridgetrails.ui.play.sheets.PlaySheet
import com.bainbridgetrails.ui.play.sheets.PlayToggleFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Settings › Play (ux.md §3.1; product §5.1, PF1): the toggle, the intro and the opt-in start balance, over the real
 * Play gate (PD-1) so "no request" is the server's word, not a fake's.
 */
class PlayToggleFlowTest {
    private val server = FakeServer()
    private val dir = DataFixtures.tmpDir("play-toggle")
    private val settings = MutableStateFlow<PlaySettings?>(PlaySettings())
    private var created = 0
    private var gate: PlayGate? = null
    private var ids = 0
    private var snapshotDelayMs = 0L

    /** Snapshot-derived marks of today's coverage (product §3.6.4 worked example: 1,296). */
    private val derived = WalkDerived(coverage = 1_000, complete = 200, squares = 96)
    private val clock = DebugPlayClock(system = { Instant.parse("2026-10-29T21:00:00Z") })

    private fun TestScope.flow(): PlayToggleFlow {
        val d = StandardTestDispatcher(testScheduler)
        server.on("GET /v1/play/content", Reply(304, ""))
        val g = PlayGate(backgroundScope, enabled = settings) {
            created++
            val content = PlayContentRepository(
                api = server.client(dataset = "roads"), dir = dir, bundled = { PlayFixtures.pack },
                bundledTides = { PlayFixtures.tideText(it) }, playOn = { settings.value?.enabled == true },
                now = { 10_000_000L }, io = d, compute = d,
            )
            PlayRepository(
                scope = backgroundScope, goal = Goal.roads, clock = { clock }, content = content,
                places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-roads.json")) },
                store = PlayStateStore("$dir/roads-state.json", d), track = MutableStateFlow<List<Fix>>(emptyList()), compute = d,
                walkDerived = { delay(snapshotDelayMs); derived }, newId = { "id-${ids++}" },
            )
        }.also { gate = it }
        g.start()
        val optIn = PlayOptInGraph(
            updatePlay = { change -> settings.value = change(settings.value ?: PlaySettings()) },
            repo = { gate?.current },
        )
        return PlayToggleFlow(optIn, enabled = false)
    }

    private fun playRequests() = server.requests.count { it.path.startsWith("/v1/play") }

    private fun logbook(): List<LogEntry> = gate!!.current?.store?.state?.value?.logbook?.entries.orEmpty()

    @Test
    fun introDismissedWithoutStartKeepsPlayOffAndMakesNoRequest() = runTest {
        val f = flow()
        runCurrent()
        f.onSwitch(true)
        // ux §3.1: the switch shows on (pending) while the intro is open; nothing is persisted yet.
        assertTrue(f.state.value.switchOn)
        assertEquals(PlaySheet.Intro, f.state.value.sheet)
        assertFalse(settings.value!!.enabled)
        f.onIntroDismissed()
        advanceUntilIdle()
        assertFalse(f.state.value.switchOn, "the switch animates back off (PF1-3)")
        assertNull(f.state.value.sheet)
        assertFalse(settings.value!!.enabled)
        assertFalse(settings.value!!.everToggled, "a dismissed intro is not a toggle")
        assertEquals(0, created, "no PlayRepository")
        assertEquals(0, playRequests(), "no /v1/play request (PF1-3, PD-1)")
    }

    @Test
    fun startSetsEnabledAndCreditsStartBalanceOnce() = runTest {
        val f = flow()
        runCurrent()
        f.onSwitch(true)
        val first = f.onStart()
        advanceUntilIdle()
        assertTrue(settings.value!!.enabled, "Start turns Play on (PF1-1)")
        assertTrue(settings.value!!.everToggled)
        assertTrue(f.state.value.switchOn)
        assertNull(f.state.value.sheet)
        assertEquals(1_296, first, "snackbar play_start_balance carries the snapshot's walk-derived marks")
        val entries = logbook()
        assertEquals(1, entries.size)
        assertEquals(LogKind.start_balance, entries.single().kind)
        assertEquals(1_296, entries.single().marks)
        assertEquals(LocalDate(2026, 10, 29), entries.single().date)
        assertTrue(gate!!.current!!.store.state.value.logbook.hasStartBalance)

        // Off (confirmed) then on again: no second "Your walks so far", marks unchanged (PF1-2, PF14-2).
        f.onSwitch(false)
        assertEquals(PlaySheet.Off, f.state.value.sheet)
        assertTrue(f.state.value.switchOn, "the switch stays on until the off sheet is confirmed")
        f.onOffConfirmed()
        advanceUntilIdle()
        assertFalse(settings.value!!.enabled)
        f.onSwitch(true)
        val second = f.onStart()
        advanceUntilIdle()
        assertNull(second, "no second start balance, no snackbar")
        assertEquals(1, logbook().count { it.kind == LogKind.start_balance })
        assertEquals(1_296, logbook().sumOf { it.marks })
        assertEquals(1, created, "the repository is kept across off/on")
    }

    /**
     * PF1-1 (review L2-7): a snapshot slower than Start's spinner loses only the snackbar; the repository still credits
     * the start balance on its own, Play is on and the switch is free again.
     */
    @Test
    fun slowSnapshotStillCreditsStartBalance() = runTest {
        snapshotDelayMs = 30_000L
        val f = flow()
        runCurrent()
        f.onSwitch(true)
        val marks = f.onStart()
        assertNull(marks, "Start stopped waiting after 5 s: no snackbar")
        assertTrue(settings.value!!.enabled)
        assertFalse(f.state.value.starting)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(LogKind.start_balance), logbook().map { it.kind })
        assertEquals(1_296, logbook().single().marks)
    }
}
