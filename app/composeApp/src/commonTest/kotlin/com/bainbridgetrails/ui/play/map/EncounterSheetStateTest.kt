package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.play.PlayFixtures
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigResult
import com.bainbridgetrails.play.config.Scope
import com.bainbridgetrails.play.config.TierName
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.schedule.ActiveItem
import com.bainbridgetrails.play.schedule.Gate
import com.bainbridgetrails.play.schedule.Occurrence
import com.bainbridgetrails.play.schedule.ResolvedPlacement
import com.bainbridgetrails.play.schedule.Span
import com.bainbridgetrails.play.time.SunDay
import com.bainbridgetrails.ui.components.GateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** EncounterSheet states (ux.md §2.3; product PF4-2, PF4-3, PF4-4, PF7-2). */
class EncounterSheetStateTest {
    private val config: PlayConfig = (PlayConfig.decode(PlayFixtures.pack) as PlayConfigResult.Ok).config
    private val items = config.items.associateBy { it.id }
    private val copy = EncounterCopy.english { EnglishPlayStrings[it] }

    /** Thu Oct 29, 2026 at 20:00 island time: after civil dusk. */
    private val night = PlayFixtures.island("2026-10-29T20:00")
    private val dawn = PlayFixtures.island("2026-10-30T07:20")
    private val sun = SunDay(null, null, night, null, null)

    private fun occ(key: String, itemId: String, type: ItemType, placements: List<ResolvedPlacement>, resolvable: List<Span> = emptyList(), tideGated: Boolean = false) =
        Occurrence(key, itemId, type, Scope.island, night - 100.hours, night + 100.hours, false, placements, 0, null, true, resolvable, resolvable, tideGated)

    @Test
    fun `gated treasure has no CTA and a GateNote`() {
        val p = ResolvedPlacement("treasure-weekly-trails@2026-10-26#03", 3, "trail:trail-grand-forest-west", "trail-grand-forest-west", listOf("s1"), GeoPoint(lat = 47.65472, lon = -122.55968), "10:4", TierName.agate, 40)
        val o = occ("treasure-weekly-trails@2026-10-26", "treasure-weekly-trails", ItemType.treasure, listOf(p), resolvable = listOf(Span(dawn, dawn + 9.hours)))
        val m = EncounterLogic.model(items.getValue("treasure-weekly-trails"), ActiveItem(o, Gate.SLEEPING), p.key, night, sun, copy, names = { "Grand Forest West" })
        assertEquals(EncounterVariant.TREASURE_ASLEEP, m.variant)
        assertNull(m.walkThere, "PF4-3: no Walk there while dark (absent, never disabled)")
        val gate = assertNotNull(m.gate)
        assertEquals(GateKind.Dark, gate.kind)
        assertEquals("Back in daylight, from 7:20 am", gate.text)
        assertEquals("40 marks", m.reward)
        assertEquals("Agate", m.title)
        // The same treasure in daylight: the CTA to its line, no gate.
        val live = EncounterLogic.model(items.getValue("treasure-weekly-trails"), ActiveItem(o, Gate.OPEN), p.key, dawn + 1.hours, sun, copy, names = { "Grand Forest West" })
        assertEquals(EncounterVariant.TREASURE_LIVE, live.variant)
        assertEquals(WalkTarget.Feature("trail-grand-forest-west"), live.walkThere)
        assertNull(live.gate)
        assertTrue(live.chips.any { it.text == "Washes away Sunday night" })
    }

    @Test
    fun `remembrance has no marks CTA or motion`() {
        val p = ResolvedPlacement("lore-memorial@always", 0, "place:memorial-card", null, emptyList(), GeoPoint(lat = 47.6156, lon = -122.5102), null)
        val o = occ("lore-memorial@always", "lore-memorial", ItemType.lore, listOf(p))
        val m = EncounterLogic.model(items.getValue("lore-memorial"), ActiveItem(o, Gate.VIEW), p.key, night, sun, copy, names = { null })
        assertEquals(EncounterVariant.REMEMBRANCE, m.variant)
        assertNull(m.reward, "PF4-4: no RewardChip")
        assertNull(m.walkThere, "PF4-4: no Walk there")
        assertFalse(m.motion, "PF4-4: no animation")
        assertNull(m.gate)
        assertTrue(m.chips.single().outlined && m.chips.single().text == "Remembrance")
        assertTrue(m.chips.none { it.check }, "no sea glass anywhere")
        assertEquals("Nothing to earn here. Just a place to visit.", m.footnote)
        assertFalse(listOf(m.title, m.body, m.footnote).any { it != null && Regex("\\d+ marks").containsMatchIn(it) })
    }

    @Test
    fun `kraken 50 % row text`() {
        val stretches = listOf(
            ResolvedPlacement("kraken-roads@2026-10-29#0", 0, "stretch:road-manitou-beach-drive-northeast", "road-manitou-beach-drive-northeast", listOf("a"), GeoPoint(lat = 47.65637, lon = -122.51034), null),
            ResolvedPlacement("kraken-roads@2026-10-29#1", 1, "stretch:road-northeast-gertie-johnson-road", "road-northeast-gertie-johnson-road", listOf("b"), GeoPoint(lat = 47.66593, lon = -122.50630), null),
        )
        val day = PlayFixtures.island("2026-10-29T14:00")
        val o = occ("kraken-roads@2026-10-29", "kraken-roads", ItemType.obstacle, stretches, resolvable = listOf(Span(day - 2.hours, day + 2.hours)), tideGated = true)
        val names = mapOf("road-manitou-beach-drive-northeast" to "Manitou Beach Drive Northeast", "road-northeast-gertie-johnson-road" to "Northeast Gertie Johnson Road")
        val m = EncounterLogic.model(
            items.getValue("kraken-roads"), ActiveItem(o, Gate.OPEN, found = setOf("kraken-roads@2026-10-29#1")), o.key, day, sun, copy,
            names = { names[it] }, shares = mapOf("kraken-roads@2026-10-29#0" to 0.5),
            coast = "Manitou",
        )
        assertEquals(EncounterVariant.KRAKEN, m.variant)
        val row = m.stretches.first()
        assertEquals("50% walked. 60% sends it back.", row.text)
        assertEquals(0.5, row.share)
        assertTrue(row.walkable, "the tide is low and it is daylight: the row keeps its walk button")
        assertEquals("You sent it back", m.stretches[1].text)
        assertEquals("60–80 marks a stretch", m.reward)
        assertNull(m.walkThere, "the kraken sheet has no CTA; each row has its own")
        // Tide above 8 ft: every stretch loses its walk button and the sheet says when it drops (PF4-2).
        val high = EncounterLogic.model(items.getValue("kraken-roads"), ActiveItem(o.copy(resolvable = listOf(Span(day + 2.hours, day + 4.hours))), Gate.TIDE_HIGH), o.key, day, sun, copy, names = { names[it] }, coast = "Manitou")
        assertTrue(high.stretches.none { it.walkable })
        assertEquals(GateKind.Tide, high.gate?.kind)
        assertEquals("The tide is high here. The kraken can be sent back from 4 pm", high.gate?.text)
        val unknown = EncounterLogic.model(items.getValue("kraken-roads"), ActiveItem(o, Gate.TIDE_UNKNOWN), o.key, day, sun, copy, names = { names[it] }, coast = "Manitou")
        assertEquals("The kraken is paused until tide times are updated.", unknown.gate?.text)
        assertTrue(unknown.stretches.none { it.walkable })
    }
}
