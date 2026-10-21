package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.WalkDerived
import com.bainbridgetrails.play.schedule.DerivedGoal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** product §3.6.4 (start balance), §3.6.5 (Logbook restore), PF15-7, PF1-2. */
class PlayRestoreTest {
    private val server = FakeServer()
    private val dir = DataFixtures.tmpDir("play-restore")
    private val clock = DebugPlayClock(system = { Instant.parse("2026-10-27T19:00:00Z") })
    private var signedIn = true
    private val derived = WalkDerived(coverage = 900, complete = 50, squares = 25)
    private val occ = listOf("egg-ferry-rail@2026-01-05", "treasure-weekly-roads@2026-10-19#03")

    init {
        server.on("GET /v1/play/content", Reply(304, ""))
        server.on("GET /v1/play/me", Reply.json("""{"dataset":"roads","weekId":"2026-10-26","total":2410,"week":120,"perKind":{"treasure":9,"egg":1},"occ":${occ.joinToString(",", "[", "]") { "\"$it\"" }}}"""))
    }

    private fun TestScope.repo(): PlayRepository {
        val d = StandardTestDispatcher(testScheduler)
        val content = PlayContentRepository(
            api = server.client(token = { "tok" }, dataset = "roads"), dir = dir, bundled = { PlayFixtures.pack },
            bundledTides = { PlayFixtures.tideText(it) }, playOn = { true }, now = { 0L }, io = d, compute = d,
        )
        var n = 0
        return PlayRepository(
            scope = backgroundScope, goal = Goal.roads, clock = { clock }, content = content,
            places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-roads.json")) },
            store = PlayStateStore("$dir/roads-state.json", d), track = MutableStateFlow<List<Fix>>(emptyList()), compute = d,
            api = server.client(token = { "tok" }, dataset = "roads"),
            signedIn = { signedIn }, walkDerived = { derived }, newId = { "id-${++n}" },
        )
    }

    @Test
    fun `reinstall with 2410 server marks writes one restored entry`() = runTest {
        val r = repo()
        r.start() // Play turned on after a reinstall, signed in
        runCurrent()
        val lb = r.store.state.value.logbook
        val entry = lb.entries.single()
        assertEquals(LogKind.restored, entry.kind)
        assertEquals(PlayRepository.RESTORED_WHAT, entry.what)
        assertEquals(2410, entry.marks)
        assertEquals(2410, lb.total, "the Logbook total equals the server total")
        assertEquals(BoardState.accepted, entry.board)
        assertTrue(r.store.state.value.found.containsAll(occ), "claimed occurrence keys are found again (no second claim)")

        // Off and on again, and a later sign-in: never a second restore, never a start balance on top.
        r.stop()
        r.start()
        runCurrent()
        r.restore()
        assertEquals(1, r.store.state.value.logbook.entries.size)
        assertEquals(2410, r.store.state.value.logbook.total)
    }

    @Test
    fun `start balance once ever`() = runTest {
        signedIn = false
        val r = repo()
        r.start()
        runCurrent()
        val first = r.store.state.value.logbook.entries.single()
        assertEquals(LogKind.start_balance, first.kind)
        assertEquals(975, first.marks)
        r.stop()
        r.start()
        runCurrent()
        assertEquals(1, r.store.state.value.logbook.entries.count { it.kind == LogKind.start_balance }, "PF1-2: never twice")
        assertEquals(0, server.count("GET /v1/play/me"), "signed out: no /v1/play/me")
    }

    @Test
    fun `opt-in offline then sign-in tops the Logbook up to the server total`() = runTest {
        signedIn = false
        val r = repo()
        r.start()
        runCurrent()
        signedIn = true
        r.restore() // AppGraph calls this on sign-in while Play is on
        val lb = r.store.state.value.logbook
        assertEquals(listOf(LogKind.start_balance, LogKind.restored), lb.entries.map { it.kind })
        assertEquals(2410, lb.total)
    }
}
