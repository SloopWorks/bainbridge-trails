package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.fx.fxHostInputs
import com.bainbridgetrails.play.schedule.DerivedGoal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** PD-1, PF0-1 / PF0-2: with Play off nothing Play exists — no repository, no FxClock, no sensing, no `/v1/play` call. */
class PlayOffNothingCreatedTest {
    private val server = FakeServer()
    private val dir = DataFixtures.tmpDir("play-off")
    private val settings = MutableStateFlow<PlaySettings?>(null)
    private val track = MutableStateFlow<List<Fix>>(emptyList())
    private var created = 0

    private fun TestScope.gate(): PlayGate {
        val d = StandardTestDispatcher(testScheduler)
        server.on("GET /v1/play/content", Reply(304, ""))
        return PlayGate(backgroundScope, enabled = settings) {
            created++
            val content = PlayContentRepository(
                api = server.client(dataset = "roads"), dir = dir, bundled = { PlayFixtures.pack },
                bundledTides = { PlayFixtures.tideText(it) }, playOn = { settings.value?.enabled == true },
                now = { 10_000_000L }, io = d, compute = d,
            )
            PlayRepository(
                scope = backgroundScope, goal = Goal.roads, clock = { SystemPlayClock }, content = content,
                places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-roads.json")) },
                store = PlayStateStore("$dir/roads-state.json", d), track = track, compute = d,
            )
        }
    }

    private fun playRequests() = server.requests.count { it.path.startsWith("/v1/play") }

    @Test
    fun playOffPlayOrNullNullNoFxClockNoPlaySensingNoPlayRequest() = runTest {
        val sensingBefore = PlaySensing.created
        val g = gate()
        g.start()
        settings.value = PlaySettings() // loaded: off (the default)
        runCurrent()
        track.value = listOf(Fix(1L, -122.52, 47.63, 5.0)) // a walk records with Play off
        runCurrent()

        assertNull(g.current, "playOrNull is null while off")
        assertEquals(0, created, "PlayRepository never constructed while off")
        assertEquals(sensingBefore, PlaySensing.created, "no PlaySensing observer")
        assertNull(fxHostInputs(PlaySettings(), recording = false), "no FxHost, so no FxClock")
        assertNull(fxHostInputs(PlaySettings(), recording = true))
        assertEquals(0, playRequests(), "no /v1/play request (PF0-2)")

        // Turning Play on creates it once and starts it; the content check is the first Play request.
        settings.value = PlaySettings(enabled = true)
        runCurrent()
        val repo = assertNotNull(g.current)
        assertEquals(1, created)
        assertEquals(sensingBefore + 1, PlaySensing.created)
        assertTrue(playRequests() >= 1)

        // Off again: hidden and stopped, nothing deleted, no further request; on again reuses the same instance.
        settings.value = PlaySettings(enabled = false)
        runCurrent()
        assertNull(g.current)
        val after = playRequests()
        track.value = listOf(Fix(2L, -122.52, 47.63, 5.0))
        runCurrent()
        assertEquals(after, playRequests())
        settings.value = PlaySettings(enabled = true)
        runCurrent()
        assertSame(repo, g.current)
        assertEquals(1, created)
    }
}
