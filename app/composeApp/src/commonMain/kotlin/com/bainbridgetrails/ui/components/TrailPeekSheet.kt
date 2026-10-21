package com.bainbridgetrails.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.map_preview_multi_title
import com.bainbridgetrails.resources.map_preview_open
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/** What a map sheet shows about one trail. */
data class TrailSummary(
    val id: String,
    val name: String,
    val subtitle: String,
    val fraction: Double,
    val status: TrailStatus,
    /** Set for a road outside the island goal: why (docs/roads/product.md §6); no status, no Start walk. */
    val notCountedReason: String? = null,
)

/**
 * Trail preview after a map tap (ux.md §3.2, product F2 step 5): Fraunces name, "Park · 1.4 mi", ProgressRing + %
 * + StatusChip, Start walk + View trail. Tap the body or swipe up → [onDetails]; swipe down → [onDismiss].
 */
@Composable
fun TrailPeekSheet(
    trail: TrailSummary,
    onStart: () -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    startLabel: String,
) {
    MapSheet(onDismiss, modifier, onExpand = onDetails) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(top = 2.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.lg),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    trail.name,
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trail.subtitle.isNotEmpty()) {
                    Text(trail.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (trail.notCountedReason != null) {
                    NotCountedChip(Modifier.padding(top = 6.dp))
                    Text(trail.notCountedReason, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    StatusChip(trail.status, Modifier.padding(top = 6.dp))
                }
            }
            if (trail.notCountedReason == null) ProgressRing(trail.fraction, trail.status, size = 56.dp)
        }
        val openLabel = stringResource(LocalGoal.current.s(Res.string.map_preview_open))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
            if (trail.notCountedReason == null) {
                PrimaryButton(startLabel, onStart, Modifier.weight(1f), icon = BtIcons.DirectionsWalk)
                SecondaryButton(openLabel, onDetails, Modifier.weight(1f))
            } else {
                SecondaryButton(openLabel, onDetails, Modifier.weight(1f))
            }
        }
    }
}

/** Overlapping trails under one tap (ux.md §3.2): "Trails on this section" + compact rows; picking one selects it. */
@Composable
fun TrailChooserSheet(
    trails: List<TrailSummary>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MapSheet(onDismiss, modifier, padContent = false) {
        Text(
            stringResource(LocalGoal.current.s(Res.string.map_preview_multi_title)),
            Modifier.semantics { heading() }.padding(horizontal = BtTheme.spacing.screenH).padding(bottom = 4.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        for (t in trails) TrailRow(t.name, t.subtitle, t.fraction, t.status, onClick = { onPick(t.id) })
    }
}
