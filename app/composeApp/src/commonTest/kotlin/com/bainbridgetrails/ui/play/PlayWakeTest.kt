package com.bainbridgetrails.ui.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayWakeTest {
    @Test
    fun `any tap before 2200 ms skips to lift`() {
        for (at in listOf(0L, 300L, 800L, 2_199L)) {
            val c = PlayWakeClock(reduced = false)
            assertEquals(at.toInt(), c.t(at))
            assertTrue(c.tap(at), "tap at $at skips")
            assertEquals(PlayWakeTimeline.LIFT_MS, c.t(at), "jumps to the lift, never cuts to the end")
            assertEquals(PlayWakeTimeline.LIFT_MS + 100, c.t(at + 100), "then plays on from the lift")
            assertFalse(c.tap(at + 150), "a second tap does nothing")
            assertTrue(c.done(at + (PlayWakeTimeline.TOTAL_MS - PlayWakeTimeline.LIFT_MS)))
        }
        val late = PlayWakeClock(reduced = false)
        assertFalse(late.tap(2_300), "after the lift starts taps pass")
        assertEquals(2_300, late.t(2_300))
        assertTrue(late.blocksInput(2_199) && !late.blocksInput(2_200), "input is held only until the lift")
    }

    @Test
    fun `reduced = static card 1·2 s`() {
        val c = PlayWakeClock(reduced = true)
        val fade = PlayWakeTimeline.REDUCED_FADE_MS
        assertEquals(150, fade, "short3 crossfades")
        assertEquals(1_200, PlayWakeTimeline.REDUCED_CARD_MS)
        // Static: the art is the pre-lift final frame at every moment.
        for (e in listOf(0L, 400L, 1_000L, 1_349L)) assertEquals(PlayWakeTimeline.LIFT_MS, c.t(e))
        assertEquals(0f, c.cardAlpha(0))
        for (e in fade.toLong()..(fade + 1_200L) step 50) assertEquals(1f, c.cardAlpha(e), "card fully shown for 1.2 s, e=$e")
        assertTrue(c.cardAlpha(fade + 1_200L + 75) in 0.01f..0.99f, "short3 crossfade to the map")
        assertFalse(c.done(fade + 1_200L + fade - 1))
        assertTrue(c.done(fade + 1_200L + fade))
        assertEquals(1_500L, PlayWakeTimeline.REDUCED_TOTAL_MS)
    }

    @Test
    fun `full motion runs 3 s with the haptic at 1900`() {
        val c = PlayWakeClock(reduced = false)
        assertFalse(c.done(2_999))
        assertTrue(c.done(3_000))
        assertEquals(1_900, PlayWakeTimeline.HAPTIC_MS)
        assertEquals(1f, c.cardAlpha(500), "the full-motion art is not crossfaded")
    }
}
