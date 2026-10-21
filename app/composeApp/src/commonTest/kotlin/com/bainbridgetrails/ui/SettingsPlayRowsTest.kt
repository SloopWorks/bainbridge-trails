package com.bainbridgetrails.ui

import com.bainbridgetrails.ui.PlaySettingsRow.ABOUT
import com.bainbridgetrails.ui.PlaySettingsRow.AMBIENT
import com.bainbridgetrails.ui.PlaySettingsRow.DEBUG_CLOCK
import com.bainbridgetrails.ui.PlaySettingsRow.LOGBOOK
import com.bainbridgetrails.ui.PlaySettingsRow.RANKS
import com.bainbridgetrails.ui.PlaySettingsRow.REPLAY
import com.bainbridgetrails.ui.PlaySettingsRow.SOUNDS
import com.bainbridgetrails.ui.PlaySettingsRow.TOGGLE
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings › Play (ux.md § 3.1): the switch is always there; only the debug clock row is build-gated. */
class SettingsPlayRowsTest {
    @Test
    fun playOffShowsTheToggleOnly() {
        assertEquals(listOf(TOGGLE), playSettingsRows(enabled = false, replay = true, debugClock = true))
        assertEquals(listOf(TOGGLE), playSettingsRows(enabled = false, replay = false, debugClock = false))
    }

    @Test
    fun playOnRevealsTheRows() {
        assertEquals(
            listOf(TOGGLE, SOUNDS, AMBIENT, RANKS, LOGBOOK, ABOUT, REPLAY, DEBUG_CLOCK),
            playSettingsRows(enabled = true, replay = true, debugClock = true),
        )
        // Release (no debug clock) and the quiet day (no replay): the toggle and the core rows stay.
        assertEquals(listOf(TOGGLE, SOUNDS, AMBIENT, RANKS, LOGBOOK, ABOUT), playSettingsRows(enabled = true, replay = false, debugClock = false))
    }
}
