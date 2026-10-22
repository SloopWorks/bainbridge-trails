package com.bainbridgetrails.play

import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Play trails v1.1 plan D1 / D7 (L2-2): the state file gains `trailPaid` and `trailsStarted` with defaults, so a v1 file
 * (G0's `roads-guard.json` `client.v1State`, written by main 2aee859) decodes unchanged, and a v1.1 file round-trips.
 */
class PlayStateCompatTest {
    private val golden = Path("../../data/play/golden/roads-guard.json")
    private val dir = DataFixtures.tmpDir("play-state-compat")

    private fun v1StateJson(): String = SystemFileSystem.source(golden).buffered().use { src ->
        Json.parseToJsonElement(src.readString()).jsonObject["client"]!!.jsonObject["v1State"]!!.jsonPrimitive.content
    }

    @Test
    fun `v1 state decodes with defaults`() = runTest {
        val text = v1StateJson()
        assertFalse("trailPaid" in text || "trailsStarted" in text, "the v1 file predates the ledger")
        val path = "$dir/v1-state.json"
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text) }
        val st = PlayStateStore(path).load()
        assertEquals(emptySet(), st.trailPaid)
        assertFalse(st.trailsStarted)
        assertTrue(st.logbook.entries.isNotEmpty(), "the v1 Logbook is intact")
        assertEquals(st.logbook.entries.size, st.logbook.entries.map { it.kind }.toSet().size, "one entry of every v1 kind")
    }

    @Test
    fun `v1_1 state round-trips through the file`() = runTest {
        val date = LocalDate(2026, 10, 31)
        val state = PlayState(
            logbook = Logbook(
                listOf(
                    LogEntry("a", LogKind.start_trails, "Trail rewards so far", "why", date, 350),
                    LogEntry("b", LogKind.island, "The whole island", "why", date, 1_000, contentId = "island"),
                    LogEntry("c", LogKind.climb, "100 m climbed", "why", date, 50, walkId = "w-0123", contentId = "climb:100"),
                ),
            ),
            trailPaid = setOf("park1:Moritani Preserve", "parkc:Moritani Preserve", "island"),
            trailsStarted = true,
        )
        val path = "$dir/v11-state.json"
        JsonFile(path, PlayState.serializer()).write(state)
        assertEquals(state, JsonFile(path, PlayState.serializer()).read())
        // An unknown future field is ignored (a v1.2 file read by v1.1).
        val text = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text.replaceFirst("{", """{"futureField":[1,2],""")) }
        assertEquals(state, JsonFile(path, PlayState.serializer()).read())
    }
}
