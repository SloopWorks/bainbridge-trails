package com.bainbridgetrails.sync

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Fixtures
import com.bainbridgetrails.net.Reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncRepositoryTest {
    private val server = FakeServer()
    private val path = DataFixtures.tmpDir("sync") + "/sync.json"
    private var user: String? = "u1"
    private val walks = mutableListOf<LocalWalk>()
    private val local = FakeCoverage { walks.toList() }
    private var changes = 0
    private var ids = 0

    class FakeCoverage(private val walks: () -> List<LocalWalk>) : LocalCoverage {
        var state: CoverageState = CoverageState.EMPTY
        var completed: Set<String> = emptySet()
        var snapshots = 0
        override suspend fun snapshot(): LocalSnapshot {
            snapshots++
            return LocalSnapshot(state, walks())
        }
        override suspend fun mergeRemote(state: CoverageState, completed: Set<String>): Boolean {
            val next = this.state.union(state)
            val nextCompleted = this.completed + completed
            val changed = next != this.state || nextCompleted != this.completed
            this.state = next
            this.completed = nextCompleted
            return changed
        }
    }

    private fun cov(json: String) = CoverageState.decode(json)

    private fun TestScope.repo(scope: CoroutineScope = backgroundScope) = SyncRepository(
        scope = scope,
        api = server.client(token = { user?.let { "tok-$it" } }),
        queuePath = path,
        currentUserId = { user },
        coverage = local,
        datasetVersion = { "v1" },
        onCoverageChanged = { changes++ },
        now = { 1_000_000L },
        backoffMs = emptyList(),
        newWalkId = { "00000000-0000-4000-8000-${(++ids).toString().padStart(12, '0')}" },
    )

    private fun uploads() = server.requests.filter { it.method == "PUT" && it.path == "/v1/coverage" }
        .map { Json.parseToJsonElement(it.body).jsonObject }

    private fun walkIds(upload: kotlinx.serialization.json.JsonObject) =
        (upload["walks"] as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun segments(upload: kotlinx.serialization.json.JsonObject) =
        upload["segments"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun backfillSendsEveryWalkAndBitThenMergesServerState() = runTest {
        local.state = cov("""{"seg-000001":"Aw=="}""")
        walks += LocalWalk("s1700000000000-a", 600)
        walks += LocalWalk("s1700000100000-b", 300)
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage("""{"seg-000001":"Aw==","seg-000009":"AQ=="}""", """["trail-x"]""")))

        val s = repo().syncOnce()

        assertIs<SyncState.Idle>(s)
        val up = uploads().single()
        assertEquals("v1", up["datasetVersion"]!!.jsonPrimitive.content)
        assertEquals("5", up["tickM"].toString())
        assertEquals(mapOf("seg-000001" to "Aw=="), segments(up))
        assertEquals(2, walkIds(up).size)
        assertTrue(up["walks"]!!.jsonArray.all { "durationS" in it.jsonObject })
        assertFalse(server.last("PUT /v1/coverage").body.contains("s1700000000000"), "session ids (times) must never be sent")
        // Merged into local: the server's extra segment + sticky completions.
        assertEquals(cov("""{"seg-000001":"Aw==","seg-000009":"AQ=="}"""), local.state)
        assertEquals(setOf("trail-x"), local.completed)
        assertEquals(1, changes)
    }

    @Test
    fun laterSyncSendsOnlyNewBitsAndNewWalks() = runTest {
        local.state = cov("""{"seg-000001":"Aw=="}""")
        walks += LocalWalk("s1", 600)
        server.on("PUT /v1/coverage") { Reply.json(Fixtures.coverage(Json.parseToJsonElement(it.body).jsonObject["segments"].toString())) }
        val repo = repo()
        repo.syncOnce()

        local.state = cov("""{"seg-000001":"Bw==","seg-000002":"AQ=="}""")
        walks += LocalWalk("s2", 120)
        repo.syncOnce()

        val second = uploads()[1]
        assertEquals(mapOf("seg-000001" to "BA==", "seg-000002" to "AQ=="), segments(second))
        assertEquals(listOf("00000000-0000-4000-8000-000000000002"), walkIds(second))
    }

    @Test
    fun nothingToSendPullsInstead() = runTest {
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage()))
        server.on("GET /v1/coverage", Reply.json(Fixtures.coverage("""{"seg-000005":"Dw=="}""", """["trail-y"]""")))
        val repo = repo()
        walks += LocalWalk("s1", 60)
        repo.syncOnce()
        assertIs<SyncState.Idle>(repo.syncOnce())
        assertEquals(1, server.count("GET /v1/coverage"))
        assertEquals(1, uploads().size)
        assertEquals(cov("""{"seg-000005":"Dw=="}"""), local.state)
        assertEquals(setOf("trail-y"), local.completed)
    }

    @Test
    fun offlineQueueSurvivesRestart() = runTest {
        local.state = cov("""{"seg-000001":"AQ=="}""")
        walks += LocalWalk("s1", 900)
        server.online = false
        val s = assertIs<SyncState.Pending>(repo().syncOnce())
        assertEquals(1, s.pendingWalks)
        assertIs<ApiError.Offline>(s.error)

        // Restart: walks.json lost (derived data) but the queue file still holds the walk.
        walks.clear()
        server.online = true
        server.requests.clear()
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage()))
        val restarted = repo()
        restarted.load()
        assertEquals(SyncState.Idle(null, 1), restarted.state.value)
        assertIs<SyncState.Idle>(restarted.syncOnce())
        assertEquals(listOf("00000000-0000-4000-8000-000000000001"), walkIds(uploads().single()))
    }

    @Test
    fun walkIdIsStablePerSession() = runTest {
        walks += LocalWalk("s1", 900)
        server.online = false
        val repo = repo()
        repo.syncOnce()
        repo.syncOnce()
        server.online = true
        server.requests.clear()
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage()))
        repo.syncOnce()
        assertEquals(listOf("00000000-0000-4000-8000-000000000001"), walkIds(uploads().single()))
    }

    @Test
    fun implausibleIsNotRetriedAndRaisesNotice() = runTest {
        local.state = cov("""{"seg-000001":"/w=="}""")
        walks += LocalWalk("s1", 10)
        server.on("PUT /v1/coverage", Reply.error(422, "implausible", "\"newlyCoveredM\":900,\"allowedM\":540"))
        server.on("GET /v1/coverage", Reply.json(Fixtures.coverage()))
        val repo = repo()

        assertIs<SyncState.Rejected>(repo.syncOnce())
        assertTrue(repo.rejectedNotice.value)
        assertEquals(cov("""{"seg-000001":"/w=="}"""), local.state, "local progress stays")

        // Same payload is not sent again; with nothing new the next sync only pulls.
        assertIs<SyncState.Idle>(repo.syncOnce())
        assertEquals(1, uploads().size)
        assertEquals(1, server.count("GET /v1/coverage"))

        repo.dismissRejectedNotice()
        assertFalse(repo.rejectedNotice.value)
    }

    @Test
    fun unauthorizedStopsAsSignedOut() = runTest {
        walks += LocalWalk("s1", 60)
        server.on("PUT /v1/coverage", Reply.error(401, "unauthorized"))
        assertEquals(SyncState.SignedOut, repo().syncOnce())
    }

    @Test
    fun signedOutDoesNothing() = runTest {
        user = null
        walks += LocalWalk("s1", 60)
        assertEquals(SyncState.SignedOut, repo().syncOnce())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun otherAccountGetsAFullBackfill() = runTest {
        local.state = cov("""{"seg-000001":"Aw=="}""")
        walks += LocalWalk("s1", 600)
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage("""{"seg-000001":"Aw=="}""")))
        val repo = repo()
        repo.syncOnce()

        repo.onSignedOut(wipe = false)
        user = "u2"
        repo.syncOnce()

        val second = uploads()[1]
        assertEquals(mapOf("seg-000001" to "Aw=="), segments(second))
        assertEquals(walkIds(uploads()[0]), walkIds(second), "same walk ids per install")
    }

    @Test
    fun tooManyWalksSendsOneSmallerUploadNeverASecondBatch() = runTest {
        // The account was already backfilled elsewhere (cap 200). A second batch would always be implausible
        // (its durations exceed the seconds since the first), so walks beyond the cap are dropped, not queued.
        local.state = cov("""{"seg-000001":"Aw=="}""")
        repeat(250) { walks += LocalWalk("s$it", 60L + it) }
        server.on("PUT /v1/coverage") {
            val n = (Json.parseToJsonElement(it.body).jsonObject["walks"] as JsonArray).size
            if (n > 200) Reply.error(400, "too_many_walks") else Reply.json(Fixtures.coverage())
        }
        server.on("GET /v1/coverage", Reply.json(Fixtures.coverage()))
        val repo = repo()
        assertIs<SyncState.Idle>(repo.syncOnce())
        assertEquals(listOf(250, 200), uploads().map { walkIds(it).size })
        assertEquals(mapOf("seg-000001" to "Aw=="), segments(uploads()[1]), "the one upload carries every bit")
        val sent = uploads()[1]["walks"]!!.jsonArray.map { it.jsonObject["durationS"]!!.jsonPrimitive.content.toLong() }
        assertEquals(60L + 50, sent.min(), "the longest walks are kept (largest plausibility allowance)")
        assertEquals(SyncState.Idle(1_000_000L, 0), repo.state.value)
        repo.syncOnce()
        assertEquals(2, uploads().size, "nothing left queued")
    }

    @Test
    fun firstBackfillIsOneUploadOfUpTo2000Walks() = runTest {
        local.state = cov("""{"seg-000001":"Aw=="}""")
        repeat(2100) { walks += LocalWalk("s$it", 60) }
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage()))
        server.on("GET /v1/coverage", Reply.json(Fixtures.coverage()))
        val repo = repo()
        assertIs<SyncState.Idle>(repo.syncOnce())
        assertEquals(listOf(2000), uploads().map { walkIds(it).size })
        repo.syncOnce()
        assertEquals(1, uploads().size, "walks beyond 2000 are not sent in a later (implausible) batch")
    }

    @Test
    fun implausibleWithoutWalksKeepsBitsForTheNextUpload() = runTest {
        // P1-2: bits uploaded without their walk (should not happen any more) are refused; they must not join the
        // baseline, or they would never reach the server once their walk arrives.
        walks += LocalWalk("s1", 600)
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage()))
        val repo = repo()
        repo.syncOnce()

        local.state = cov("""{"seg-000001":"Aw=="}""")
        server.on("PUT /v1/coverage", Reply.error(422, "implausible", "\"newlyCoveredM\":10,\"allowedM\":0"))
        repo.syncOnce()
        assertTrue(walkIds(uploads()[1]).isEmpty())
        assertFalse(repo.rejectedNotice.value, "nothing of the user's was refused yet")

        walks += LocalWalk("s2", 600)
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage()))
        repo.syncOnce()
        val third = uploads()[2]
        assertEquals(mapOf("seg-000001" to "Aw=="), segments(third), "bits are sent again, with their walk")
        assertEquals(1, walkIds(third).size)
    }

    @Test
    fun walksAndCoverageComeFromOneSnapshot() = runTest {
        walks += LocalWalk("s1", 600)
        local.state = cov("""{"seg-000001":"Aw=="}""")
        server.on("PUT /v1/coverage", Reply.json(Fixtures.coverage()))
        repo().syncOnce()
        assertEquals(1, local.snapshots)
    }

    @Test
    fun badRequestSettlesWithoutLooping() = runTest {
        local.state = cov("""{"seg-000001":"AQ=="}""")
        walks += LocalWalk("s1", 60)
        server.on("PUT /v1/coverage", Reply.error(400, "invalid_coverage"))
        server.on("GET /v1/coverage", Reply.json(Fixtures.coverage()))
        val repo = repo()
        assertIs<SyncState.Error>(repo.syncOnce())
        repo.syncOnce()
        assertEquals(1, uploads().size)
        assertFalse(repo.rejectedNotice.value)
    }
}
