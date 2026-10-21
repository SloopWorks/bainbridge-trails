package com.bainbridgetrails.play

import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Privacy review 2026-10-21 P1-2: Play's state lives in the no-backup dir (moved once from the backed-up app dir), and
 * the file never holds a local session id (`s<epochMs>-hex` = a walk's exact start time): Logbook walk ids are opaque
 * per walk on disk, mapped back to the session in memory through a no-backup map.
 */
class PlayStatePrivacyTest {
    private val root = DataFixtures.tmpDir("play-privacy")
    private val privateDir = "$root/nobackup"
    private val legacyDir = "$root/files/play"
    private val session = "s1761591600000-ab12"
    private val sessionPattern = Regex("""s\d{13}-""")

    private fun text(path: String) = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

    private fun entries(walk: String) = Logbook(
        listOf(
            LogEntry("e1", LogKind.coverage, "New ground", "why", LocalDate(2026, 10, 27), 120, walkId = walk),
            LogEntry("e2", LogKind.treasure, "Treasure", "why", LocalDate(2026, 10, 27), 25, walkId = walk, contentId = "t@2026-10-26#0"),
        ),
    )

    @Test
    fun `state paths sit under the no-backup dir`() {
        for (goal in Goal.entries) {
            assertTrue(PlayFiles.statePath(privateDir, goal).startsWith("$privateDir/play/"), PlayFiles.statePath(privateDir, goal))
            assertTrue(PlayFiles.walkIdsPath(privateDir, goal).startsWith("$privateDir/play/"))
        }
        assertEquals("$privateDir/play", PlayFiles.stateDir(privateDir))
    }

    @Test
    fun `the encoded state holds no session id and reads back as the session`() = runTest {
        val store = PlayFiles.store(privateDir, legacyDir, Goal.roads)
        store.update { it.copy(logbook = entries(session)) }
        assertEquals(session, store.state.value.logbook.entries.first().walkId, "in memory: the session id (results sheet, Logbook links)")

        val onDisk = text(PlayFiles.statePath(privateDir, Goal.roads))
        assertFalse(sessionPattern.containsMatchIn(onDisk), onDisk)
        val stored = JsonFile(PlayFiles.statePath(privateDir, Goal.roads), PlayState.serializer()).read()!!
        val opaque = stored.logbook.entries.map { it.walkId }.toSet().single()!!
        assertNotEquals(session, opaque)
        assertTrue(opaque.startsWith(PlayStateStore.OPAQUE_PREFIX), opaque)

        // Next launch: the no-backup map turns it back into the session id.
        val reread = PlayFiles.store(privateDir, legacyDir, Goal.roads).load()
        assertEquals(listOf(session, session), reread.logbook.entries.map { it.walkId })
    }

    @Test
    fun `without the map (state restored elsewhere) the walk stays opaque, never the time`() = runTest {
        PlayFiles.store(privateDir, legacyDir, Goal.roads).update { it.copy(logbook = entries(session)) }
        SystemFileSystem.delete(Path(PlayFiles.walkIdsPath(privateDir, Goal.roads)))
        val reread = PlayFiles.store(privateDir, legacyDir, Goal.roads)
        val walk = reread.load().logbook.entries.first().walkId!!
        assertTrue(walk.startsWith(PlayStateStore.OPAQUE_PREFIX))
        reread.update { it.copy(hints = mapOf("r" to 1)) }
        assertEquals(walk, JsonFile(PlayFiles.statePath(privateDir, Goal.roads), PlayState.serializer()).read()!!.logbook.entries.first().walkId, "stable")
    }

    @Test
    fun `an existing state file moves once from the backed-up dir`() = runTest {
        val legacy = PlayFiles.legacyStatePath(legacyDir, Goal.trails)
        JsonFile(legacy, PlayState.serializer()).write(PlayState(found = setOf("egg@2026-01-05"), logbook = entries(session)))

        val store = PlayFiles.store(privateDir, legacyDir, Goal.trails)
        val loaded = store.load()
        assertEquals(setOf("egg@2026-01-05"), loaded.found)
        assertEquals(session, loaded.logbook.entries.first().walkId)
        assertFalse(SystemFileSystem.exists(Path(legacy)), "old file deleted")
        val moved = text(PlayFiles.statePath(privateDir, Goal.trails))
        assertFalse(sessionPattern.containsMatchIn(moved), moved)

        // A second launch reads the new file only.
        assertEquals(loaded, PlayFiles.store(privateDir, legacyDir, Goal.trails).load())
    }
}
