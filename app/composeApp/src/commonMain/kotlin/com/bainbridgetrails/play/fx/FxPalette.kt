package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.bainbridgetrails.ui.theme.BtDarkScheme
import com.bainbridgetrails.ui.theme.BtLightScheme
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PlayColors
import com.bainbridgetrails.ui.theme.PlayDark
import kotlin.math.roundToInt

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
    /**
     * The kraken's ink casing (PD-20): [ink] blended 35 % toward the Sound teal (`secondary`, the base scheme's, not a
     * brand swap), opaque, in sRGB. Light `#2B4751`, dark `#C0DAE1`: ΔE 22.6 / 20.4 (Topo / Minimal light) and 22.6
     * (Dark) from not-started over the map land (KrakenInkTest, FxPaletteGuardTest).
     */
    val inkCasing: Color,
    /** Paper inside pins and stamps (`surfaceBright` light / `surfaceContainerHigh` dark; effects §1.1). */
    val paper: Color,
    /** The one role fill of a figure (frog, kraken body): `secondaryContainer`. */
    val figureFill: Color,
    /** Frog Rock's rock: `tertiaryContainer`. */
    val rock: Color,
) {
    /** Every role with its name, for guards and the showcase. */
    fun colors(): List<Pair<String, Color>> = listOf(
        "ink" to ink, "seaGlass" to seaGlass, "lamp" to lamp, "fog" to fog, "inkPool" to inkPool,
        "inkCasing" to inkCasing, "paper" to paper, "figureFill" to figureFill, "rock" to rock,
    )

    companion object {
        fun of(play: PlayColors, dark: Boolean = play == PlayDark): FxPalette {
            val scheme = if (dark) BtDarkScheme else BtLightScheme
            return FxPalette(
                ink = play.ink,
                seaGlass = play.seaGlass,
                lamp = play.lamp,
                fog = play.fog,
                inkPool = play.ink.copy(alpha = 0.35f),
                inkCasing = blendSrgb(play.ink, scheme.secondary, 0.35f),
                paper = if (dark) scheme.surfaceContainerHigh else scheme.surfaceBright,
                figureFill = scheme.secondaryContainer,
                rock = scheme.tertiaryContainer,
            )
        }

        /** Per-channel sRGB blend of opaque colors, rounded to 8 bits (so the pinned hex is exact). */
        internal fun blendSrgb(a: Color, b: Color, t: Float): Color {
            fun ch(x: Float, y: Float) = ((x * 255f) + ((y - x) * 255f) * t).roundToInt().coerceIn(0, 255)
            return Color(red = ch(a.red, b.red), green = ch(a.green, b.green), blue = ch(a.blue, b.blue))
        }
    }
}

/** The palette for the current theme (`BtTheme.colors.play`). */
@Composable
fun rememberFxPalette(): FxPalette {
    val play = BtTheme.colors.play
    return remember(play) { FxPalette.of(play) }
}
