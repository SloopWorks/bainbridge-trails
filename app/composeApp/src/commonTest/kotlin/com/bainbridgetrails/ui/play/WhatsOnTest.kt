package com.bainbridgetrails.ui.play

import com.bainbridgetrails.play.PlayFixtures
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigResult
import com.bainbridgetrails.play.schedule.Gate
import com.bainbridgetrails.play.schedule.PlayContext
import com.bainbridgetrails.play.schedule.PlayScheduler
import com.bainbridgetrails.ui.play.screens.WhatsOnGate
import com.bainbridgetrails.ui.play.screens.WhatsOnKind
import com.bainbridgetrails.ui.play.screens.WhatsOnLogic
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What's On (ux.md §3.15, §2.8; schedule SD-10, § 5.2): a direct rendering of `PlayScheduler.agenda`. */
class WhatsOnTest {
    private val config = (PlayConfig.decode(PlayFixtures.pack) as PlayConfigResult.Ok).config
    private val scheduler = PlayScheduler(config, PlayFixtures.EmptyPlaces, PlayFixtures.tides, null)
    private val island = TimeZone.of("America/Los_Angeles")
    private val islandTime = "%1\$s island time"

    private fun agendaAt(t: String, goal: Goal = Goal.trails) = scheduler.agenda(PlayFixtures.island(t), PlayContext(goal))

    @Test
    fun rowsFromAgenda() {
        val now = PlayFixtures.island("2026-10-29T14:00")
        val agenda = agendaAt("2026-10-29T14:00")
        val m = WhatsOnLogic.model(agenda, config, Goal.trails, now)
        // Rows are the agenda's active items in the scheduler's order: lore, eggs and the ferry sprite are never rows,
        // a weekly treasure set is one row.
        val expected = agenda.active.map { it.occurrence }.filter { o ->
            o.type in setOf(ItemType.treasure, ItemType.obstacle, ItemType.riddle, ItemType.challenge) && !o.hidden
        }.map { it.itemId }.distinct()
        assertEquals(expected, m.today.map { it.itemId })
        assertTrue("kraken-trails" in expected && "treasure-weekly-trails" in expected && "ch-krakenweek" in expected, "$expected")
        assertTrue(m.today.none { it.itemId.startsWith("lore-") || it.itemId.startsWith("egg-") || it.itemId == "ferry-sprite" })
        val kraken = m.today.single { it.itemId == "kraken-trails" }
        assertEquals(WhatsOnKind.Kraken, kraken.kind)
        assertEquals("The kraken is ashore", kraken.title)
        assertEquals("Thu 6 am – Sun 8 pm", WhatsOnLogic.windowText(kraken.start, kraken.end, island, now, islandTime))
        val treasure = m.today.single { it.kind == WhatsOnKind.Treasures }
        assertEquals(0, treasure.found)
        assertTrue(m.today.filter { it.kind == WhatsOnKind.Riddle }.all { (it.stars ?: 0) >= 1 }, "riddles carry their stars")
        // Coming up = agenda.upcoming (same filter), day of week in island time.
        assertEquals(
            agenda.upcoming.filter { it.type in setOf(ItemType.treasure, ItemType.obstacle, ItemType.riddle, ItemType.challenge) }.map { it.itemId },
            m.coming.map { it.itemId },
        )
        assertTrue(m.coming.any { it.itemId == "ch-sweep" && WhatsOnLogic.dayText(it.start) == "Sat" }, "${m.coming}")
        // Week of the Monday; the day line from agenda.day.
        assertEquals(LocalDate(2026, 10, 26), m.weekOf)
        assertEquals(agenda.day.sun.dawn, m.dawn)
        assertEquals(agenda.day.sun.dusk, m.dusk)
        // Gate glyphs from the item's state.
        assertEquals(WhatsOnGate.Wave, WhatsOnLogic.gateOf(Gate.TIDE_HIGH))
        assertEquals(WhatsOnGate.Wave, WhatsOnLogic.gateOf(Gate.TIDE_UNKNOWN))
        assertEquals(WhatsOnGate.Moon, WhatsOnLogic.gateOf(Gate.SLEEPING))
        assertEquals(WhatsOnGate.Chevron, WhatsOnLogic.gateOf(Gate.OPEN))
    }

    @Test
    fun quietDayOnlyMemorialRow() {
        val now = PlayFixtures.island("2027-03-30T14:00")
        val agenda = agendaAt("2027-03-30T14:00")
        assertTrue(agenda.day.quietDay)
        val m = WhatsOnLogic.model(agenda, config, Goal.trails, now)
        assertTrue(m.quietDay)
        assertEquals(1, m.today.size, "${m.today}")
        val row = m.today.single()
        assertEquals(WhatsOnKind.Memorial, row.kind)
        assertEquals("lore-memorial", row.itemId)
        assertEquals(null, row.marks, "remembrance: no numbers")
        assertTrue(m.coming.isEmpty(), "nothing else on the quiet day")
        assertEquals(null, m.dawn)
        assertEquals(null, m.nextFerry)
    }

    @Test
    fun islandTimeSuffixWhenPhoneZoneDiffers() {
        val now = PlayFixtures.island("2026-10-29T14:00")
        val m = WhatsOnLogic.model(agendaAt("2026-10-29T14:00"), config, Goal.trails, now)
        val kraken = m.today.single { it.itemId == "kraken-trails" }
        val ny = TimeZone.of("America/New_York")
        assertEquals("Thu 6 am – Sun 8 pm island time", WhatsOnLogic.windowText(kraken.start, kraken.end, ny, now, islandTime))
        assertEquals("Thu 6 am – Sun 8 pm", WhatsOnLogic.windowText(kraken.start, kraken.end, island, now, islandTime))
        // Same offsets as the island (Vancouver) is island time.
        assertEquals("Thu 6 am – Sun 8 pm", WhatsOnLogic.windowText(kraken.start, kraken.end, TimeZone.of("America/Vancouver"), now, islandTime))
        // Times keep minutes only when they have them; the day line takes the same suffix rule.
        assertEquals("7:31 am", WhatsOnLogic.timeText(PlayFixtures.island("2026-10-29T07:31")))
        assertEquals("6 pm", WhatsOnLogic.timeText(PlayFixtures.island("2026-10-29T18:00")))
        assertEquals("12 pm", WhatsOnLogic.timeText(PlayFixtures.island("2026-10-29T12:00")))
        assertEquals("12 am", WhatsOnLogic.timeText(PlayFixtures.island("2026-10-29T00:00")))
        assertEquals("7:31 am island time", WhatsOnLogic.withIslandSuffix("7:31 am", ny, now, islandTime))
        assertFalse(WhatsOnLogic.withIslandSuffix("7:31 am", island, now, islandTime).contains("island"))
    }

    /** A11y (review L2-7): riddle stars are drawn as ★ but spoken through the `play_riddle_stars_a11y` plural. */
    @Test
    fun riddleStarsSpokenInWords() {
        val title = "Bought with a bond"
        val drawn = WhatsOnLogic.starsText(2)
        assertEquals("★★", drawn)
        val spoken = WhatsOnLogic.spoken(title, drawn, PlayStringsXml.plural("play_riddle_stars_a11y", 2))
        assertEquals("Bought with a bond, Difficulty 2 stars", spoken)
        assertFalse('★' in spoken)
        assertEquals("Bought with a bond, Difficulty 1 star", WhatsOnLogic.spoken(title, WhatsOnLogic.starsText(1), PlayStringsXml.plural("play_riddle_stars_a11y", 1)))
        // Rows without stars keep their window.
        assertEquals("Weekend sweep, Sat – Sun", WhatsOnLogic.spoken("Weekend sweep", "Sat – Sun", null))
    }
}
