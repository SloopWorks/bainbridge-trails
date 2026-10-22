package com.bainbridgetrails.play

import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.play.claim.WalkDone
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.StartBalance
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.rules.WalkDerived
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

    /**
     * Branch review round 1 P2-11: the session ↔ walk map keeps a walk only while one of its entries is `pending`; once
     * settled the walk leaves the map (the file stays opaque and stable). The challenge walk list is opaque as well.
     */
    @Test
    fun `settled walks leave the walk-id map`() = runTest {
        val other = "s1761595200000-cd34"
        val store = PlayFiles.store(privateDir, legacyDir, Goal.roads)
        store.update { it.copy(logbook = entries(session), walks = listOf(WalkDone(session, 1761595200000L, 2))) }
        val mapPath = PlayFiles.walkIdsPath(privateDir, Goal.roads)
        val map = JsonFile(mapPath, PlayWalkIds.serializer())
        assertEquals(setOf(session), map.read()!!.bySession.keys, "pending: mapped")
        assertFalse(sessionPattern.containsMatchIn(text(PlayFiles.statePath(privateDir, Goal.roads))))

        // The upload settles the walk; another walk is still pending.
        store.update { st ->
            st.copy(logbook = Logbook(st.logbook.entries.map { it.copy(board = BoardState.accepted) } + entries(other).entries.map { it.copy(id = it.id + "o") }))
        }
        assertEquals(setOf(other), map.read()!!.bySession.keys, "the settled walk left the map")
        val onDisk = JsonFile(PlayFiles.statePath(privateDir, Goal.roads), PlayState.serializer()).read()!!
        assertFalse(sessionPattern.containsMatchIn(text(PlayFiles.statePath(privateDir, Goal.roads))))
        val settledId = onDisk.logbook.entries.first().walkId!!
        assertTrue(settledId.startsWith(PlayStateStore.OPAQUE_PREFIX))
        assertEquals(settledId, onDisk.walks.single().walkId, "one opaque id per walk")

        // A later write keeps the same opaque id; all settled → no map file at all.
        store.update { st -> st.copy(logbook = Logbook(st.logbook.entries.map { it.copy(board = BoardState.accepted) })) }
        assertEquals(settledId, JsonFile(PlayFiles.statePath(privateDir, Goal.roads), PlayState.serializer()).read()!!.logbook.entries.first().walkId)
        assertFalse(SystemFileSystem.exists(Path(mapPath)))
        val reread = PlayFiles.store(privateDir, legacyDir, Goal.roads).load()
        assertTrue(reread.logbook.entries.all { it.walkId!!.startsWith(PlayStateStore.OPAQUE_PREFIX) })
    }

    /**
     * Play trails v1.1 (plan L2-2, D1): the trails rows and the device ledger add keys and island dates only — no time of
     * day, no epoch instant, no session id, no coordinate. Pending trails rows carry opaque walk ids on disk like every row.
     */
    @Test
    fun `trail kinds and trailPaid hold no time of day`() = runTest {
        val date = LocalDate(2026, 10, 31)
        var n = 0
        val opted = PlayState(logbook = StartBalance.onEnable(Logbook(), WalkDerived(0, 0, 0), date) { "sb${n++}" }, derivedBase = DerivedBase())
        // The race path (the trails start: start_trails), then a later walk's per-key pending rows.
        val first = WalkMarks.apply(
            opted, Goal.trails, session, date, WalkDerived(500, 100, 25), listOf(TrailFixtures.HAYANO),
            { TrailFixtures.names[it] ?: it }, emptySet(), { "e${n++}" }, trails = TrailWalk(TrailFixtures.tables, TrailFixtures.hayanoDone),
        )
        val second = WalkMarks.apply(
            first, Goal.trails, "s1761595200000-cd34", date, WalkDerived(600, 200, 30), listOf(TrailFixtures.PETERS),
            { TrailFixtures.names[it] ?: it }, emptySet(), { "e${n++}" }, trails = TrailWalk(TrailFixtures.tables, TrailFixtures.bothDoneIsland),
        )
        val kinds = second.logbook.entries.map { it.kind }.toSet()
        assertTrue(kinds.containsAll(listOf(LogKind.start_trails, LogKind.climb, LogKind.high_ground, LogKind.park_first, LogKind.island)), "$kinds")
        assertTrue(second.trailPaid.isNotEmpty())

        val store = PlayFiles.store(privateDir, legacyDir, Goal.trails)
        store.update { second }
        val onDisk = text(PlayFiles.statePath(privateDir, Goal.trails))
        assertFalse(sessionPattern.containsMatchIn(onDisk), "no session id: $onDisk")
        assertFalse(Regex("""T\d{2}:\d{2}""").containsMatchIn(onDisk), "no time of day: $onDisk")
        assertFalse(Regex("""[":,\[]\d{12,}[",\]}]""").containsMatchIn(onDisk), "no epoch instant: $onDisk")
        assertFalse(Regex("""\d{1,3}\.\d{4,}""").containsMatchIn(onDisk), "no coordinate: $onDisk")
        val stored = JsonFile(PlayFiles.statePath(privateDir, Goal.trails), PlayState.serializer()).read()!!
        assertEquals(second.trailPaid, stored.trailPaid, "keys by value")
        assertTrue(stored.trailPaid.all { TrailRewards.kindOf(it) != null }, "${stored.trailPaid}")
        assertTrue(stored.trailsStarted)
        val trailRows = stored.logbook.entries.filter { it.kind in TrailRewards.KINDS && it.walkId != null }
        assertTrue(trailRows.isNotEmpty() && trailRows.all { it.walkId!!.startsWith(PlayStateStore.OPAQUE_PREFIX) })
        assertEquals(second, PlayFiles.store(privateDir, legacyDir, Goal.trails).load(), "reads back as written")
    }
}
