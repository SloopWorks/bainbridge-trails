package com.bainbridgetrails.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.map_close
import com.bainbridgetrails.resources.map_preview_multi_title
import com.bainbridgetrails.resources.map_preview_open
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** What a map sheet shows about one trail. */
data class TrailSummary(val id: String, val name: String, val subtitle: String, val fraction: Double, val status: TrailStatus)

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Drag distance (dp) that commits a swipe on a map sheet. */
private const val SWIPE_COMMIT_DP = 56f

/**
 * Non-modal sheet over the map (ux.md §3.2): rounded top, drag handle, one detent. Swipe down → [onDismiss];
 * swipe up → [onExpand] (when given). The map behind stays interactive.
 */
@Composable
internal fun MapSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    padContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var drag by remember { mutableFloatStateOf(0f) }
    val dismissLabel = stringResource(Res.string.map_close)
    Column(
        modifier
            .fillMaxWidth()
            .offset { IntOffset(0, drag.coerceAtLeast(0f).roundToInt()) }
            .shadow(8.dp, SheetShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, SheetShape)
            // Swallow taps so they never fall through to the map.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .draggable(
                rememberDraggableState { delta -> drag += delta },
                Orientation.Vertical,
                onDragStopped = {
                    val dp = drag / density.density
                    drag = 0f
                    when {
                        dp > SWIPE_COMMIT_DP -> onDismiss()
                        dp < -SWIPE_COMMIT_DP -> onExpand?.invoke()
                    }
                },
            )
            .semantics { customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true }) }
            .padding(horizontal = if (padContent) BtTheme.spacing.screenH else 0.dp)
            .padding(bottom = if (padContent) BtTheme.spacing.lg else BtTheme.spacing.sm),
    ) {
        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(32.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
        }
        content()
    }
}

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
                StatusChip(trail.status, Modifier.padding(top = 6.dp))
            }
            ProgressRing(trail.fraction, trail.status, size = 56.dp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
            PrimaryButton(startLabel, onStart, Modifier.weight(1f), icon = BtIcons.DirectionsWalk)
            SecondaryButton(stringResource(Res.string.map_preview_open), onDetails, Modifier.weight(1f))
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
            stringResource(Res.string.map_preview_multi_title),
            Modifier.semantics { heading() }.padding(horizontal = BtTheme.spacing.screenH).padding(bottom = 4.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        for (t in trails) TrailRow(t.name, t.subtitle, t.fraction, t.status, onClick = { onPick(t.id) })
    }
}
