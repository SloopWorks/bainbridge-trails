package com.bainbridgetrails.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.auth.AuthState
import com.bainbridgetrails.data.ReportResult
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.LeaderboardEntryDto
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.board_daily_note
import com.bainbridgetrails.resources.board_daily_note_signed_out
import com.bainbridgetrails.resources.board_empty_body
import com.bainbridgetrails.resources.board_empty_title
import com.bainbridgetrails.resources.board_error
import com.bainbridgetrails.resources.board_not_ranked
import com.bainbridgetrails.resources.board_offline_body
import com.bainbridgetrails.resources.board_offline_note
import com.bainbridgetrails.resources.board_offline_title
import com.bainbridgetrails.resources.board_refresh_a11y
import com.bainbridgetrails.resources.board_signed_out_prompt
import com.bainbridgetrails.resources.board_signin
import com.bainbridgetrails.resources.board_subtitle
import com.bainbridgetrails.resources.board_title
import com.bainbridgetrails.resources.board_total
import com.bainbridgetrails.resources.board_updated
import com.bainbridgetrails.resources.common_cancel
import com.bainbridgetrails.resources.common_retry
import com.bainbridgetrails.resources.error_offline_generic
import com.bainbridgetrails.resources.error_server
import com.bainbridgetrails.resources.report_body
import com.bainbridgetrails.resources.board_shown_again
import com.bainbridgetrails.resources.board_show_again
import com.bainbridgetrails.resources.board_hidden_people_body
import com.bainbridgetrails.resources.board_hidden_people
import com.bainbridgetrails.resources.board_undo
import com.bainbridgetrails.resources.board_hidden_snackbar
import com.bainbridgetrails.resources.report_send
import com.bainbridgetrails.resources.report_signin_required
import com.bainbridgetrails.resources.report_thanks
import com.bainbridgetrails.resources.report_title
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.EmptyKind
import com.bainbridgetrails.ui.components.EmptyState
import com.bainbridgetrails.ui.components.LeaderHeader
import com.bainbridgetrails.ui.components.LeaderRow
import com.bainbridgetrails.ui.components.HiddenYouRow
import com.bainbridgetrails.ui.components.OfflineNote
import com.bainbridgetrails.ui.components.SignInPrompt
import com.bainbridgetrails.ui.components.SkeletonLeaderRow
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Leaderboard (F8, ux.md §3.8). Others' rows come from the daily snapshot; `you` is live (TL-11) and is pinned at the
 * bottom whenever your row isn't on screen. Signed out: the list plus a sign-in card.
 */
@Composable
fun LeaderboardScreen(graph: AppGraph.Installed, onBack: () -> Unit, onSignIn: () -> Unit) {
    val state by graph.leaderboard.state.collectAsStateWithLifecycle()
    val auth by graph.auth.state.collectAsStateWithLifecycle()
    val signedIn = auth is AuthState.SignedIn
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var reporting by remember { mutableStateOf<LeaderboardEntryDto?>(null) }
    LaunchedEffect(signedIn) { graph.leaderboard.refresh() }
    // P1-8: the hide toggle lives on the profile (GET /v1/me); refresh it with the board so the hidden row is current.
    val profile by graph.profile.profile.collectAsStateWithLifecycle()
    LaunchedEffect(signedIn) { if (signedIn) graph.profile.refresh() }
    val hiddenSelf = signedIn && profile?.me?.hiddenFromBoard == true

    val board = state.board
    val entries = board?.entries.orEmpty()
    val you = board?.you
    // Live "you" values replace the snapshot's copy of your row. Before the daily snapshot includes anyone (a new
    // board), your live row is the list: never "No one's on it yet" above your own rank-1 row.
    val rows = remember(entries, you) {
        if (entries.isEmpty() && you != null) listOf(you.copy(isYou = true))
        else entries.map { if (it.isYou && you != null) you.copy(isYou = true) else it }
    }
    val youIndex = rows.indexOfFirst { it.isYou }
    val youVisible by remember(youIndex) {
        derivedStateOf { youIndex >= 0 && listState.layoutInfo.visibleItemsInfo.any { it.key == "row-$youIndex" } }
    }

    ListScreen(
        stringResource(Res.string.board_title),
        onBack = onBack,
        snackbar = snackbar,
        listState = listState,
        actions = {
            if (state.loading) {
                CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                IconButton(onClick = { scope.launch { graph.leaderboard.refresh() } }) { BtIcon(BtIcons.Refresh, stringResource(Res.string.board_refresh_a11y)) }
            }
        },
        bottomBar = when {
            hiddenSelf -> { {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HiddenYouRow(onChange = onSignIn)
                }
            } }
            !signedIn -> { { SignInPrompt(stringResource(Res.string.board_signed_out_prompt), stringResource(Res.string.board_signin), onSignIn, Modifier.padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm)) } }
            you != null && !youVisible -> { {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LeaderRow(you.rank, you.displayName, you.islandPct, you.trailsCompleted, isYou = true)
                }
            } }
            else -> null
        },
    ) {
        item(key = "intro") {
            Column(Modifier.padding(horizontal = BtTheme.spacing.screenH).padding(bottom = BtTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.xs)) {
                Text(stringResource(Res.string.board_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val updated = state.updatedAt
                if (updated != null && board != null) {
                    if (state.error != null) {
                        OfflineNote(stringResource(Res.string.board_offline_note, ageText(updated)), Modifier.padding(top = BtTheme.spacing.xs))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                            Text(
                                stringResource(Res.string.board_updated, ageText(updated)) + " · " + stringResource(if (signedIn) Res.string.board_daily_note else Res.string.board_daily_note_signed_out),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (board != null && board.totalRanked > 0) {
                    Text(pluralStringResource(Res.plurals.board_total, board.totalRanked, board.totalRanked), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (signedIn && !hiddenSelf && board != null && you == null && state.error == null) {
                    Text(stringResource(Res.string.board_not_ranked), Modifier.padding(top = BtTheme.spacing.xs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        when {
            board == null && state.error == null -> items(10) { SkeletonLeaderRow() }
            board == null -> item(key = "error") {
                val offline = state.error is ApiError.Offline
                EmptyState(
                    if (offline) stringResource(Res.string.board_offline_title) else stringResource(Res.string.board_error),
                    body = if (offline) stringResource(Res.string.board_offline_body) else null,
                    kind = if (offline) EmptyKind.Offline else EmptyKind.Error,
                    action = stringResource(Res.string.common_retry) to { scope.launch { graph.leaderboard.refresh() } },
                )
            }
            rows.isEmpty() -> item(key = "empty") {
                EmptyState(stringResource(Res.string.board_empty_title), body = stringResource(Res.string.board_empty_body))
            }
            else -> {
                stickyHeader(key = "header") { LeaderHeader() }
                items(rows.size, key = { "row-$it" }) { i ->
                    val e = rows[i]
                    LeaderRow(
                        e.rank, e.displayName, e.islandPct, e.trailsCompleted, e.isYou,
                        onReport = if (e.isYou) null else { { reporting = e } },
                        onHide = if (e.isYou) null else { {
                            scope.launch {
                                graph.leaderboard.hidePerson(e.userId)
                                val r = snackbar.showSnackbar(getString(Res.string.board_hidden_snackbar), actionLabel = getString(Res.string.board_undo), duration = SnackbarDuration.Short)
                                if (r == SnackbarResult.ActionPerformed) graph.leaderboard.unhidePerson(e.userId)
                            }
                        } },
                    )
                }
            }
        }
        if (state.hiddenCount > 0) {
            item(key = "hidden-people") {
                Row(
                    Modifier.fillMaxWidth().padding(start = BtTheme.spacing.screenH, end = BtTheme.spacing.sm, top = BtTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.board_hidden_people, state.hiddenCount), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(Res.string.board_hidden_people_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            graph.leaderboard.showHiddenAgain()
                            snackbar.showSnackbar(getString(Res.string.board_shown_again))
                        }
                    }) { Text(stringResource(Res.string.board_show_again)) }
                }
            }
        }
    }

    reporting?.let { e ->
        AlertDialog(
            onDismissRequest = { reporting = null },
            title = { Text(stringResource(Res.string.report_title)) },
            text = { Text(stringResource(Res.string.report_body, e.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    reporting = null
                    scope.launch {
                        val msg = when (val r = graph.leaderboard.reportName(e.userId)) {
                            ReportResult.Reported -> getString(Res.string.report_thanks)
                            ReportResult.SignInRequired -> getString(Res.string.report_signin_required)
                            is ReportResult.Failed -> getString(if (r.error is ApiError.Offline) Res.string.error_offline_generic else Res.string.error_server)
                        }
                        snackbar.showSnackbar(msg)
                    }
                }) { Text(stringResource(Res.string.report_send)) }
            },
            dismissButton = { TextButton(onClick = { reporting = null }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}
