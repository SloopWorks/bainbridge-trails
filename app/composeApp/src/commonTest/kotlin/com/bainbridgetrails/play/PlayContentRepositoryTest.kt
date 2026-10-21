package com.bainbridgetrails.play

import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Reply
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** schedule.md § 4.8: bundled default, `GET /v1/play/content?v=` with ETag / 304, last good kept, strict validation. */
class PlayContentRepositoryTest {
    private val server = FakeServer()
    private val dir = DataFixtures.tmpDir("play-content")
    private var clock = 1_000_000L
    private var playOn = true

    private fun TestScope.repo() = StandardTestDispatcher(testScheduler).let { d ->
        PlayContentRepository(
            api = server.client(dataset = "roads"),
            dir = dir,
            bundled = { PlayFixtures.pack },
            bundledTides = { PlayFixtures.tideText(it) },
            playOn = { playOn },
            now = { clock },
            io = d,
            compute = d,
        )
    }

    private val hour = 3_600_000L

    @Test
    fun bundledWhenNoCache() = runTest {
        val s = assertNotNull(repo().load())
        assertEquals(PlayConfigSource.BUNDLED, s.source)
        assertEquals(PlayFixtures.bundledVersion, s.config.version)
        assertNull(s.rejected)
        assertTrue(server.requests.isEmpty(), "loading never fetches")
    }

    @Test
    fun notModifiedKeepsCache() = runTest {
        val newer = PlayFixtures.packWith(PlayFixtures.version("2026.12.0"))
        server.on("GET /v1/play/content", Reply(200, newer, mapOf("ETag" to "\"pc-2026.12.0-abc\"")))
        val first = repo()
        first.load()
        assertTrue(first.refreshIfDue())
        assertEquals("v=${PlayFixtures.bundledVersion}", server.last("GET /v1/play/content").query)
        assertEquals(PlayConfigSource.FRESH, first.state.value!!.source)
        assertEquals("2026.12.0", first.state.value!!.config.version)

        // Next launch, two hours later: the cache is newer than the bundle; the server answers 304.
        clock += 2 * hour
        server.on("GET /v1/play/content", Reply(304, "", mapOf("ETag" to "\"pc-2026.12.0-abc\"")))
        val second = repo()
        assertEquals(PlayConfigSource.CACHED, second.load()!!.source)
        assertTrue(second.refreshIfDue())
        val sent = server.last("GET /v1/play/content")
        assertEquals("v=2026.12.0", sent.query)
        assertEquals("\"pc-2026.12.0-abc\"", sent.ifNoneMatch)
        val s = second.state.value!!
        assertEquals(PlayConfigSource.CACHED, s.source)
        assertEquals("2026.12.0", s.config.version)
        assertNull(s.rejected)

        // Checked just now: no second request within the hour.
        val before = server.count("GET /v1/play/content")
        assertFalse(second.refreshIfDue())
        assertEquals(before, server.count("GET /v1/play/content"))
    }

    @Test
    fun invalidFreshKeepsLastGoodAndSetsRejected() = runTest {
        // Decodes, but the validator rejects it (schema 1 requires the island zone).
        val invalid = PlayFixtures.packWith(PlayFixtures.version("2026.12.0"), "tz" to JsonPrimitive("UTC"))
        server.on("GET /v1/play/content", Reply(200, invalid, mapOf("ETag" to "\"pc-bad\"")))
        val r = repo()
        r.load()
        assertTrue(r.refreshIfDue())
        val s = r.state.value!!
        assertEquals(PlayConfigSource.BUNDLED, s.source)
        assertEquals(PlayFixtures.bundledVersion, s.config.version)
        assertTrue(s.rejected!!.contains("tz"), s.rejected)

        // Not JSON at all: rejected too, still the last good pack.
        clock += 2 * hour
        server.on("GET /v1/play/content", Reply(200, "{not json"))
        assertTrue(r.refreshIfDue())
        assertEquals(PlayFixtures.bundledVersion, r.state.value!!.config.version)
        assertNotNull(r.state.value!!.rejected)

        // A rejected pack is never cached.
        assertEquals(PlayConfigSource.BUNDLED, repo().load()!!.source)
    }

    @Test
    fun oversizeFreshIsRejectedAndLastGoodKept() = runTest {
        // A valid pack padded past the 2 MB cap (privacy review 2026-10-21 P2-4).
        val pad = "x".repeat(ApiClient.MAX_PLAY_BODY_BYTES)
        val huge = PlayFixtures.packWith(PlayFixtures.version("2026.12.0"), "padding" to JsonPrimitive(pad))
        server.on("GET /v1/play/content", Reply(200, huge, mapOf("ETag" to "\"pc-huge\"")))
        val r = repo()
        r.load()
        assertTrue(r.refreshIfDue())
        val s = r.state.value!!
        assertEquals(PlayConfigSource.BUNDLED, s.source)
        assertEquals(PlayFixtures.bundledVersion, s.config.version)
        assertTrue(s.rejected!!.contains("too large"), s.rejected)
        assertNotNull(s.checkedAt, "checked: no retry before the hour")
        assertEquals(PlayConfigSource.BUNDLED, repo().load()!!.source, "never cached")
    }

    @Test
    fun oversizeTideYearIsNotUsedOrCached() = runTest {
        server.on("GET /v1/play/tides/2031", Reply(200, "[" + "0,".repeat(ApiClient.MAX_PLAY_BODY_BYTES / 2) + "0]"))
        val r = repo()
        assertNull(r.tides(kotlinx.datetime.LocalDate(2031, 6, 1)))
        assertEquals(1, server.count("GET /v1/play/tides/2031"))
        assertFalse(kotlinx.io.files.SystemFileSystem.exists(kotlinx.io.files.Path("$dir/tides/${PlayContentRepository.STATION}-2031.json")))
    }

    @Test
    fun olderCachedThanBundledUsesBundled() = runTest {
        val older = PlayFixtures.packWith(PlayFixtures.version("2026.10.3"))
        server.on("GET /v1/play/content", Reply(200, older, mapOf("ETag" to "\"pc-2026.10.3-old\"")))
        val r = repo()
        r.load()
        assertTrue(r.refreshIfDue())
        assertEquals(PlayFixtures.bundledVersion, r.state.value!!.config.version, "an older fresh pack never replaces a newer one")

        clock += 2 * hour
        val s = repo().load()!!
        assertEquals(PlayConfigSource.BUNDLED, s.source)
        assertEquals(PlayFixtures.bundledVersion, s.config.version)
    }

    @Test
    fun playOffMakesNoRequest() = runTest {
        playOn = false
        server.on("GET /v1/play/content", Reply(200, PlayFixtures.pack))
        val r = repo()
        assertEquals(PlayConfigSource.BUNDLED, r.load()!!.source)
        clock += 10 * hour
        assertFalse(r.refreshIfDue())
        assertFalse(r.fetchNow())
        // 2031 is not bundled: with Play on this would fetch the year; off, nothing.
        assertNull(r.tides(kotlinx.datetime.LocalDate(2031, 6, 1)))
        assertTrue(server.requests.none { it.path.startsWith("/v1/play") }, "Play off: no /v1/play request (PF0-2)")
    }
}
