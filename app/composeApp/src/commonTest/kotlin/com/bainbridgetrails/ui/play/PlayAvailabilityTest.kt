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
import com.bainbridgetrails.play.SystemPlayClock
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.schedule.DerivedGoal
import com.bainbridgetrails.ui.play.components.PlayAvailability
import com.bainbridgetrails.ui.play.components.PlayAvailabilityLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Play screens' `WithPlay` (review L2-7): the repository comes from the settings StateFlow + the Play gate, Off is
 * immediate (EmptyState, or leave when Play turns off while the screen is open), and a slow gate never strands the
 * screen on a spinner (the old 5 s poll gave up).
 */
class PlayAvailabilityTest {
    private val server = FakeServer()
    private val dir = DataFixtures.tmpDir("play-availability")
    private val settings = MutableStateFlow<PlaySettings?>(PlaySettings())

    private fun TestScope.repo(): PlayRepository {
        val d = StandardTestDispatcher(testScheduler)
        server.on("GET /v1/play/content", Reply(304, ""))
        val content = PlayContentRepository(
            api = server.client(dataset = "roads"), dir = dir, bundled = { PlayFixtures.pack },
            bundledTides = { PlayFixtures.tideText(it) }, playOn = { settings.value?.enabled == true },
            now = { 10_000_000L }, io = d, compute = d,
        )
        return PlayRepository(
            scope = backgroundScope, goal = Goal.roads, clock = { SystemPlayClock }, content = content,
            places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-roads.json")) },
            store = PlayStateStore("$dir/roads-state.json", d), track = MutableStateFlow<List<Fix>>(emptyList()), compute = d,
        )
    }

    @Test
    fun offIsImmediateAndNothingIsCreated() = runTest {
        var created = 0
        val gate = PlayGate(backgroundScope, settings) { created++; repo() }.also { it.start() }
        val seen = mutableListOf<PlayAvailability>()
        backgroundScope.launch { PlayAvailabilityLogic.flow(settings.map { it?.enabled == true }, { gate.current }).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<PlayAvailability>(PlayAvailability.Off), seen)
        assertEquals(PlayAvailability.Off, PlayAvailabilityLogic.now(enabled = false, current = null))
        assertEquals(0, created, "an Off screen never makes Play (PD-1)")
    }

    @Test
    fun onFollowsTheGateThenOffAgain() = runTest {
        val gate = PlayGate(backgroundScope, settings) { repo() }.also { it.start() }
        val seen = mutableListOf<PlayAvailability>()
        backgroundScope.launch { PlayAvailabilityLogic.flow(settings.map { it?.enabled == true }, { gate.current }).collect { seen += it } }
        runCurrent()
        settings.value = PlaySettings(enabled = true)
        runCurrent()
        advanceTimeBy(100)
        val ready = assertIs<PlayAvailability.Ready>(seen.last())
        assertSame(gate.current, ready.play)
        settings.value = PlaySettings(enabled = false)
        runCurrent()
        assertEquals(PlayAvailability.Off, seen.last(), "turned off while open: the screen leaves")
    }

    @Test
    fun slowGateNeverGivesUp() = runTest {
        var current: PlayRepository? = null
        val on = MutableStateFlow(true)
        val seen = mutableListOf<PlayAvailability>()
        backgroundScope.launch { PlayAvailabilityLogic.flow(on, { current }).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<PlayAvailability>(PlayAvailability.Starting), seen)
        advanceTimeBy(30_000) // far past the old 5 s give-up
        val r = repo()
        current = r
        advanceTimeBy(PlayAvailabilityLogic.POLL_MS + 1)
        assertEquals(PlayAvailability.Ready(r), seen.last())
    }
}
