package com.bainbridgetrails.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_done
import com.bainbridgetrails.resources.ic_cloud_upload
import com.bainbridgetrails.resources.ic_warning
import com.bainbridgetrails.resources.recovery_banner
import com.bainbridgetrails.resources.results_approx_warning
import com.bainbridgetrails.resources.results_delta_pts
import com.bainbridgetrails.resources.results_details
import com.bainbridgetrails.resources.results_dropped_steps
import com.bainbridgetrails.resources.results_dropped_vehicle
import com.bainbridgetrails.resources.results_island
import com.bainbridgetrails.resources.results_island_label
import com.bainbridgetrails.resources.results_newly_completed
import com.bainbridgetrails.resources.results_no_progress
import com.bainbridgetrails.resources.results_no_progress_hint
import com.bainbridgetrails.resources.results_signin
import com.bainbridgetrails.resources.results_signin_action
import com.bainbridgetrails.resources.results_stats
import com.bainbridgetrails.resources.results_sync_failed
import com.bainbridgetrails.resources.results_sync_ok
import com.bainbridgetrails.resources.results_sync_pending
import com.bainbridgetrails.resources.results_sync_running
import com.bainbridgetrails.resources.results_title
import com.bainbridgetrails.resources.results_trails_header
import com.bainbridgetrails.resources.results_unverified
import com.bainbridgetrails.resources.results_verified
import com.bainbridgetrails.resources.record_not_now
import com.bainbridgetrails.tracking.SessionSummary
import com.bainbridgetrails.ui.Format
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.components.ResultTrailRow
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.SignInPrompt
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

/** Tenths of a percent, rounded down like [Format.percent] (TL-5), so the delta matches the two shown values. */
private fun tenths(fraction: Double): Long = floor(fraction.coerceIn(0.0, 1.0) * 1000 + 1e-9).toLong()

/** Island change in points between the two values as displayed: "0.5" (never negative: coverage only grows). */
internal fun deltaPoints(before: Double, after: Double): String {
    val d = (tenths(after) - tenths(before)).coerceAtLeast(0)
    return "${d / 10}.${d % 10}"
}

/** Sync line under the results (ux.md §3.5f States). Null = nothing to say (signed out shows a sign-in card instead). */
internal enum class ResultsSync { PENDING, RUNNING, OK, FAILED }

/**
 * Results (F6, ux.md §3.5f): "Walk saved", time · distance, island before → after with the delta, per-trail
 * before → after rows (status chip when the status changed), verification, dropped counts under Details, sync hint.
 */
@Composable
internal fun ResultsSheet(
    summary: SessionSummary,
    units: Units,
    status: (Double) -> TrailStatus,
    sync: ResultsSync?,
    signedIn: Boolean,
    recovered: Boolean,
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSignIn by remember(summary.sessionId) { mutableStateOf(true) }
    var details by remember(summary.sessionId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = BtTheme.spacing.screenH).padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (recovered) Banner(stringResource(Res.string.recovery_banner), null, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            Column {
                Text(stringResource(Res.string.results_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                if (summary.endedAt > summary.startedAt) {
                    Text(
                        stringResource(Res.string.results_stats, Format.duration(summary.endedAt - summary.startedAt), Format.distance(summary.distanceM, units)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IslandTile(summary.islandBefore, summary.islandAfter)
            if (!summary.verified) Banner(stringResource(Res.string.results_unverified), Res.drawable.ic_warning, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            if (summary.approximateLocation) Banner(stringResource(Res.string.results_approx_warning), Res.drawable.ic_warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)

            if (summary.trails.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(Res.string.results_no_progress), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.results_no_progress_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val newly = summary.trails.filter { it.trailId in summary.newlyCompleted }
                val rest = summary.trails.filter { it.trailId !in summary.newlyCompleted }
                if (newly.isNotEmpty()) {
                    SectionHeader(stringResource(Res.string.results_newly_completed))
                    for (t in newly) ResultTrailRow(t.name, t.before, t.after, status(t.before), t.status)
                }
                if (rest.isNotEmpty()) {
                    SectionHeader(stringResource(Res.string.results_trails_header))
                    for (t in rest) ResultTrailRow(t.name, t.before, t.after, status(t.before), t.status)
                }
            }

            if (summary.droppedVehicle > 0 || summary.droppedSteps > 0) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { details = !details },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.results_details), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BtIcon(BtIcons.ExpandMore, null, Modifier.rotate(if (details) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (details) {
                    val small = MaterialTheme.typography.bodySmall
                    val c = MaterialTheme.colorScheme.onSurfaceVariant
                    if (summary.droppedVehicle > 0) Text(pluralStringResource(Res.plurals.results_dropped_vehicle, summary.droppedVehicle, summary.droppedVehicle), style = small, color = c)
                    if (summary.droppedSteps > 0) Text(pluralStringResource(Res.plurals.results_dropped_steps, summary.droppedSteps, summary.droppedSteps), style = small, color = c)
                }
            }
            if (summary.verified) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BtIcon(BtIcons.Verified, null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(Res.string.results_verified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                !signedIn && showSignIn -> SignInPrompt(
                    stringResource(Res.string.results_signin),
                    stringResource(Res.string.results_signin_action),
                    onSignIn,
                    onDismiss = { showSignIn = false },
                    dismissLabel = stringResource(Res.string.record_not_now),
                )
                signedIn && sync != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BtIcon(if (sync == ResultsSync.OK) BtIcons.Check else Res.drawable.ic_cloud_upload, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(sync.label()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        PrimaryButton(
            stringResource(Res.string.common_done),
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.screenH, vertical = 16.dp),
        )
    }
}

private fun ResultsSync.label() = when (this) {
    ResultsSync.PENDING -> Res.string.results_sync_pending
    ResultsSync.RUNNING -> Res.string.results_sync_running
    ResultsSync.OK -> Res.string.results_sync_ok
    ResultsSync.FAILED -> Res.string.results_sync_failed
}

/** Wide island tile: "Island  11.9% → 12.4%" with "+0.5 pts" in `positive` (polite live region, ux.md §5.5). */
@Composable
internal fun IslandTile(before: Double, after: Double) {
    val points = deltaPoints(before, after)
    val delta = stringResource(Res.string.results_delta_pts, points)
    val spoken = stringResource(Res.string.results_island, Format.percent(before), Format.percent(after), delta)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .clearAndSetSemantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.results_island_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Format.percent(before), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("→", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.percent(after), style = BtTheme.type.statLarge)
            }
        }
        // No "+0.0 pts" for a walk that added nothing (the no-progress text below says so).
        if (points != "0.0") Text(delta, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = BtTheme.colors.positive)
    }
}

@Composable
internal fun Banner(text: String, icon: DrawableResource?, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(bg, MaterialTheme.shapes.small).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) BtIcon(icon, null, size = 20.dp, tint = fg)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}
