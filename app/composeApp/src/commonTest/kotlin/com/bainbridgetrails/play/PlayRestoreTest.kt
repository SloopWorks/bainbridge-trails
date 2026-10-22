package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogCopy
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.TrailRewardInput
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.rules.WalkDerived
import com.bainbridgetrails.play.schedule.DerivedGoal
import com.bainbridgetrails.tracking.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(LogCopy.what(LogKind.restored), entry.what)
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

    private fun meWithLog(total: Int, log: String) =
        Reply.json("""{"dataset":"roads","weekId":"2026-10-26","total":$total,"week":0,"occ":[],"log":$log}""")

    /**
     * Branch review round 1 P0-2: the server-only credits (W-WEEK, kraken retreat, island challenge) reach the Logbook
     * as one accepted entry per row keyed `"<kind>@<week>"`, logged on the week's Sunday; a re-fetch adds nothing and
     * the Logbook total stays equal to the server's.
     */
    @Test
    fun `server-only credits one entry per row and a re-fetch adds nothing`() = runTest {
        val rows = """[{"week":"2026-10-19","kind":"week","marks":70},{"week":"2026-10-26","kind":"retreat","marks":50},""" +
            """{"week":"2026-10-26","kind":"island_challenge","marks":75},{"week":"2026-10-26","kind":"future_kind","marks":5}]"""
        server.on("GET /v1/play/me", meWithLog(2410, rows))
        val r = repo()
        r.start() // reinstall, signed in: the credits, then a restore line for the rest
        runCurrent()
        val lb = r.store.state.value.logbook
        val credits = lb.entries.filter { it.kind in setOf(LogKind.week, LogKind.retreat, LogKind.island_challenge) }
        assertEquals(listOf("week@2026-10-19", "retreat@2026-10-26", "island_challenge@2026-10-26"), credits.map { it.contentId })
        assertTrue(credits.all { it.board == BoardState.accepted })
        assertEquals(listOf(LocalDate(2026, 10, 25), LocalDate(2026, 11, 1), LocalDate(2026, 11, 1)), credits.map { it.date }, "on the week's Sunday")
        assertEquals(2410 - 195, lb.entries.single { it.kind == LogKind.restored }.marks)
        assertEquals(2410, lb.total, "the Logbook total equals the server total")

        // Re-fetch (foreground, sign-in): nothing new.
        r.restore()
        assertEquals(lb, r.store.state.value.logbook)

        // The cron closes another week: exactly one new entry, totals equal again.
        server.on("GET /v1/play/me", meWithLog(2480, rows.dropLast(1) + """,{"week":"2026-10-26","kind":"week","marks":70}]"""))
        r.restore()
        val after = r.store.state.value.logbook
        assertEquals(lb.entries.size + 1, after.entries.size)
        assertEquals("week@2026-10-26", after.entries.last().contentId)
        assertEquals(2480, after.total)
        r.stop()
    }

    // --- Play trails v1.1 (plan D3, D4; review P0-1, P1-7, P1-8; orchestrator ruling on v1.1 → v1 → v1.1) ---------------

    private val trailsDir = DataFixtures.tmpDir("play-restore-trails")
    private var trailsInput: TrailRewardInput? = TrailFixtures.bothDoneIsland

    /** Server keys: climb:100, park1 + parkc Moritani (250 marks). The phone's snapshot also owes high:Peter's, park1 Gazzam, island. */
    private val serverPaid = listOf("climb:100", "park1:Moritani Preserve", "parkc:Moritani Preserve")

    private fun trailsMe(total: Int, paid: List<String> = serverPaid) = Reply.json(
        """{"dataset":"trails","weekId":"2026-10-26","total":$total,"week":0,"perKind":{"treasure":2,"climb":1,"park_first":1,"park_complete":1},""" +
            """"occ":${occ.joinToString(",", "[", "]") { "\"$it\"" }},"paid":${paid.joinToString(",", "[", "]") { "\"$it\"" }}}""",
    )

    private fun TestScope.trailsRepo(claims: PlayClaimQueue? = null): PlayRepository {
        val d = StandardTestDispatcher(testScheduler)
        val content = PlayContentRepository(
            api = server.client(token = { "tok" }), dir = trailsDir, bundled = { PlayFixtures.pack },
            bundledTides = { PlayFixtures.tideText(it) }, playOn = { true }, now = { 0L }, io = d, compute = d,
        )
        var n = 0
        return PlayRepository(
            scope = backgroundScope, goal = Goal.trails, clock = { clock }, content = content,
            places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-trails.json")) },
            store = PlayStateStore("$trailsDir/trails-state.json", d), track = MutableStateFlow<List<Fix>>(emptyList()), compute = d,
            api = server.client(token = { "tok" }), claims = claims,
            signedIn = { signedIn }, walkDerived = { derived }, newId = { "t-${++n}" },
            walkContext = { WalkContext(names = TrailFixtures.names) },
            trailTables = { TrailFixtures.tables }, trailInput = { trailsInput },
        )
    }

    private fun marks(keys: Collection<String>) = keys.sumOf { TrailRewards.marksOf(it) }

    /** Every trails key the Logbook pays: start_trails marks + per-key rows. */
    private fun trailMarks(lb: Logbook) = lb.entries.filter { it.kind == LogKind.start_trails || it.kind in TrailRewards.KINDS }.sumOf { it.marks }

    @Test
    fun `signed-out opt-in with trail progress, then sign-in restores server finds once`() = runTest {
        signedIn = false
        server.on("GET /v1/play/me", trailsMe(5_000))
        val r = trailsRepo()
        r.start()
        runCurrent()
        val local = r.store.state.value
        assertEquals(listOf(LogKind.start_balance, LogKind.start_trails, LogKind.island), local.logbook.entries.map { it.kind })
        val qualifying = TrailRewards.qualifying(TrailFixtures.bothDoneIsland, TrailFixtures.tables)
        assertEquals(qualifying, local.trailPaid, "every owed key joins the device ledger")
        assertTrue(local.trailsStarted)

        signedIn = true
        assertTrue(r.restore(), "start_balance + start_trails + island (no walk) is restorable")
        val st = r.store.state.value
        val restored = st.logbook.entries.single { it.kind == LogKind.restored }
        val localOnly = marks(qualifying - serverPaid.toSet())
        assertEquals(5_000 - local.logbook.total + localOnly, restored.marks, "owed = me.total − credited.total + Σ marks(start-paid keys ∖ me.paid)")
        assertEquals(5_000 + localOnly, st.logbook.total, "the server total plus the keys the server has not paid yet")
        assertTrue(st.trailPaid.containsAll(serverPaid), "seeded with me.paid on the Restored write")
        assertTrue(st.found.containsAll(occ))

        // Again (foreground, a second sign-in): nothing more, ever.
        r.restore()
        r.stop()
        r.start()
        runCurrent()
        assertEquals(st.logbook, r.store.state.value.logbook)
        assertEquals(1, r.store.state.value.logbook.entries.count { it.kind == LogKind.restored })
        r.stop()
    }

    @Test
    fun `foreground restore without a Restored entry does not seed trailPaid`() = runTest {
        signedIn = false
        server.on("GET /v1/play/me", trailsMe(5_000, paid = serverPaid + "high:trail-peters-trail" + "mile:5"))
        trailsInput = TrailFixtures.hayanoDone
        val r = trailsRepo()
        r.start()
        runCurrent()
        // A walk since: the Logbook is no longer restorable.
        r.store.update { it.copy(logbook = it.logbook.add(LogEntry("w1", LogKind.coverage, "New ground", "why", LocalDate(2026, 10, 27), 40, walkId = "s1"))) }
        val before = r.store.state.value
        signedIn = true
        assertFalse(r.restore())
        val after = r.store.state.value
        assertTrue(after.logbook.entries.none { it.kind == LogKind.restored })
        assertEquals(before.trailPaid, after.trailPaid, "P0-1: no Restored write → no seed")
        assertTrue("mile:5" !in after.trailPaid && "high:trail-peters-trail" !in after.trailPaid)
        assertTrue(after.found.containsAll(occ), "finds are still re-marked")
        r.stop()
    }

    /** A v1.1 → v1 → v1.1 phone: v1 restored the Logbook (server total incl. trail marks) and knows no trails ledger. */
    private suspend fun v1Restored(r: PlayRepository) = r.store.update {
        PlayState(logbook = Logbook(listOf(LogEntry("r", LogKind.restored, LogCopy.what(LogKind.restored), LogCopy.why(LogKind.restored), LocalDate(2026, 10, 20), 3_000, board = BoardState.accepted))))
    }

    @Test
    fun `restored v1 Logbook seeds me paid before the trails start`() = runTest {
        server.on("GET /v1/play/me", trailsMe(3_000))
        val r = trailsRepo()
        v1Restored(r)
        r.start()
        runCurrent()
        val st = r.store.state.value
        assertTrue(st.trailsStarted)
        assertTrue(st.trailPaid.containsAll(serverPaid))
        val qualifying = TrailRewards.qualifying(TrailFixtures.bothDoneIsland, TrailFixtures.tables)
        assertEquals(marks(qualifying - serverPaid.toSet()), trailMarks(st.logbook), "only the keys the server has not paid")
        assertEquals(1, st.logbook.entries.count { it.kind == LogKind.restored }, "no second restore")
        r.stop()
    }

    @Test
    fun `restored v1 Logbook offline defers the trails start until the next me read`() = runTest {
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-31T11:00")))
        server.on("GET /v1/play/me", Reply.offline)
        val q = PlayClaimQueue("$trailsDir/claims.json", StandardTestDispatcher(testScheduler))
        val r = trailsRepo(claims = q)
        v1Restored(r)
        r.start()
        runCurrent()
        assertFalse(r.store.state.value.trailsStarted, "deferred: the account's paid keys are unknown")
        assertTrue(r.store.state.value.logbook.entries.none { it.kind == LogKind.start_trails || it.kind in TrailRewards.KINDS })

        // A walk finishes meanwhile: its walk rows are written, no trails key is paid yet.
        val start = PlayFixtures.island("2026-10-31T10:00").toEpochMilliseconds()
        val summary = SessionSummary(
            sessionId = "s1", islandBefore = 0.0, islandAfter = 1.0, trails = emptyList(), verified = true, droppedVehicle = 0,
            droppedSteps = 0, newlyCompleted = listOf(TrailFixtures.PETERS), startedAt = start, endedAt = start + 3_600_000, distanceM = 1_110.0, matchedM = 1_110.0,
        )
        r.gatedHook("s1", emptyList(), summary)
        runCurrent()
        val walked = r.store.state.value
        assertTrue(walked.logbook.entries.any { it.walkId == "s1" && it.kind == LogKind.complete })
        assertTrue(walked.logbook.entries.none { it.kind == LogKind.start_trails || it.kind in TrailRewards.KINDS })
        assertFalse(walked.trailsStarted)

        // Back online: the next /me read seeds, then the deferred start pays the rest once.
        server.on("GET /v1/play/me", trailsMe(3_000))
        r.restore()
        runCurrent()
        val st = r.store.state.value
        assertTrue(st.trailsStarted)
        val qualifying = TrailRewards.qualifying(TrailFixtures.bothDoneIsland, TrailFixtures.tables)
        assertEquals(marks(qualifying - serverPaid.toSet()), trailMarks(st.logbook))
        r.stop()
    }
}
