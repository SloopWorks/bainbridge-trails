package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.play.schedule.Gate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Show on map" (ux.md § 2.8, § 3.16): which marker's sheet opens and what the camera fits. */
class PlayFocusLogicTest {
    private fun m(key: String, occ: String, item: String, kind: MarkerKind, lon: Double, lat: Double, found: Boolean = false) =
        PlayMarker(key, occ, item, kind, lon, lat, null, found, Gate.OPEN)

    private val markers = listOf(
        m("tw@2026-10-26#01", "tw@2026-10-26", "treasure-weekly-roads", MarkerKind.TREASURE, -122.50, 47.62, found = true),
        m("tw@2026-10-26#02", "tw@2026-10-26", "treasure-weekly-roads", MarkerKind.TREASURE, -122.55, 47.66),
        m("kraken-roads@2026-10-29", "kraken-roads@2026-10-29", "kraken-roads", MarkerKind.KRAKEN, -122.50, 47.66),
        m("riddle-frog@always", "riddle-frog@always", "riddle-frog", MarkerKind.RIDDLE, -122.52, 47.70),
    )

    @Test
    fun occurrenceKeySelectsFirstUnfoundAndFitsAll() {
        val t = assertNotNull(PlayFocusLogic.target("tw@2026-10-26", markers))
        assertEquals("tw@2026-10-26#02", t.select)
        assertEquals(-122.55, t.west)
        assertEquals(-122.50, t.east)
    }

    @Test
    fun krakenOccurrenceOpensTheAttackSheetAtAPoint() {
        val t = assertNotNull(PlayFocusLogic.target("kraken-roads@2026-10-29", markers))
        assertEquals("kraken-roads@2026-10-29", t.select)
        assertTrue(t.isPoint)
    }

    @Test
    fun placementKeyOrItemIdAndUnknownDropped() {
        assertEquals("tw@2026-10-26#01", PlayFocusLogic.target("tw@2026-10-26#01", markers)?.select)
        assertEquals("riddle-frog@always", PlayFocusLogic.target("riddle-frog", markers)?.select)
        assertNull(PlayFocusLogic.target("nope", markers))
    }
}
