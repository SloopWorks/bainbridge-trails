package com.bainbridgetrails.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.fraunces_bold
import com.bainbridgetrails.resources.fraunces_semibold
import com.bainbridgetrails.resources.fraunces_semibold_italic
import org.jetbrains.compose.resources.Font

/**
 * Fraunces (SIL OFL 1.1, composeResources/font/OFL.txt): static instances opsz 72, SOFT 50, WONK 0 cut from the
 * Google Fonts variable files with fontTools varLib.instancer (ux.md §1.3).
 */
@Composable
fun frauncesFamily(): FontFamily = FontFamily(
    Font(Res.font.fraunces_semibold, FontWeight.SemiBold),
    Font(Res.font.fraunces_bold, FontWeight.Bold),
    Font(Res.font.fraunces_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
)

/** M3 type scale: Fraunces for display/headline/titleLarge, platform font for everything else. */
fun btTypography(display: FontFamily): Typography {
    val sys = FontFamily.Default
    return Typography(
        displayLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.5).sp),
        displayMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 45.sp, lineHeight = 52.sp),
        displaySmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
        headlineLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        titleSmall = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontFamily = sys, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        bodyMedium = TextStyle(fontFamily = sys, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
        bodySmall = TextStyle(fontFamily = sys, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
        labelLarge = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
        labelSmall = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    )
}

/**
 * App-specific styles (`BtTheme.type`). Numbers that change while visible use [statLive] / [numeric] (system font +
 * tabular figures, no jitter); Fraunces only for numbers that are static once shown.
 */
@Immutable
data class BtExtraType(
    /** Me island %, Celebration %. */
    val statHero: TextStyle,
    /** StatTile value. */
    val statLarge: TextStyle,
    /** Live timer / distance while recording. */
    val statLive: TextStyle,
    /** % in rows, ranks. */
    val numeric: TextStyle,
    /** Small tabular label (ring %, pill timer). */
    val numericLabel: TextStyle,
)

fun btExtraType(display: FontFamily, base: Typography) = BtExtraType(
    statHero = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1).sp),
    statLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp),
    statLive = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, fontFeatureSettings = "tnum"),
    numeric = base.bodyMedium.copy(fontFeatureSettings = "tnum"),
    numericLabel = base.labelMedium.copy(fontFeatureSettings = "tnum"),
)
