package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.schedule.DerivedGoal
import com.bainbridgetrails.ui.play.sheets.PlayOptInGraph
import com.bainbridgetrails.ui.play.sheets.PlayToggleFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PF14-1 / PF14-2, PD-1: turning Play off confirms with no request at all and deletes nothing (the Logbook stays). */
class PlayOffRequestsTest {
    @Test
    fun turningOffSendsNoRequestKeepsLogbook() = runTest {
        val server = FakeServer()
        server.on("GET /v1/play/content", Reply(304, ""))
        val dir = DataFixtures.tmpDir("play-off-requests")
        val d = StandardTestDispatcher(testScheduler)
        val settings = MutableStateFlow<PlaySettings?>(PlaySettings())
        val statePath = "$dir/roads-state.json"
        val gate = PlayGate(backgroundScope, enabled = settings) {
            val content = PlayContentRepository(
                api = server.client(dataset = "roads"), dir = dir, bundled = { PlayFixtures.pack },
                bundledTides = { PlayFixtures.tideText(it) }, playOn = { settings.value?.enabled == true },
                now = { 10_000_000L }, io = d, compute = d,
            )
            PlayRepository(
                scope = backgroundScope, goal = Goal.roads, clock = { SystemPlayClock }, content = content,
                places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-roads.json")) },
                store = PlayStateStore(statePath, d), track = MutableStateFlow<List<Fix>>(emptyList()), compute = d,
            )
        }
        gate.start()
        val toggle = PlayToggleFlow(
            PlayOptInGraph(updatePlay = { change -> settings.value = change(settings.value ?: PlaySettings()) }, repo = { gate.current }, timeoutMs = 50),
            enabled = false,
        )
        toggle.onSwitch(true)
        toggle.onStart()
        runCurrent()
        val repo = gate.current!!
        val entry = LogEntry("e1", LogKind.treasure, "Sea glass", "Weekly treasure", LocalDate(2026, 10, 27), 25, contentId = "t@2026-10-26#01")
        repo.store.update { it.copy(logbook = Logbook(it.logbook.entries + entry)) }
        runCurrent()
        val totalOn = repo.store.state.value.logbook.total

        val before = server.requests.size
        toggle.onSwitch(false)
        toggle.onOffConfirmed()
        runCurrent()
        assertFalse(settings.value!!.enabled)
        assertNull(gate.current, "every Play surface gone (playOrNull null)")
        assertFalse(repo.running)
        assertEquals(before, server.requests.size, "turning off sends no request (PD-1)")
        assertTrue(server.requests.none { it.path.startsWith("/v1/play") && server.requests.indexOf(it) >= before })

        // Nothing deleted: the file on disk still holds the Logbook; turning on again shows it intact (PF14-2).
        val reread = PlayStateStore(statePath, d).load()
        assertTrue(reread.logbook.entries.any { it.id == "e1" }, "Logbook kept on disk")
        assertEquals(totalOn, reread.logbook.total)
        settings.value = settings.value!!.copy(enabled = true)
        runCurrent()
        assertEquals(totalOn, gate.current!!.store.state.value.logbook.total)
    }
}
