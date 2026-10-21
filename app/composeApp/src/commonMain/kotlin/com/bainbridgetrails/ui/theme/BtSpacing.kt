package com.bainbridgetrails.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing & sizing (ux.md §1.5), read via `BtTheme.spacing`. */
@Immutable
data class BtSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    /** Horizontal screen margin. */
    val screenH: Dp = 16.dp,
    val sectionGap: Dp = 24.dp,
    /** Every interactive element. */
    val minTouch: Dp = 48.dp,
    /** TrailRow / WalkRow / LeaderRow minimum. */
    val rowHeight: Dp = 72.dp,
    val bottomBarHeight: Dp = 80.dp,
    /** Record disc diameter. */
    val recordFab: Dp = 64.dp,
    /** How far the Record disc rises above the bar. */
    val recordFabRise: Dp = 16.dp,
    val mapButton: Dp = 48.dp,
    /** Content max width (tablets / landscape). */
    val contentMaxWidth: Dp = 600.dp,
    // Play sizes (docs/play/ux.md §1.3), named so nobody improvises.
    /** Play chip / Challenges chip visual height (touch stays [minTouch]). */
    val playChipHeight: Dp = 40.dp,
    /** Top of the Play chrome row below the status-bar inset (search row 8 + 48 + 8). */
    val playRowTop: Dp = 64.dp,
    /** Camera-fit top padding with Play on (+ inset); pure mode keeps 72dp. */
    val playMapTopPadding: Dp = 128.dp,
    /** Map pin figure (z ≥ 14) and its lift above the anchor. */
    val playPin: Dp = 28.dp,
    val playPinLift: Dp = 30.dp,
    /** Map pin figure and lift at z 12.5–14. */
    val playPinSmall: Dp = 22.dp,
    val playPinLiftSmall: Dp = 26.dp,
    /** Every Play marker hit rect (square). */
    val playHit: Dp = 48.dp,
    /** Paper art tile holding an ink glyph: sheets / rows / Logbook. */
    val playArtTileSheet: Dp = 56.dp,
    val playArtTileRow: Dp = 40.dp,
    val playArtTileLog: Dp = 32.dp,
    /** Play progress bar height (status ProgressBar is 8dp). */
    val playBar: Dp = 6.dp,
)
