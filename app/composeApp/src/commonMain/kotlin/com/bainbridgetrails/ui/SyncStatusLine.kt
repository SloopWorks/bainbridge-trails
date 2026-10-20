package com.bainbridgetrails.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.sync_failed
import com.bainbridgetrails.resources.sync_first_progress
import com.bainbridgetrails.resources.sync_now_a11y
import com.bainbridgetrails.resources.sync_ok
import com.bainbridgetrails.resources.sync_ok_at
import com.bainbridgetrails.resources.sync_pending
import com.bainbridgetrails.resources.sync_rejected_notice
import com.bainbridgetrails.resources.sync_syncing
import com.bainbridgetrails.sync.SyncState
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.theme.BtTheme

/**
 * Subtle sync status (F11 step 5): backed up / will sync / didn't finish / syncing, never a blocking spinner.
 * [onRetry] adds a 48dp "sync now" button when the last attempt didn't succeed. Nothing when signed out.
 */
@Composable
fun SyncStatusLine(state: SyncState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (icon, text, retry) = when (state) {
        SyncState.SignedOut -> return
        is SyncState.Idle -> when {
            state.pendingWalks > 0 -> Triple(BtIcons.CloudOff, org.jetbrains.compose.resources.stringResource(Res.string.sync_pending), true)
            state.lastSyncedAt != null -> Triple(BtIcons.CloudDone, org.jetbrains.compose.resources.stringResource(Res.string.sync_ok_at, ageText(state.lastSyncedAt)), false)
            else -> Triple(BtIcons.CloudDone, org.jetbrains.compose.resources.stringResource(Res.string.sync_ok), false)
        }
        is SyncState.Syncing -> Triple(null, org.jetbrains.compose.resources.stringResource(if (state.backfill) Res.string.sync_first_progress else Res.string.sync_syncing), false)
        is SyncState.Pending -> Triple(BtIcons.CloudOff, org.jetbrains.compose.resources.stringResource(Res.string.sync_pending), true)
        is SyncState.Error -> Triple(BtIcons.Error, org.jetbrains.compose.resources.stringResource(Res.string.sync_failed), true)
        is SyncState.Rejected -> Triple(BtIcons.Info, org.jetbrains.compose.resources.stringResource(Res.string.sync_rejected_notice), false)
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
    ) {
        if (icon == null) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = muted)
        } else {
            BtIcon(icon, null, size = 18.dp, tint = muted)
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted)
        if (retry) {
            IconButton(onClick = onRetry) { BtIcon(BtIcons.Refresh, org.jetbrains.compose.resources.stringResource(Res.string.sync_now_a11y), size = 20.dp, tint = MaterialTheme.colorScheme.primary) }
        }
    }
}
