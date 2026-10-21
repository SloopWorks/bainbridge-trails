package com.bainbridgetrails.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.bainbridgetrails.coverage.TrailStatus

/** Material 3 schemes, exactly docs/mvp/ux.md §1.1. No dynamic color (status hues must never clash with primary). */
val BtLightScheme = lightColorScheme(
    primary = Color(0xFF2E5E4E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE6DA),
    onPrimaryContainer = Color(0xFF0E2A20),
    inversePrimary = Color(0xFF93D3B6),
    secondary = Color(0xFF3D6E78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E7EB),
    onSecondaryContainer = Color(0xFF0F2E35),
    tertiary = Color(0xFF6E5A3A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF1E3C8),
    onTertiaryContainer = Color(0xFF2A1E0A),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F5EF),
    onBackground = Color(0xFF1E2A24),
    surface = Color(0xFFF7F5EF),
    onSurface = Color(0xFF1E2A24),
    surfaceVariant = Color(0xFFE3E6DE),
    onSurfaceVariant = Color(0xFF4A554E),
    surfaceTint = Color(0xFF2E5E4E),
    inverseSurface = Color(0xFF2E3530),
    inverseOnSurface = Color(0xFFEEF1EB),
    outline = Color(0xFF75807A),
    outlineVariant = Color(0xFFC5CBC3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBFAF6),
    surfaceDim = Color(0xFFDCDAD3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F0EA),
    surfaceContainer = Color(0xFFECEBE4),
    surfaceContainerHigh = Color(0xFFE6E5DE),
    surfaceContainerHighest = Color(0xFFE0DFD8),
)

val BtDarkScheme = darkColorScheme(
    primary = Color(0xFF93D3B6),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF1A4A3B),
    onPrimaryContainer = Color(0xFFB4EFD3),
    inversePrimary = Color(0xFF2E5E4E),
    secondary = Color(0xFFA3CED7),
    onSecondary = Color(0xFF01363F),
    secondaryContainer = Color(0xFF224D56),
    onSecondaryContainer = Color(0xFFC6EAF2),
    tertiary = Color(0xFFDCC39A),
    onTertiary = Color(0xFF3D2E10),
    tertiaryContainer = Color(0xFF554525),
    onTertiaryContainer = Color(0xFFF7E0B8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101A16),
    onBackground = Color(0xFFDCE6E1),
    surface = Color(0xFF101A16),
    onSurface = Color(0xFFDCE6E1),
    surfaceVariant = Color(0xFF3F4A44),
    onSurfaceVariant = Color(0xFFBCC8C1),
    surfaceTint = Color(0xFF93D3B6),
    inverseSurface = Color(0xFFDCE6E1),
    inverseOnSurface = Color(0xFF2A3530),
    outline = Color(0xFF87938C),
    outlineVariant = Color(0xFF3F4A44),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF35403B),
    surfaceDim = Color(0xFF101A16),
    surfaceContainerLowest = Color(0xFF0B1410),
    surfaceContainerLow = Color(0xFF17221E),
    surfaceContainer = Color(0xFF1B2722),
    surfaceContainerHigh = Color(0xFF25312C),
    surfaceContainerHighest = Color(0xFF303C37),
)

/**
 * One status's four color roles (ux.md §1.2). `line` = map layers only; `accent` = rings/bars/icons (≥3:1 on surface);
 * `container`/`onContainer` = StatusChip. Never write body text in `accent`.
 */
@Immutable
data class StatusColors(val line: Color, val accent: Color, val container: Color, val onContainer: Color)

/**
 * Play's colour roles (docs/play/ux.md §1.1): one ink for art, one accent for reward, one light, one veil. Never a
 * status, recording, userTrack, error or positive hue (PlayColorsGuardTest: CIE76 ΔE ≥ 20, both themes).
 * - [ink]: all Play line art, stamps, the kraken's ink casing, art-tile glyphs. Never body text; no fill > 24dp² at full α.
 * - [seaGlass]: marks glyph △, treasure fill, Play bar fill, selected-pin ring; as **text** only for a `+n` marks
 *   delta on surface / surfaceContainerLow / mapChrome (≥ 5.2:1). Never a line along the network.
 * - [lamp]: light only (flame, glint core), inside an ink outline or as glow; on dark always on a dark backing.
 * - [fog]: exploration fog / kraken spray; the alpha is part of the token. Never above status halos, lines or labels.
 * - [seaGlassWash]: derived, not a role — sea glass over surfaceContainerLow, the background of a found / done
 *   state. Text on it is `onSurface` only.
 */
@Immutable
data class PlayColors(
    val ink: Color,
    val seaGlass: Color,
    val lamp: Color,
    val fog: Color,
    val seaGlassWash: Color,
)

/** Light: fog `#F4F6F5` @ .55; wash = sea glass @ .14 over surfaceContainerLow (≈ `#D1DED3`). */
val PlayLight = Color(0xFF037245).let { seaGlass ->
    PlayColors(
        ink = Color(0xFF22323C),
        seaGlass = seaGlass,
        lamp = Color(0xFFFFF1C9),
        fog = Color(0x8CF4F6F5),
        seaGlassWash = seaGlass.copy(alpha = 0.14f).compositeOver(BtLightScheme.surfaceContainerLow),
    )
}

/** Dark: fog `#7D9095` @ .25; wash = sea glass @ .18 over surfaceContainerLow (≈ `#1F4438`). */
val PlayDark = Color(0xFF45E0AE).let { seaGlass ->
    PlayColors(
        ink = Color(0xFFCFE0E6),
        seaGlass = seaGlass,
        lamp = Color(0xFFFFF0CF),
        fog = Color(0x407D9095),
        seaGlassWash = seaGlass.copy(alpha = 0.18f).compositeOver(BtDarkScheme.surfaceContainerLow),
    )
}

/** App-specific colors (ux.md §1.2), read via `BtTheme.colors`. */
@Immutable
data class BtExtraColors(
    val notStarted: StatusColors,
    val partway: StatusColors,
    val nearlyDone: StatusColors,
    val complete: StatusColors,
    val nearlyDoneCasing: Color,
    val notCounted: Color,
    val userTrack: Color,
    val userTrackCasing: Color,
    val recording: Color,
    val onRecording: Color,
    val selectionCasing: Color,
    val mapChrome: Color,
    val onMapChrome: Color,
    val waterLine: Color,
    val positive: Color,
    /** Play's four roles (docs/play/ux.md §1.1); brand-independent. Only Play surfaces read them. */
    val play: PlayColors,
)

val BtExtraLight = BtExtraColors(
    notStarted = StatusColors(line = Color(0xFF6E6A64), accent = Color(0xFF6E6A64), container = Color(0xFFE8E6E2), onContainer = Color(0xFF3F3C37)),
    partway = StatusColors(line = Color(0xFFC98A00), accent = Color(0xFFA87400), container = Color(0xFFF8EBC8), onContainer = Color(0xFF5E4200)),
    nearlyDone = StatusColors(line = Color(0xFFE2562A), accent = Color(0xFFE2562A), container = Color(0xFFFCE2D8), onContainer = Color(0xFF7A2A0F)),
    complete = StatusColors(line = Color(0xFF9B1FA8), accent = Color(0xFF9B1FA8), container = Color(0xFFF3DDF6), onContainer = Color(0xFF5E0F68)),
    nearlyDoneCasing = Color(0xFF5A1F0C),
    notCounted = Color(0xBF7D8679),
    userTrack = Color(0xFF0B63CE),
    userTrackCasing = Color(0xFFFFFFFF),
    recording = Color(0xFFC0262D),
    onRecording = Color(0xFFFFFFFF),
    selectionCasing = Color(0xFF1E2A24),
    mapChrome = Color(0xF2FBFAF6),
    onMapChrome = Color(0xFF26302B),
    waterLine = Color(0xFF6F97A2),
    positive = Color(0xFF2E5E4E),
    play = PlayLight,
)

val BtExtraDark = BtExtraColors(
    notStarted = StatusColors(line = Color(0xFFA9A49B), accent = Color(0xFFA9A49B), container = Color(0xFF2E302D), onContainer = Color(0xFFD3CFC7)),
    partway = StatusColors(line = Color(0xFFF0B43C), accent = Color(0xFFF0B43C), container = Color(0xFF42330D), onContainer = Color(0xFFF8D98A)),
    nearlyDone = StatusColors(line = Color(0xFFFF7B47), accent = Color(0xFFFF7B47), container = Color(0xFF4A2112), onContainer = Color(0xFFFFC2A8)),
    complete = StatusColors(line = Color(0xFFD45CFF), accent = Color(0xFFD45CFF), container = Color(0xFF3E1846), onContainer = Color(0xFFF0C2FF)),
    nearlyDoneCasing = Color(0xFF0B1410),
    notCounted = Color(0xCC5E6E66),
    userTrack = Color(0xFF5EA8FF),
    userTrackCasing = Color(0xFF0B1410),
    recording = Color(0xFFFF8A80),
    onRecording = Color(0xFF3B0907),
    selectionCasing = Color(0xFFDCE6E1),
    mapChrome = Color(0xF213201B),
    onMapChrome = Color(0xFFDCE6E1),
    waterLine = Color(0xFF2E5564),
    positive = Color(0xFF93D3B6),
    play = PlayDark,
)

fun BtExtraColors.of(s: TrailStatus): StatusColors = when (s) {
    TrailStatus.NOT_STARTED -> notStarted
    TrailStatus.PARTWAY -> partway
    TrailStatus.NEARLY_DONE -> nearlyDone
    TrailStatus.COMPLETE -> complete
}
