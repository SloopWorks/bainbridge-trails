package com.bainbridgetrails.ui.play.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.rules.Rank
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_rank_up
import com.bainbridgetrails.resources.play_rank_up_a11y
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/**
 * RankUpLine (ux.md § 2.7): one 56dp line in results or the celebration, never a modal (PF6-3): `tertiaryContainer`,
 * large shape, [RankBadge] + `play_rank_up` `titleSmall`; polite `play_rank_up_a11y`. The `confirm` haptic and the
 * 0.96 → 1 settle belong to the host (results, L2-8) so they fire once per walk, not per composition.
 */
@Composable
fun RankUpLine(rank: Rank, modifier: Modifier = Modifier) {
    val title = stringResource(rank.titleRes)
    val spoken = stringResource(Res.string.play_rank_up_a11y, title)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.large)
            .padding(horizontal = BtTheme.spacing.lg, vertical = BtTheme.spacing.sm)
            .clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        RankBadge(rank)
        Text(
            stringResource(Res.string.play_rank_up, title),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
