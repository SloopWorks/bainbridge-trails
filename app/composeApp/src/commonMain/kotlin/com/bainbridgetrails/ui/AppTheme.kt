package com.bainbridgetrails.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bainbridgetrails.coverage.TrailStatus

/** Palette from docs/mvp/product.md "Theme notes" (map palette). Final visuals come with the UX spec. */
private val Light = lightColorScheme(
    primary = Color(0xFF26302B),
    onPrimary = Color.White,
    secondary = Color(0xFF9B1FA8),
    background = Color(0xFFECEFE6),
    surface = Color(0xFFECEFE6),
    surfaceContainer = Color(0xFFF4F6EF),
    onSurface = Color(0xFF1E2A24),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFDCE6E1),
    onPrimary = Color(0xFF13201B),
    secondary = Color(0xFFD45CFF),
    background = Color(0xFF1D2925),
    surface = Color(0xFF1D2925),
    surfaceContainer = Color(0xFF243330),
    onSurface = Color(0xFFDCE6E1),
)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/** Status chip colors (placeholder; the map status palette lands with the map work). */
fun TrailStatus.color(dark: Boolean): Color = when (this) {
    TrailStatus.NOT_STARTED -> if (dark) Color(0xFF6E7A74) else Color(0xFF8A948E)
    TrailStatus.PARTWAY -> if (dark) Color(0xFFE0A84A) else Color(0xFFB7791F)
    TrailStatus.NEARLY_DONE -> if (dark) Color(0xFF6FB7D6) else Color(0xFF2B7A99)
    TrailStatus.COMPLETE -> if (dark) Color(0xFFD45CFF) else Color(0xFF9B1FA8)
}
