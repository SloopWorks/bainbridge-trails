package com.bainbridgetrails.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_retry
import com.bainbridgetrails.resources.detail_community_completed
import com.bainbridgetrails.resources.detail_community_error
import com.bainbridgetrails.resources.detail_community_few
import com.bainbridgetrails.resources.detail_community_median
import com.bainbridgetrails.resources.detail_community_offline
import com.bainbridgetrails.ui.Format
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Community stats for one trail (public, decisions.md TL-4; server hides counts under 5 as null). */
sealed interface CommunityState {
    data object Loading : CommunityState
    data object Offline : CommunityState
    data object Error : CommunityState

    /** [completedCount] null = fewer than 5 (hidden); [medianFraction] 0–1 or null. */
    data class Loaded(val completedCount: Int?, val medianFraction: Double?) : CommunityState
}

/**
 * Community card (ux.md §3.4, product F4 step 6): "N people have completed this trail" (Fraunces number) + typical
 * progress; hidden small counts read "Be one of the first to finish this trail"; offline / error never spin.
 */
@Composable
fun CommunityCard(state: CommunityState, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(BtTheme.spacing.lg)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            CommunityState.Loading -> {
                SkeletonBlock(220.dp, 20.dp)
                SkeletonBlock(140.dp, 14.dp)
            }
            CommunityState.Offline -> OfflineNote(stringResource(Res.string.detail_community_offline))
            CommunityState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.detail_community_error),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
            }
            is CommunityState.Loaded -> {
                val n = state.completedCount
                if (n == null) {
                    Text(stringResource(Res.string.detail_community_few), style = MaterialTheme.typography.titleMedium)
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(n.toString(), style = BtTheme.type.statLarge)
                        Text(
                            pluralStringResource(Res.plurals.detail_community_completed, n, n).removePrefix("$n").trim(),
                            Modifier.padding(bottom = 5.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                state.medianFraction?.let {
                    Text(
                        stringResource(Res.string.detail_community_median, Format.percentWhole(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
