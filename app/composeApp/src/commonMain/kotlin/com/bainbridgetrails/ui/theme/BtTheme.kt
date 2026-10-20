package com.bainbridgetrails.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.bainbridgetrails.data.LocalGoal

val LocalBtColors = staticCompositionLocalOf { BtExtraLight }
val LocalBtSpacing = staticCompositionLocalOf { BtSpacing() }
val LocalBtMotion = staticCompositionLocalOf { BtMotion(reduced = false) }
val LocalBtBrand = staticCompositionLocalOf { BtBrand.TRAILS }
val LocalBtType = staticCompositionLocalOf<BtExtraType> { error("BtTheme not installed") }

/**
 * App theme: Material 3 (colors, type, shapes per ux.md §1) + app tokens. Read app tokens with [BtTheme.colors],
 * [BtTheme.spacing], [BtTheme.type], [BtTheme.motion]; Material roles with `MaterialTheme.*` as usual.
 * Dark follows the system (no in-app override in MVP); [dark] / [reducedMotion] are overridable for the showcase/tests.
 * [brand] follows the goal (LocalGoal, fixed by the flavour: docs/roads/ux.md §1.1): only primary/secondary differ.
 */
@Composable
fun BtTheme(
    dark: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = rememberReducedMotion(),
    brand: BtBrand = BtBrand.of(LocalGoal.current.goal),
    content: @Composable () -> Unit,
) {
    val display = frauncesFamily()
    val typography = remember(display) { btTypography(display) }
    val extraType = remember(display, typography) { btExtraType(display, typography) }
    CompositionLocalProvider(
        LocalBtColors provides (if (dark) BtExtraDark else BtExtraLight).copy(positive = brand.roles(dark).positive),
        LocalBtBrand provides brand,
        LocalBtSpacing provides BtSpacing(),
        LocalBtMotion provides BtMotion(reducedMotion),
        LocalBtType provides extraType,
    ) {
        val scheme = remember(dark, brand) { (if (dark) BtDarkScheme else BtLightScheme).withBrand(brand.roles(dark)) }
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = BtShapes) {
            // Nested themes (showcase override) must not inherit the outer Surface's content color.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

object BtTheme {
    val colors: BtExtraColors @Composable @ReadOnlyComposable get() = LocalBtColors.current
    val spacing: BtSpacing @Composable @ReadOnlyComposable get() = LocalBtSpacing.current
    val type: BtExtraType @Composable @ReadOnlyComposable get() = LocalBtType.current
    val motion: BtMotion @Composable @ReadOnlyComposable get() = LocalBtMotion.current
    val brand: BtBrand @Composable @ReadOnlyComposable get() = LocalBtBrand.current
}
