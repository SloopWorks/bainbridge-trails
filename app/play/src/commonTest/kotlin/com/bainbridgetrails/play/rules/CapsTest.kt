package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.Caps
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** product §3.5: 400 found marks per island day, client-enforced; extra finds still claim, with a 0-mark entry. */
class CapsTest {
    private val day = LocalDate(2026, 10, 27)

    private fun entry(id: String, kind: LogKind, marks: Int, date: LocalDate = day) =
        LogEntry(id = id, kind = kind, what = id, why = "test", date = date, marks = marks, contentId = "$id@2026-10-26")

    @Test
    fun `day cap 390 + 25 → +0 with play_log_cap`() {
        assertEquals(400, Caps().foundPerDay)
        var log = Logbook()
            .add(entry("egg-a", LogKind.egg, 20))
            .add(entry("ink-a", LogKind.ink, 80))
            .add(entry("riddle-a", LogKind.riddle, 120))
            .add(entry("bell-a", LogKind.treasure, 150))
            .add(entry("lore-a", LogKind.lore, 15))
            .add(entry("egg-b", LogKind.egg, 5))
            .add(entry("cov", LogKind.coverage, 300).copy(contentId = null, walkId = "w1")) // walk-derived: never capped
            .add(entry("egg-y", LogKind.egg, 20, date = day.minusDays(1))) // yesterday
        assertEquals(390, log.foundOn(day))
        assertEquals(Capped(0, capped = true), FoundCaps.apply(log.foundOn(day), 25))
        log = log.addFind(entry("sea-glass", LogKind.treasure, 25))
        val e = log.entries.last()
        assertEquals(0, e.marks)
        assertTrue(e.capped)
        assertEquals("play_log_cap", e.noteKey)
        assertEquals(390, log.foundOn(day))
        // under the cap a find pays in full, up to exactly 400
        assertEquals(Capped(10, capped = false), FoundCaps.apply(390, 10))
        assertEquals(Capped(25, capped = false), FoundCaps.apply(0, 25))
        val ok = Logbook().addFind(entry("x", LogKind.treasure, 25)).entries.single()
        assertFalse(ok.capped)
        assertEquals(25, ok.marks)
    }

    private fun LocalDate.minusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() - n)
}
