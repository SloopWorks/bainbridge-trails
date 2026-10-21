package com.bainbridgetrails.sync

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Fixtures
import com.bainbridgetrails.net.PlayClaimDto
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.play.PlayClaimQueue
import com.bainbridgetrails.play.PlayClaims
import com.bainbridgetrails.play.PlaySettled
import com.bainbridgetrails.play.QueuedPlay
import com.bainbridgetrails.play.claim.Claim
import com.bainbridgetrails.play.claim.ClaimKind
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
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
import kotlin.test.assertNotNull
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

    private fun TestScope.repo(
        scope: CoroutineScope = backgroundScope,
        dataset: String = "trails",
        uploadable: ((String) -> Boolean)? = null,
        playPart: (suspend (Map<String, String>, Set<String>) -> PlayPart?)? = null,
    ) = SyncRepository(
        scope = scope,
        api = server.client(token = { user?.let { "tok-$it" } }, dataset = dataset),
        uploadable = { uploadable },
        queuePath = path,
        currentUserId = { user },
        coverage = local,
        datasetVersion = { "v1" },
        onCoverageChanged = { changes++ },
        now = { 1_000_000L },
        backoffMs = emptyList(),
        newWalkId = { "00000000-0000-4000-8000-${(++ids).toString().padStart(12, '0')}" },
        playPart = playPart,
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

    /** Roads (RD-7): only the goal's counted rseg- bits go up; a filtered segment never forces a re-upload. */
    @Test
    fun roadsUploadOnlyUploadableSegmentsWithDataset() = runTest {
        local.state = cov("""{"rseg-000001":"Aw==","rseg-000002":"AQ==","seg-000003":"AQ=="}""")
        walks += LocalWalk("s1", 600)
        server.on("PUT /v1/coverage") { Reply.json(Fixtures.coverage(Json.parseToJsonElement(it.body).jsonObject["segments"].toString())) }
        server.on("GET /v1/coverage", Reply.json(Fixtures.coverage()))
        val repo = repo(dataset = "roads", uploadable = { it == "rseg-000001" })

        repo.syncOnce()
        val up = uploads().single()
        assertEquals(mapOf("rseg-000001" to "Aw=="), segments(up))
        assertEquals("roads", up["dataset"]!!.jsonPrimitive.content)

        repo.syncOnce() // nothing new to send: a pull, not a second upload
        assertEquals(1, uploads().size)
        assertEquals("dataset=roads", server.last("GET /v1/coverage").query)
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

    // --- Play claims on the coverage upload (docs/play/plan.md L2-9; product §3.10, PD-3) ------------------------------

    private val playPath = DataFixtures.tmpDir("sync-play") + "/play-claims.json"
    private val settled = mutableListOf<PlaySettled>()
    private val queue = PlayClaimQueue(playPath)
    private val playProvider: suspend (Map<String, String>, Set<String>) -> PlayPart? = { ids, walks -> queue.part(ids, walks) { settled += it } }

    private fun claim(id: String, kind: String = "treasure", band: Int? = null) = PlayClaimDto(id, kind, "2026-10-26", band)

    private fun play(upload: JsonObject) = upload["play"]?.jsonArray?.map { it.jsonObject }

    private fun playResult(accepted: List<String>, rejected: String = "[]") =
        Fixtures.coverage().trimEnd().removeSuffix("}") + ""","play":{"accepted":${accepted.joinToString(",", "[", "]") { "\"$it\"" }},"rejected":$rejected,"capped":[],"total":85,"week":85}}"""

    @Test
    fun `play elements only for walks in walks`() = runTest {
        // 201 queued walks after a first accepted upload elsewhere: the one upload carries the 200 longest.
        local.state = cov("""{"seg-000001":"Aw=="}""")
        repeat(201) { walks += LocalWalk("s$it", 600L + it) }
        queue.add(QueuedPlay("s200", "2026.11.0", listOf(claim("kept@2026-10-26#0")), dayKey = "2026-10-26"))
        queue.add(QueuedPlay("s0", "2026.11.0", listOf(claim("dropped@2026-10-26#0")))) // the shortest walk: over the cap
        queue.add(QueuedPlay("s-unknown", "2026.11.0", listOf(claim("later@2026-10-26#0")))) // no local walk yet
        server.on("PUT /v1/coverage") {
            val n = (Json.parseToJsonElement(it.body).jsonObject["walks"] as JsonArray).size
            if (n > 200) Reply.error(400, "too_many_walks") else Reply.json(playResult(listOf("kept@2026-10-26#0")))
        }
        assertIs<SyncState.Idle>(repo(playPart = playProvider).syncOnce())

        val up = uploads().last()
        val sent = walkIds(up).toSet()
        val elements = assertNotNull(play(up))
        assertEquals(1, elements.size)
        assertTrue(elements.all { it["walkId"]!!.jsonPrimitive.content in sent }, "every element is tied to walks[]")
        assertEquals("2026.11.0", up["playConfig"]!!.jsonPrimitive.content)
        assertEquals(setOf("kept@2026-10-26#0"), settled.single { it.sessionId == "s200" }.accepted)
        assertEquals(mapOf("dropped@2026-10-26#0" to PlayClaimQueue.OVER_CAP), settled.single { it.sessionId == "s0" }.localOnly)
        assertEquals(listOf("s-unknown"), queue.queued().map { it.sessionId })
    }

    @Test
    fun `fields absent and body byte-identical when play off`() = runTest {
        // PF0-2: no provider (Play never on), a provider that answers null (Play off), and Play on with nothing for
        // this upload's walks all send the pure upload byte for byte.
        suspend fun body(provider: (suspend (Map<String, String>, Set<String>) -> PlayPart?)?): String {
            val srv = FakeServer()
            srv.on("PUT /v1/coverage", Reply.json(Fixtures.coverage()))
            var n = 0
            val cov = FakeCoverage { listOf(LocalWalk("s1", 900), LocalWalk("s2", 60)) }.also { it.state = cov("""{"seg-000001":"Aw=="}""") }
            SyncRepository(
                scope = backgroundScope, api = srv.client(token = { "tok-u1" }), queuePath = DataFixtures.tmpDir("sync-off") + "/sync.json",
                currentUserId = { "u1" }, coverage = cov, datasetVersion = { "v1" }, now = { 1_000_000L }, backoffMs = emptyList(),
                newWalkId = { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" },
                playPart = provider,
            ).syncOnce()
            return srv.last("PUT /v1/coverage").body
        }

        val pure = body(null)
        assertEquals(setOf("datasetVersion", "tickM", "segments", "walks"), Json.parseToJsonElement(pure).jsonObject.keys)
        assertEquals(pure, body { _, _ -> null })
        val other = PlayClaimQueue(DataFixtures.tmpDir("sync-play-off") + "/play-claims.json")
        other.add(QueuedPlay("s-other", "2026.11.0", listOf(claim("x@2026-10-26#0"))))
        assertEquals(pure, body { ids, w -> other.part(ids, w) })
        assertEquals(1, other.queued().size, "an element of a walk without an id stays queued")
    }

    @Test
    fun `payload has no timestamps days marks or coordinates`() = runTest {
        // PF15-4 (contract): from the evaluator's Claim (which carries marks and an instant) to the wire.
        val at = Instant.parse("2026-10-29T20:13:07Z")
        val claims = listOf(
            Claim("treasure-weekly-roads@2026-10-26#07", "treasure-weekly-roads", ClaimKind.treasure, LocalDate(2026, 10, 26), marks = 60, at = at),
            Claim("kraken-roads@2026-10-29#0", "kraken-roads", ClaimKind.ink, LocalDate(2026, 10, 26), marks = 80, band = 95, at = at),
            Claim("riddle-bond@2026-10-26", "riddle-bond", ClaimKind.riddle, LocalDate(2026, 10, 26), marks = 60, at = at),
        )
        queue.add(PlayClaims.element("s1700000000000-a", "2026.11.0", claims, dayKey = LocalDate(2026, 10, 26), hints = mapOf("riddle-bond" to 1)))
        walks += LocalWalk("s1700000000000-a", 900)
        server.on("PUT /v1/coverage", Reply.json(playResult(claims.map { it.key })))
        repo(playPart = playProvider).syncOnce()

        val raw = server.last("PUT /v1/coverage").body
        val up = Json.parseToJsonElement(raw).jsonObject
        val el = assertNotNull(play(up)).single()
        assertEquals(setOf("walkId", "claims", "dayKey", "hints"), el.keys)
        for (c in el["claims"]!!.jsonArray.map { it.jsonObject }) {
            assertTrue(c.keys.all { it in setOf("id", "kind", "weekId", "band") }, "claim keys ${c.keys}")
            assertEquals(c["kind"]!!.jsonPrimitive.content == "ink", "band" in c, "band only on ink")
            assertEquals("2026-10-26", c["weekId"]!!.jsonPrimitive.content, "a Monday, never the day")
        }
        assertEquals("2026-10-26", el["dayKey"]!!.jsonPrimitive.content, "dayKey is the week id, never the day")
        assertEquals(JsonObject(mapOf("riddle-bond" to JsonPrimitive(1))), el["hints"])
        for (banned in listOf("marks", "\"at\"", "time", "\"lat", "\"lon", "2026-10-29T", "20:13", "what", "why", "total", "s1700000000000")) {
            assertFalse(banned in raw, "no $banned in the body")
        }
    }

    @Test
    fun `offline 2 claims sent on reconnect`() = runTest {
        // PF15-1: a walk with 2 claims finishes offline; when connectivity returns, the upload carries both.
        local.state = cov("""{"seg-000001":"AQ=="}""")
        walks += LocalWalk("s1", 900)
        queue.add(QueuedPlay("s1", "2026.11.0", listOf(claim("a@2026-10-26#0"), claim("k@2026-10-29#0", "ink", 80))))
        server.online = false
        val repo = repo(playPart = playProvider)
        assertIs<SyncState.Pending>(repo.syncOnce())
        assertTrue(settled.isEmpty(), "a retryable failure settles nothing")
        assertEquals(1, queue.queued().size)

        server.online = true
        server.requests.clear()
        server.on("PUT /v1/coverage", Reply.json(playResult(listOf("a@2026-10-26#0", "k@2026-10-29#0"))))
        assertIs<SyncState.Idle>(repo.syncOnce())
        val up = uploads().single()
        val el = assertNotNull(play(up)).single()
        assertEquals(walkIds(up).single(), el["walkId"]!!.jsonPrimitive.content)
        assertEquals(listOf("a@2026-10-26#0", "k@2026-10-29#0"), el["claims"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(setOf("a@2026-10-26#0", "k@2026-10-29#0"), settled.single().accepted)
        assertTrue(queue.queued().isEmpty())
    }

    @Test
    fun `invalid play never costs the walks`() = runTest {
        // Play is optional: a 400 about the play fields alone resends the pure upload and settles Play local_only.
        walks += LocalWalk("s1", 900)
        queue.add(QueuedPlay("s1", "2026.11.0", listOf(claim("a@2026-10-26#0"))))
        server.on("PUT /v1/coverage") { if ("\"play\"" in it.body) Reply.error(400, "invalid_play") else Reply.json(Fixtures.coverage()) }
        assertIs<SyncState.Idle>(repo(playPart = playProvider).syncOnce())
        assertEquals(listOf(true, false), uploads().map { "play" in it })
        assertEquals(mapOf("a@2026-10-26#0" to "invalid_play"), settled.single().localOnly)
    }
}
