package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PlayColors

/**
 * The colors map effects draw with (engine PF-5, effects.md §1.2): Play's four roles from `BtTheme.colors.play`
 * plus the alpha variants effects.md fixes. Never a status, recording or userTrack hue (FxPaletteGuardTest: ΔE ≥ 20
 * over the map land, light and dark). Effects add roles here, never hex in a renderer.
 */
@Immutable
data class FxPalette(
    /** All Play line art; the kraken. */
    val ink: Color,
    /** Treasures, finds, the "+" of the claim pop. */
    val seaGlass: Color,
    /** Light only: flame, glint core (inside an ink outline or as glow). */
    val lamp: Color,
    /** Exploration fog / spray; the alpha is part of the role. */
    val fog: Color,
    /** The kraken's ink pool (UNDER_STATUS), α ≤ .35 so the coastline stays readable (effects §1.6, §3.1). */
    val inkPool: Color,
    // No ink-casing role yet: effects §3.1's "playInk at α .85" lands ΔE 14–17 from not-started over the map land
    // (FxPaletteGuardTest); the kraken stage settles its value with design.
) {
    /** Every role with its name, for guards and the showcase. */
    fun colors(): List<Pair<String, Color>> = listOf(
        "ink" to ink, "seaGlass" to seaGlass, "lamp" to lamp, "fog" to fog, "inkPool" to inkPool,
    )

    companion object {
        fun of(play: PlayColors): FxPalette = FxPalette(
            ink = play.ink,
            seaGlass = play.seaGlass,
            lamp = play.lamp,
            fog = play.fog,
            inkPool = play.ink.copy(alpha = 0.35f),
        )
    }
}

/** The palette for the current theme (`BtTheme.colors.play`). */
@Composable
fun rememberFxPalette(): FxPalette {
    val play = BtTheme.colors.play
    return remember(play) { FxPalette.of(play) }
}
