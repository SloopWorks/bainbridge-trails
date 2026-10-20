package com.bainbridgetrails.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.bainbridgetrails.data.DatasetProfile

/** The brand's primary/secondary roles (docs/roads/ux.md §1.1). Everything else in the theme is shared. */
@Immutable
data class BrandRoles(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    /** [BtExtraColors.positive]. */
    val positive: Color,
)

/** Empty-state glyph family (ux.md §7): the trail app's water lines, or the roads plat grid. */
enum class EmptyMotif { WaterLines, RoadGrid }

/**
 * Per-goal brand (ux.md §1.1): trails = spruce ink (exactly docs/mvp/ux.md §1.1), roads = survey slate `#2F5068` with
 * spruce swapped in as secondary. Status colours, surfaces, type and shapes are identical in both.
 */
enum class BtBrand(val light: BrandRoles, val dark: BrandRoles, val emptyMotif: EmptyMotif) {
    TRAILS(
        light = BrandRoles(Color(0xFF2E5E4E), Color.White, Color(0xFFCFE6DA), Color(0xFF0E2A20), Color(0xFF93D3B6),
            Color(0xFF3D6E78), Color.White, Color(0xFFD3E7EB), Color(0xFF0F2E35), Color(0xFF2E5E4E)),
        dark = BrandRoles(Color(0xFF93D3B6), Color(0xFF003827), Color(0xFF1A4A3B), Color(0xFFB4EFD3), Color(0xFF2E5E4E),
            Color(0xFFA3CED7), Color(0xFF01363F), Color(0xFF224D56), Color(0xFFC6EAF2), Color(0xFF93D3B6)),
        emptyMotif = EmptyMotif.WaterLines,
    ),
    ROADS(
        light = BrandRoles(Color(0xFF2F5068), Color.White, Color(0xFFD3E2EE), Color(0xFF0B2235), Color(0xFFA8C8E0),
            Color(0xFF2E5E4E), Color.White, Color(0xFFCFE6DA), Color(0xFF0E2A20), Color(0xFF2F5068)),
        dark = BrandRoles(Color(0xFFA8C8E0), Color(0xFF00334D), Color(0xFF1E3A50), Color(0xFFD3E8F8), Color(0xFF2F5068),
            Color(0xFF93D3B6), Color(0xFF003827), Color(0xFF1A4A3B), Color(0xFFB4EFD3), Color(0xFFA8C8E0)),
        emptyMotif = EmptyMotif.RoadGrid,
    ),
    ;

    fun roles(dark: Boolean): BrandRoles = if (dark) this.dark else light

    companion object {
        fun of(profile: DatasetProfile): BtBrand = when (profile) {
            DatasetProfile.TRAILS -> TRAILS
            DatasetProfile.ROADS -> ROADS
        }
    }
}

/** [base] with the brand roles swapped in (surfaceTint follows primary). */
fun ColorScheme.withBrand(r: BrandRoles): ColorScheme = copy(
    primary = r.primary, onPrimary = r.onPrimary, primaryContainer = r.primaryContainer,
    onPrimaryContainer = r.onPrimaryContainer, inversePrimary = r.inversePrimary,
    secondary = r.secondary, onSecondary = r.onSecondary, secondaryContainer = r.secondaryContainer,
    onSecondaryContainer = r.onSecondaryContainer, surfaceTint = r.primary,
)
