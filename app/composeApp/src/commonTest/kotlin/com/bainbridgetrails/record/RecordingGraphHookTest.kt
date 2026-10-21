package com.bainbridgetrails.record

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** PD-12, product §3.6.2: the Finalizer's `onGated` provider is null while Play is off and the repository's hook when on. */
class RecordingGraphHookTest {
    private val server = FakeServer().also { it.on("GET /v1/play/content", Reply(304, "")) }
    private val dir = DataFixtures.tmpDir("play-hook")
    private val settings = MutableStateFlow<PlaySettings?>(null)

    @Test
    fun `finalizer provider follows the Play gate`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        var repo: PlayRepository? = null
        val gate = PlayGate(backgroundScope, settings) {
            val content = PlayContentRepository(
                api = server.client(dataset = "roads"), dir = dir, bundled = { PlayFixtures.pack },
                bundledTides = { PlayFixtures.tideText(it) }, playOn = { settings.value?.enabled == true },
                now = { 0L }, io = d, compute = d,
            )
            PlayRepository(
                scope = backgroundScope, goal = Goal.roads, clock = { SystemPlayClock }, content = content,
                places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-roads.json")) },
                store = PlayStateStore("$dir/roads-state.json", d), track = MutableStateFlow<List<Fix>>(emptyList()), compute = d,
            ).also { repo = it }
        }
        val provider = playGatedProvider { gate.current }
        gate.start()
        settings.value = PlaySettings()
        runCurrent()
        assertNull(provider(), "Play off: the Finalizer does no Play work")

        settings.value = PlaySettings(enabled = true)
        runCurrent()
        assertSame(assertNotNull(repo).gatedHook, provider())

        settings.value = PlaySettings(enabled = false)
        runCurrent()
        assertNull(provider())
    }
}
