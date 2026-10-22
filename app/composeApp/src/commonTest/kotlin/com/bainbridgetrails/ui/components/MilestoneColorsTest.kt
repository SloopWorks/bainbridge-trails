package com.bainbridgetrails.ui.components

import com.bainbridgetrails.ui.theme.BtDarkScheme
import com.bainbridgetrails.ui.theme.BtLightScheme
import com.bainbridgetrails.ui.theme.PlayDark
import com.bainbridgetrails.ui.theme.PlayLight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * docs/play/trails/ux.md § 5.1: `MilestoneMedallion` / `MilestoneBadge` take optional colors whose default is exactly
 * what roads drew before the parameter existed (roads renders unchanged); the Play variant swaps EARNED to tertiary
 * container and the NEXT arc to sea glass, never `primary`.
 */
class MilestoneColorsTest {
    @Test
    fun `default colors equal the roads constants`() {
        for (s in listOf(BtLightScheme, BtDarkScheme)) {
            assertEquals(
                MilestoneColors(
                    earnedFill = s.primaryContainer,
                    earnedRing = s.primary,
                    earnedMark = s.onPrimaryContainer,
                    nextFill = s.surfaceContainerLow,
                    track = s.outlineVariant,
                    nextArc = s.primary,
                    nextMark = s.onSurface,
                    lockedMark = s.onSurfaceVariant.copy(alpha = 0.6f),
                ),
                MilestoneColors.roads(s),
            )
        }
    }

    @Test
    fun `play colors follow ux 5_1 and never use primary`() {
        for ((s, play) in listOf(BtLightScheme to PlayLight, BtDarkScheme to PlayDark)) {
            val c = MilestoneColors.play(s, play)
            assertEquals(s.tertiaryContainer, c.earnedFill)
            assertEquals(s.onTertiaryContainer, c.earnedRing)
            assertEquals(s.onTertiaryContainer, c.earnedMark)
            assertEquals(s.surfaceContainerLow, c.nextFill)
            assertEquals(s.outlineVariant, c.track)
            assertEquals(play.seaGlass, c.nextArc)
            assertEquals(s.onSurface, c.nextMark)
            assertEquals(MilestoneColors.roads(s).lockedMark, c.lockedMark)
            listOf(c.earnedFill, c.earnedRing, c.earnedMark, c.nextArc).forEach { assertNotEquals(s.primary, it) }
        }
    }
}
