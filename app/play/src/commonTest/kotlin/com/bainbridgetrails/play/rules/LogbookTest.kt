package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.time.IslandTime
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** product §3.8, PF11, PF1-2, §3.6.4. */
class LogbookTest {
    private val tue = LocalDate(2026, 10, 27)
    private val week = LocalDate(2026, 10, 26)

    private fun e(id: String, kind: LogKind, marks: Int, date: LocalDate = tue, walk: String? = "w1", content: String? = null) =
        LogEntry(id = id, kind = kind, what = "what $id", why = "why $id", date = date, marks = marks, walkId = walk, contentId = content)

    @Test
    fun `totals equal sum`() {
        val log = Logbook()
            .add(e("1", LogKind.coverage, 21))
            .add(e("2", LogKind.day, 15))
            .add(e("3", LogKind.treasure, 25, content = "treasure-weekly-roads@2026-10-26#07"))
            .add(e("4", LogKind.ink, 80, date = LocalDate(2026, 10, 29), walk = "w2", content = "kraken-roads@2026-10-29#0"))
            .add(e("5", LogKind.coverage, 24, date = LocalDate(2026, 10, 25), walk = "w0")) // previous week
            .add(e("6", LogKind.stamp, 0, content = "riddle-frog@2026-10-26"))
        assertEquals(log.entries.sumOf { it.marks }, log.total)
        assertEquals(165, log.total)
        assertEquals(141, log.weekTotal(week))
        assertEquals(week, log.entries[3].weekId)
        assertEquals(LocalDate(2026, 10, 19), log.entries[4].weekId)
        // idempotent per (walk, content): a re-run of the same walk's claims adds nothing
        val again = log.add(e("7", LogKind.treasure, 25, content = "treasure-weekly-roads@2026-10-26#07"))
            .add(e("8", LogKind.coverage, 21))
            .add(e("3", LogKind.egg, 20, content = "egg-x@2026-10-26"))
        assertEquals(log, again)
        assertEquals(log.total, Json.decodeFromString(Logbook.serializer(), Json.encodeToString(Logbook.serializer(), log)).total)
    }

    @Test
    fun `no time of day stored`() {
        // 23:30 PDT on Tue Oct 27 is already Oct 28 in UTC: the entry keeps the island date and nothing finer.
        val lateTue = Instant.parse("2026-10-28T06:30:00Z")
        val entry = LogEntry(
            id = "x", kind = LogKind.treasure, what = "Sea glass on Rose Loop", why = "Weekly treasure, common",
            date = IslandTime.dateOf(lateTue), marks = 25, walkId = "w1", contentId = "treasure-weekly-roads@2026-10-26#07",
        )
        assertEquals(tue, entry.date)
        val json = Json.encodeToString(Logbook.serializer(), Logbook(listOf(entry)))
        assertTrue("2026-10-27" in json, json)
        assertFalse(Regex("""\d{1,2}:\d{2}""").containsMatchIn(json), "a time of day in $json")
        assertFalse(Regex("""\d{4}-\d{2}-\d{2}T""").containsMatchIn(json), "an instant in $json")
        val descriptor = LogEntry.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        assertTrue(fields.none { it.contains("time", ignoreCase = true) || it == "at" || it == "instant" }, "$fields")
        assertTrue(fields.none { descriptor.getElementDescriptor(fields.indexOf(it)).serialName.contains("Instant") }, "$fields")
    }

    @Test
    fun `start balance once across off-on`() {
        // Marcus (§3.9): 8,400 m, 2 trails, 6 squares → 1,190, one "Your walks so far" entry
        val derived = Marks.walkDerived(Goal.trails, 8_400.0, 2, 6)
        var n = 0
        val newId = { "e${n++}" }
        val on = StartBalance.onEnable(Logbook(), derived, tue, newId)
        assertEquals(1, on.entries.size)
        assertEquals(LogKind.start_balance, on.entries.single().kind)
        assertEquals(1_190, on.total)
        assertTrue(on.hasStartBalance)
        // off, then on again: no second entry, marks unchanged (PF1-2)
        val again = StartBalance.onEnable(on, derived, LocalDate(2026, 11, 3), newId)
        assertEquals(on, again)
        // coverage walked while off is credited on the next enable, never as a second start balance (PF14-2)
        val more = Marks.walkDerived(Goal.trails, 9_000.0, 2, 6)
        val later = StartBalance.onEnable(on, more, LocalDate(2026, 11, 10), newId)
        assertEquals(1, later.entries.count { it.kind == LogKind.start_balance })
        assertEquals(1_250, later.total)
        assertEquals(LogKind.coverage, later.entries.last().kind)
        // a restored Logbook (reinstall, §3.6.5) carries the server total: no start balance on top of it
        val restored = Logbook().add(LogEntry("r", LogKind.restored, "Restored from your account", "", tue, 2_410))
        assertEquals(restored, StartBalance.onEnable(restored, derived, tue, newId))
    }

    @Test
    fun `trails kinds appended, not walkDerived, not found`() {
        // D7: stored keywords are the names; v1.1 appends after the v1 kinds (visit was last in v1)
        assertEquals(listOf(LogKind.visit, LogKind.climb, LogKind.high_ground, LogKind.start_trails), LogKind.entries.takeLast(4))
        for (k in listOf(LogKind.climb, LogKind.high_ground, LogKind.start_trails)) {
            assertFalse(k.walkDerived, "$k is not W-COV/W-DONE/W-SQUARE: StartBalance's catch-up must not count it")
            assertFalse(k.found, "$k is not under the daily found cap")
        }
        val e = LogEntry("c", LogKind.climb, "250 m climbed", LogCopy.why(LogKind.climb), tue, 50, walkId = "w1", contentId = "climb:250")
        val log = Logbook().add(e)
        assertEquals(log, log.add(e.copy(id = "c2", walkId = "w2")), "dedupe by (kind, contentId)")
        val json = Json.encodeToString(Logbook.serializer(), log)
        assertEquals(log, Json.decodeFromString(Logbook.serializer(), json))
        assertTrue("\"kind\":\"climb\"" in json, json)
    }
}
