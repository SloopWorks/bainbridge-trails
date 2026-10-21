package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.data.DatasetProfile
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.PlayClaimDto
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.play.claim.GatedFix
import com.bainbridgetrails.play.claim.GatedSummary
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Privacy review 2026-10-21 P0-1 (product § 3.10, § 5.5): "Also delete … on this phone" clears the local Logbook, the
 * claim queues and this process's claim results, for both goals, with Play on, off, or never built.
 */
class PlayWipeTest {
    private val root = DataFixtures.tmpDir("play-wipe")
    private val privateDir = "$root/private"
    private val legacyDir = "$root/app/play"
    private val server = FakeServer().apply { on("GET /v1/play/content", Reply(304, "")) }
    private val clock = DebugPlayClock(system = { Instant.parse("2026-10-27T19:00:00Z") })

    private val entry = LogEntry("e1", LogKind.coverage, "New ground", "why", LocalDate(2026, 10, 27), 120, walkId = "s1761591600000-ab12")

    private fun exists(path: String) = SystemFileSystem.exists(Path(path))

    /** Both goals' state, both profiles' claim queues, and a legacy (pre-move) state file, all on disk. */
    private suspend fun seed() {
        for (goal in Goal.entries) {
            PlayFiles.store(privateDir, legacyDir, goal).update { it.copy(found = setOf("k-$goal"), logbook = Logbook(listOf(entry))) }
        }
        for (profile in DatasetProfile.entries) {
            PlayClaimQueue(PlayFiles.claimsPath(privateDir, profile)).add(QueuedPlay("s1", "2026.11.0", listOf(PlayClaimDto("a@2026-10-26#0", "treasure", "2026-10-26"))))
        }
        SystemFileSystem.createDirectories(Path(legacyDir))
        SystemFileSystem.sink(Path("$legacyDir/trails-state.json")).close()
    }

    private fun allFiles(): List<String> =
        Goal.entries.flatMap { listOf(PlayFiles.statePath(privateDir, it), PlayFiles.walkIdsPath(privateDir, it), PlayFiles.legacyStatePath(legacyDir, it)) } +
            DatasetProfile.entries.map { PlayFiles.claimsPath(privateDir, it) }

    private fun TestScope.repo(store: PlayStateStore, claims: PlayClaimQueue): PlayRepository {
        val d = StandardTestDispatcher(testScheduler)
        val content = PlayContentRepository(
            api = server.client(dataset = "roads"), dir = "$root/cache", bundled = { PlayFixtures.pack },
            bundledTides = { PlayFixtures.tideText(it) }, playOn = { true }, now = { 0L }, io = d, compute = d,
        )
        return PlayRepository(
            scope = backgroundScope, goal = Goal.roads, clock = { clock }, content = content,
            places = { PlayFixtures.EmptyPlaces }, derived = { null }, store = store,
            track = MutableStateFlow<List<Fix>>(emptyList()), compute = d, claims = claims,
        )
    }

    @Test
    fun `wipe with Play on clears the Logbook, both queues and the claim results`() = runTest {
        seed()
        val store = PlayFiles.store(privateDir, legacyDir, Goal.roads)
        val claims = PlayClaimQueue(PlayFiles.claimsPath(privateDir, DatasetProfile.ROADS))
        val r = repo(store, claims)
        assertEquals(listOf(entry.id), store.load().logbook.entries.map { it.id })
        val t = Instant.parse("2026-10-27T18:00:00Z").toEpochMilliseconds()
        r.claimWalk("s2", listOf(GatedFix(t, 47.62, -122.52), GatedFix(t + 60_000, 47.62, -122.52)), GatedSummary("s2", t, t + 60_000, 10.0, 0.0, emptyList(), true, 0))
        assertTrue(r.claimResults.value.isNotEmpty(), "precondition: a claim pass ran this process")

        PlayFiles.wipe(privateDir, legacyDir, repo = r, currentClaims = claims)

        assertEquals(PlayState(), store.state.value, "in-memory state reset (nothing left to rewrite the file)")
        assertTrue(r.claimResults.value.isEmpty())
        for (f in allFiles()) assertFalse(exists(f), "$f deleted")
        // A later write starts from empty: the old Logbook never comes back.
        store.update { it.copy(hints = mapOf("riddle-bond" to 1)) }
        assertTrue(PlayFiles.store(privateDir, legacyDir, Goal.roads).load().logbook.entries.isEmpty())
        assertTrue(claims.queued().isEmpty())
    }

    @Test
    fun `wipe with Play off and no repository deletes every Play file`() = runTest {
        seed()
        assertTrue(allFiles().count(::exists) >= 6, "precondition: files written")

        PlayFiles.wipe(privateDir, legacyDir, repo = null, currentClaims = null)

        for (f in allFiles()) assertFalse(exists(f), "$f deleted")
        for (goal in Goal.entries) assertEquals(PlayState(), PlayFiles.store(privateDir, legacyDir, goal).load())
    }
}
