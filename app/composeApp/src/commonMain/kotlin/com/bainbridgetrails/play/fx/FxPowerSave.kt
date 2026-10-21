package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable

/**
 * Battery Saver (Android) / Low Power Mode (iOS), updating while it changes. Play treats it as the map half of reduced
 * motion (effects.md § 5, ux.md § 1.4): [FxHostState] stops the clock and draws every effect on its final frame.
 */
@Composable
expect fun rememberPowerSaveMode(): Boolean
