package com.bainbridgetrails.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.ui.Format
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Trail list row (ux.md §4): ProgressRing 40dp, name (2 lines), [subtitle] ("Park · 1.4 mi"), StatusChip (compact
 * under 360dp width; below the subtitle at font scale ≥ 1.5). Whole row is one merged, clickable node.
 */
@Composable
fun TrailRow(
    name: String,
    subtitle: String,
    fraction: Double,
    status: TrailStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    animationDelayMs: Int = 0,
    trailing: (@Composable () -> Unit)? = null,
    /** Roads list (docs/roads/ux.md §4.3): 64dp, one-line name and subtitle; grows at font scale ≥ 1.5. */
    dense: Boolean = false,
    /** Screen-reader name when [name] is abbreviated for display ("Northeast", never "N E"). */
    spokenName: String = name,
    /** Replaces the ring (uncounted roads: their map pattern); [statusText] then replaces the status chip. */
    leading: (@Composable () -> Unit)? = null,
    statusText: String? = null,
) {
    val statusLabel = statusText ?: stringResource(status.label)
    val a11y = if (leading != null) "$spokenName, $subtitle, $statusLabel" else "$spokenName, $subtitle, ${Format.percent(fraction)}, $statusLabel"
    val bigText = LocalDensity.current.fontScale >= 1.5f
    val lines = if (dense && !bigText) 1 else 2
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compactChip = maxWidth < 360.dp
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (dense && !bigText) 64.dp else BtTheme.spacing.rowHeight)
                .clickable(onClick = onClick)
                .clearAndSetSemantics { contentDescription = a11y; role = Role.Button }
                .padding(horizontal = BtTheme.spacing.screenH, vertical = if (dense) BtTheme.spacing.sm else BtTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.lg),
        ) {
            if (leading != null) leading() else ProgressRing(fraction, status, animationDelayMs = animationDelayMs)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = lines, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = lines, overflow = TextOverflow.Ellipsis)
                if (bigText && leading == null) StatusChip(status, Modifier.padding(top = 4.dp))
            }
            if (trailing != null) trailing() else if (!bigText && leading == null) StatusChip(status, compact = compactChip)
        }
    }
}

/** Live list row while recording: ring with the gained share, "12% → 31%" (ux.md §3.5d). */
@Composable
fun LiveTrailRow(name: String, before: Double, after: Double, status: TrailStatus, modifier: Modifier = Modifier) {
    val label = stringResource(status.label)
    val change = "${Format.percentWhole(before)} → ${Format.percentWhole(after)}"
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clearAndSetSemantics { contentDescription = "$name, $change, $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        ProgressRing(after, status, size = 32.dp, gained = (after - before).coerceAtLeast(0.0), showLabel = false)
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(change, style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Results row: before → after, with a StatusChip only when the status changed (ux.md §3.5f). */
@Composable
fun ResultTrailRow(
    name: String,
    before: Double,
    after: Double,
    beforeStatus: TrailStatus,
    afterStatus: TrailStatus,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(afterStatus.label)
    val changed = beforeStatus != afterStatus
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clearAndSetSemantics {
            contentDescription = "$name, ${Format.percent(before)} to ${Format.percent(after)}" + if (changed) ", now $label" else ""
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        ProgressRing(after, afterStatus, size = 32.dp, gained = (after - before).coerceAtLeast(0.0), showLabel = false)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Format.percentWhole(before), style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("→", style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.percentWhole(after), style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (changed) StatusChip(afterStatus)
    }
}
