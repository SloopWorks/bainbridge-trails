package com.bainbridgetrails.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** docs/play/ux.md §2.3: EncounterSheet = MapSheet capped at 60 % of the map height; content scrolls beyond it. */
class MapSheetTest {
    @Test
    fun maxHeightFractionCapsAt60Percent() {
        assertEquals(1200, mapSheetMaxHeight(availablePx = 2000, maxHeightFraction = 0.6f))
        assertEquals(600, mapSheetMaxHeight(availablePx = 1000, maxHeightFraction = 0.6f))
        assertEquals(0, mapSheetMaxHeight(availablePx = 0, maxHeightFraction = 0.6f))
    }

    @Test
    fun defaultIsTodaysBehaviourUncapped() {
        assertEquals(1f, MapSheetDefaults.MaxHeightFraction)
        assertNull(mapSheetMaxHeight(availablePx = 2000, maxHeightFraction = MapSheetDefaults.MaxHeightFraction))
        assertNull(mapSheetMaxHeight(availablePx = null, maxHeightFraction = 0.6f)) // unbounded parent: nothing to cap
    }

    @Test
    fun fractionOutsideZeroToOneIsRejected() {
        assertFailsWith<IllegalArgumentException> { mapSheetMaxHeight(1000, 0f) }
        assertFailsWith<IllegalArgumentException> { mapSheetMaxHeight(1000, 1.2f) }
    }
}
