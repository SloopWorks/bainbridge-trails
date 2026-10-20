package com.bainbridgetrails.data

import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Reply
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommunityRepositoriesTest {
    private val server = FakeServer()
    private val cache = DataFixtures.tmpDir("board") + "/leaderboard.json"
    private var clock = 1_000L
    private var signedIn = true

    private val board = """{"entries":[
        {"rank":1,"userId":"a","displayName":"Trail Fox","islandPct":41.2,"trailsCompleted":30,"isYou":false},
        {"rank":2,"userId":"b","displayName":"Mossy Heron 42","islandPct":40.9,"trailsCompleted":33,"isYou":true}],
        "you":{"rank":2,"userId":"b","displayName":"Mossy Heron 42","islandPct":41.0,"trailsCompleted":33,"isYou":true},"totalRanked":57}"""

    private fun boardRepo() = LeaderboardRepository(server.client(token = { if (signedIn) "t" else null }), cache, { signedIn }, { clock })

    @Test
    fun leaderboardIsCachedForOffline() = runTest {
        server.on("GET /v1/leaderboard", Reply.json(board))
        val r = boardRepo()
        assertIs<ApiResult.Ok<*>>(r.refresh())
        assertEquals(2, r.state.value.board!!.entries.size)
        assertEquals(2, r.state.value.board!!.you!!.rank)
        assertEquals(1_000L, r.state.value.updatedAt)

        // Next launch, offline: cached rows + updated time, plus the error for the banner (F8-3).
        server.online = false
        clock = 9_000L
        val offline = boardRepo()
        offline.refresh()
        val s = offline.state.value
        assertEquals(57, s.board!!.totalRanked)
        assertEquals(1_000L, s.updatedAt)
        assertIs<ApiError.Offline>(s.error)
    }

    @Test
    fun reportHidesRowImmediatelyAndPersists() = runTest {
        server.on("GET /v1/leaderboard", Reply.json(board))
        server.on("POST /v1/report-name", Reply(202, """{"ok":true}"""))
        val r = boardRepo()
        r.refresh()
        assertEquals(ReportResult.Reported, r.reportName("a"))
        assertEquals("""{"userId":"a"}""", server.last("POST /v1/report-name").body)
        assertEquals(listOf("b"), r.state.value.board!!.entries.map { it.userId })

        // Still hidden after a refresh and a restart.
        r.refresh()
        assertEquals(listOf("b"), r.state.value.board!!.entries.map { it.userId })
        val restarted = boardRepo()
        restarted.refresh()
        assertEquals(listOf("b"), restarted.state.value.board!!.entries.map { it.userId })
        // ...and offline from the cache.
        server.online = false
        val offline = boardRepo()
        offline.refresh()
        assertEquals(listOf("b"), offline.state.value.board!!.entries.map { it.userId })
        // A failed report (offline) hides nothing.
        assertIs<ReportResult.Failed>(offline.reportName("b"))
        assertEquals(listOf("b"), offline.state.value.board!!.entries.map { it.userId })
    }

    @Test
    fun reportOfAGoneUserCountsAsReportedAndHidesTheRow() = runTest {
        server.on("GET /v1/leaderboard", Reply.json(board))
        server.on("POST /v1/report-name", Reply.error(404, "not_found"))
        val r = boardRepo()
        r.refresh()
        assertEquals(ReportResult.Reported, r.reportName("a"))
        assertEquals(listOf("b"), r.state.value.board!!.entries.map { it.userId })
    }

    @Test
    fun reportNeedsSignIn() = runTest {
        signedIn = false
        assertEquals(ReportResult.SignInRequired, boardRepo().reportName("a"))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun authChangeDropsYou() = runTest {
        server.on("GET /v1/leaderboard", Reply.json(board))
        val r = boardRepo()
        r.refresh()
        r.onAuthChanged()
        assertNull(r.state.value.board!!.you)
        assertTrue(r.state.value.board!!.entries.none { it.isYou })
    }

    @Test
    fun trailStatsAreCachedFiveMinutes() = runTest {
        server.on(
            "GET /v1/trails/stats",
            Reply.json("""{"datasetVersion":"v","completionThreshold":0.85,"minCount":5,"trails":{
                "trail-a":{"walkers":10,"completedCount":5,"medianPct":63.5},"trail-b":{"walkers":null,"completedCount":null,"medianPct":null}}}"""),
        )
        val repo = TrailStatsRepository(server.client(), { clock })
        repo.get()
        assertEquals(10, repo.state.value.forTrail("trail-a")!!.walkers)
        assertNull(repo.state.value.forTrail("trail-b")!!.walkers)

        clock += 4 * 60_000
        repo.get()
        assertEquals(1, server.count("GET /v1/trails/stats"))
        clock += 2 * 60_000
        repo.get()
        assertEquals(2, server.count("GET /v1/trails/stats"))

        server.online = false
        repo.get(force = true)
        assertIs<ApiError.Offline>(repo.state.value.error)
        assertEquals(10, repo.state.value.forTrail("trail-a")!!.walkers, "keeps the last good stats")
        assertNull(server.last("GET /v1/trails/stats").bearer)
    }
}
