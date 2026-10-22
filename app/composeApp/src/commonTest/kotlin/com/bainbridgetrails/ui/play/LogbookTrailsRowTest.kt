package com.bainbridgetrails.ui.play

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.play.rules.LogCopy
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.play.components.TrailUnit
import com.bainbridgetrails.ui.play.components.TrailValue
import com.bainbridgetrails.ui.play.screens.LogRowModel
import com.bainbridgetrails.ui.play.screens.LogbookGroup
import com.bainbridgetrails.ui.play.screens.LogbookLogic
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Logbook rows of the trails rules (docs/play/trails/ux.md § 2.2, UX-2): `climb` / `milestone` re-render from the
 * entry's `contentId` (`climb:250` metres, `mile:10` miles) in the Settings unit, outside composition; an unparseable
 * key keeps the persisted what; a11y speaks the unit through `play_unit_*_a11y`.
 */
class LogbookTrailsRowTest {
    private val day = LocalDate(2026, 10, 31)

    private fun entry(key: String?, kind: LogKind = key?.let(TrailRewards::kindOf) ?: LogKind.climb, what: String = "persisted") =
        LogEntry("e-$key", kind, what, LogCopy.why(kind), day, 50, walkId = "w1", contentId = key)

    /** The row's shown what, as LogbookRow renders it: the kind's template filled with the value text. */
    private fun shown(row: LogRowModel): String {
        val v = row.value ?: return row.what
        return PlayFormat.fill(PlayStringsXml[assertNotNull(LogbookLogic.whatRes(row.kind)).key], v.text)
    }

    /** The row's spoken what: the template filled with `play_unit_*_a11y` (English plural on [TrailValue.pluralQuantity]). */
    private fun spoken(row: LogRowModel): String {
        val v = row.value ?: return row.what
        val unit = PlayFormat.fill(PlayStringsXml.pluralForm(v.unit.spoken.key, v.pluralQuantity), v.number)
        return PlayFormat.fill(PlayStringsXml[assertNotNull(LogbookLogic.whatRes(row.kind)).key], unit)
    }

    @Test
    fun `climb_250 → "820 ft climbed" in miles, "250 m climbed" in km`() {
        val e = TrailRewards.entries(listOf("climb:250", "mile:10"), { null }, day, "w1") { "id" }.let { es -> es.map { it.copy(id = it.contentId!!) } }
        val climb = e.single { it.kind == LogKind.climb }
        assertEquals("250 m climbed", climb.what, "persisted in the stored unit")
        assertEquals("820 ft climbed", shown(LogbookLogic.row(climb, Units.MILES)))
        assertEquals("250 m climbed", shown(LogbookLogic.row(climb, Units.KILOMETRES)))
        val mile = e.single { it.kind == LogKind.milestone }
        assertEquals("10 mi of trail walked", shown(LogbookLogic.row(mile, Units.MILES)))
        assertEquals("16.1 km of trail walked", shown(LogbookLogic.row(mile, Units.KILOMETRES)))
        // A unit switch re-renders the same entry: the model follows the units the screen passes.
        val model = { u: Units -> LogbookLogic.model(e, day, units = u).days.single().groups.single().rows }
        assertEquals(listOf("820 ft climbed", "10 mi of trail walked"), model(Units.MILES).map(::shown))
        assertEquals(listOf("250 m climbed", "16.1 km of trail walked"), model(Units.KILOMETRES).map(::shown))
    }

    @Test
    fun `unparseable contentId → persisted what`() {
        for (key in listOf("climb:", "climb:abc", "mile:1.5", null, "high:trail-x")) {
            val row = LogbookLogic.row(entry(key, LogKind.climb, what = "Climb as written"), Units.MILES)
            assertNull(row.value, "$key")
            assertEquals("Climb as written", shown(row))
        }
        // A key of another kind never re-renders this kind's what; other kinds never re-render at all.
        assertNull(LogbookLogic.row(entry("mile:10", LogKind.climb), Units.MILES).value)
        assertNull(LogbookLogic.row(entry("park1:Gazzam Lake Preserve"), Units.MILES).value)
        assertNull(LogbookLogic.row(entry("high:trail-peters-trail"), Units.MILES).value)
        // No units (the celebration's marks line): the persisted what.
        assertNull(LogbookLogic.row(entry("climb:250")).value)
    }

    @Test
    fun `a11y "820 feet climbed"`() {
        val row = LogbookLogic.row(entry("climb:250"), Units.MILES)
        assertEquals(TrailValue("820", TrailUnit.FEET), row.value)
        assertEquals("820 feet climbed", spoken(row))
        assertEquals("250 metres climbed", spoken(LogbookLogic.row(entry("climb:250"), Units.KILOMETRES)))
        assertEquals("1 mile of trail walked", spoken(LogbookLogic.row(entry("mile:1"), Units.MILES)))
        assertEquals("16.1 kilometres of trail walked", spoken(LogbookLogic.row(entry("mile:10"), Units.KILOMETRES)))
    }

    @Test
    fun `walk group order is the results order`() {
        // WalkMarks appends complete, coverage, then the trails rows; a find logged in between keeps its place.
        val logged = listOf(
            entry("trail-a", LogKind.complete), entry("t", LogKind.treasure), entry(null, LogKind.coverage),
            entry("climb:100"), entry("high:trail-a"), entry("park1:Gazzam Lake Preserve"),
        ).mapIndexed { i, e -> e.copy(id = "e$i") }
        val rows = (LogbookLogic.model(logged, day).days.single().groups.single() as LogbookGroup.Walk).rows
        assertEquals(
            listOf(LogKind.coverage, LogKind.treasure, LogKind.complete, LogKind.high_ground, LogKind.park_first, LogKind.climb),
            rows.map { it.kind },
        )
    }
}
