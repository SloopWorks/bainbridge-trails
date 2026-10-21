package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.ui.play.map.PlayChipRow.Layout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ux.md §2.1 / §3.5.1 / §5.3: the Play row compacts by measurement (row 2 = width − 32 − 56dp): the Challenges chip
 * first, then the Play chip drops its rank title; at font scale ≥ 1.3 both are compact; at ≥ 1.5 the row wraps.
 * Widths are the chips' measured dp at 1.0× (labelLarge, Roboto): "40 · Walk-on" ≈ 125, "1 challenge this week" ≈ 185,
 * compact Play chip ≈ 96 (min width), compact Challenges chip ≈ 58.
 */
class PlayChipRowTest {
    private fun layout(screenDp: Float, fontScale: Float = 1f, play: Float = 125f, challenges: Float? = 185f, s: Float = 1f) =
        PlayChipRow.layoutFor(
            availableDp = PlayChipRow.rowWidthDp(screenDp),
            playFullDp = play * s,
            playCompactDp = 96f * s,
            challengesFullDp = challenges?.let { it * s },
            challengesCompactDp = challenges?.let { 58f * s },
            fontScale = fontScale,
        )

    @Test
    fun rowWidthKeepsTheMarginsAndTrophyColumnClear() {
        assertEquals(272f, PlayChipRow.rowWidthDp(360f))
        assertEquals(323f, PlayChipRow.rowWidthDp(411f))
    }

    @Test
    fun fullAt411() {
        assertEquals(Layout(challengesCompact = false, playCompact = false, wrap = false), layout(411f))
    }

    @Test
    fun challengesCompactFirstAt360() {
        assertEquals(Layout(challengesCompact = true, playCompact = false, wrap = false), layout(360f))
    }

    @Test
    fun rankTitleDroppedWhenStillTooWide() {
        // "1,240 · Madrona Wanderer" ≈ 215dp: + compact challenges (8 + 58) = 281 > 272.
        assertEquals(Layout(challengesCompact = true, playCompact = true, wrap = false), layout(360f, play = 215f))
        // Without a Challenges chip the Play chip alone still compacts when it does not fit.
        assertEquals(Layout(challengesCompact = false, playCompact = true, wrap = false), layout(320f, play = 260f, challenges = null))
        assertEquals(Layout(challengesCompact = false, playCompact = false, wrap = false), layout(360f, challenges = null))
    }

    @Test
    fun bothCompactFromOnePointThree() {
        for (scale in listOf(1.3f, 1.4f, 1.49f)) {
            assertEquals(Layout(challengesCompact = true, playCompact = true, wrap = false), layout(411f, fontScale = scale, s = scale), "at $scale")
        }
        assertEquals(Layout(challengesCompact = false, playCompact = false, wrap = false), layout(411f, fontScale = 1.29f))
    }

    @Test
    fun wrapsFromOnePointFive() {
        for (scale in listOf(1.5f, 1.8f, 2f)) {
            assertEquals(Layout(challengesCompact = true, playCompact = true, wrap = true), layout(411f, fontScale = scale, s = scale), "at $scale")
        }
        // Nothing to wrap without a Challenges chip.
        assertEquals(Layout(challengesCompact = false, playCompact = true, wrap = false), layout(411f, fontScale = 2f, challenges = null))
    }

    @Test
    fun wrapsWhenEvenCompactChipsOverflow() {
        assertEquals(Layout(challengesCompact = true, playCompact = true, wrap = true), layout(240f, play = 215f))
    }
}
