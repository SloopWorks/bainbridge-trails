package com.bainbridgetrails.ui.play

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.ui.play.screens.LogNote
import com.bainbridgetrails.ui.play.screens.LogTrailing
import com.bainbridgetrails.ui.play.screens.LogbookGroup
import com.bainbridgetrails.ui.play.screens.LogbookLogic
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logbook screen (ux.md §3.13, §2.6; product §3.8, PF11). */
class LogbookGroupingTest {
    private var n = 0

    private fun rec(
        kind: LogKind, date: String, marks: Int, walk: String? = null, board: BoardState = BoardState.accepted,
        capped: Boolean = false, what: String = "What $kind", why: String = "Why $kind",
    ) = LogEntry(
        id = "e${n++}", kind = kind, what = what, why = why, date = LocalDate.parse(date), marks = marks, walkId = walk,
        board = board, capped = capped,
    )

    private val today = LocalDate(2026, 11, 1)
    private val distances = mapOf("w1" to 1_200.0, "w2" to 2_000.0, "w3" to 3_400.0)

    /** Appended oldest first, as the store writes them. */
    private val records = listOf(
        rec(LogKind.start_balance, "2026-10-26", 1_296),
        rec(LogKind.treasure, "2026-10-27", 25, walk = "w1"),
        rec(LogKind.coverage, "2026-10-27", 40, walk = "w1"),
        rec(LogKind.ink, "2026-10-29", 80, walk = "w2"),
        rec(LogKind.riddle, "2026-10-29", 40, walk = "w3", board = BoardState.local_only),
        rec(LogKind.complete, "2026-10-29", 100, walk = "w3"),
        rec(LogKind.week, "2026-11-01", 70),
    )

    @Test
    fun groupsByWalkNewestFirst() {
        val m = LogbookLogic.model(records, today)
        assertEquals(listOf("2026-11-01", "2026-10-29", "2026-10-27", "2026-10-26"), m.days.map { it.date.toString() })
        val oct29 = m.days[1].groups
        assertEquals(listOf("w3", "w2"), oct29.map { (it as LogbookGroup.Walk).walkId }, "the later walk of the day first")
        // Each line shows what, why and +n (PF11-1), in the order the walk logged them.
        val w3 = oct29.first()
        assertEquals(listOf("What riddle", "What complete"), w3.rows.map { it.what })
        assertEquals(listOf("Why riddle", "Why complete"), w3.rows.map { it.why })
        assertEquals(listOf(LogTrailing.Marks(40, muted = true), LogTrailing.Marks(100, muted = false)), w3.rows.map { it.trailing })
        assertIs<LogbookGroup.WeekClose>(m.days.first().groups.single(), "a week close is its own group on the Sunday")
        assertIs<LogbookGroup.Loose>(m.days.last().groups.single(), "the start balance belongs to no walk")
        // Totals equal the sum of entries (PF11-4); "this week" = week id 2026-10-26.
        assertEquals(records.sumOf { it.marks }, m.totals.total)
        assertEquals(records.sumOf { it.marks }, m.totals.week)
        assertEquals("Salal Scout", m.totals.rank.title)
        assertEquals(3_000 - 1_651, m.totals.toNext)
        // Walk subheader text: "Walk · 2.0 km" from the walk's distance.
        assertEquals("Walk · 2.0 km", LogbookLogic.walkLabel("Walk · %1\$s", distances["w2"], Units.KILOMETRES))
        assertEquals("Walk", LogbookLogic.walkLabel("Walk · %1\$s", null, Units.KILOMETRES))
    }

    @Test
    fun localOnlyShowsNote() {
        val rows = LogbookLogic.model(records, today).days.flatMap { d -> d.groups.flatMap { it.rows } }
        val riddle = rows.single { it.kind == LogKind.riddle }
        assertEquals(LogNote.LocalOnly, riddle.note, "play_log_local_only under the line (PF11-2)")
        assertEquals(LogTrailing.Marks(40, muted = true), riddle.trailing, "number in onSurfaceVariant")
        assertTrue(rows.filter { it.kind != LogKind.riddle }.all { it.note == null })
        // A capped find (+0, kind found) says why: play_log_cap; a stamp has no number; a visit nothing at all.
        val more = LogbookLogic.model(
            listOf(rec(LogKind.egg, "2026-10-30", 0, walk = "w4", capped = true), rec(LogKind.stamp, "2026-10-30", 0), rec(LogKind.visit, "2026-10-30", 0, walk = "w4")),
            today,
        ).days.single().groups.flatMap { it.rows }
        assertEquals(LogNote.Cap, more.single { it.kind == LogKind.egg }.note)
        assertEquals(LogTrailing.Stamp, more.single { it.kind == LogKind.stamp }.trailing)
        assertEquals(LogTrailing.None, more.single { it.kind == LogKind.visit }.trailing)
        assertNull(more.single { it.kind == LogKind.visit }.note)
    }

    @Test
    fun neverFormatsATimeOfDay() {
        val timeOfDay = Regex("""\d{1,2}:\d{2}|\b(am|pm|AM|PM)\b|\b\d{1,2}\s?h\b""")
        val m = LogbookLogic.model(records, today)
        val texts = buildList {
            for (d in m.days) {
                add(LogbookLogic.dayLabel(d.date, today))
                for (g in d.groups) if (g is LogbookGroup.Walk) add(LogbookLogic.walkLabel("Walk · %1\$s", distances[g.walkId], Units.MILES))
            }
        }
        assertEquals("Sun, Nov 1", texts.first(), "dates only (PF11-3)")
        assertEquals("Sat, Dec 13, 2025", LogbookLogic.dayLabel(LocalDate(2025, 12, 13), today), "the year only when it isn't this one")
        for (t in texts) assertFalse(timeOfDay.containsMatchIn(t), "no time of day in \"$t\"")
        // The model carries island dates only: no instant, no clock time reaches the screen.
        assertTrue(m.days.all { it.date.toString().matches(Regex("""\d{4}-\d{2}-\d{2}""")) })
    }
}
