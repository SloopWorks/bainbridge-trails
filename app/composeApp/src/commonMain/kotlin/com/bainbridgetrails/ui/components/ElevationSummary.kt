package com.bainbridgetrails.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.map.formatElevation
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.detail_gain
import com.bainbridgetrails.resources.ic_trending_up
import org.jetbrains.compose.resources.stringResource

/**
 * Elevation gain tile for Trail detail (product F4 step 3: `gain_m` in the user's units). Loss / tiers are cut from the
 * MVP (decisions.md P2), so this is the one elevation fact. "—" when the dataset has no value.
 */
@Composable
fun ElevationSummary(gainM: Double?, units: Units, modifier: Modifier = Modifier) {
    StatTile(
        value = gainM?.let { "↑ " + formatElevation(it, units) } ?: "—",
        label = stringResource(Res.string.detail_gain),
        modifier = modifier,
        icon = Res.drawable.ic_trending_up,
    )
}
