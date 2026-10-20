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
)
