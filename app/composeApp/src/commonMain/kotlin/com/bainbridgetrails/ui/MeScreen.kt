package com.bainbridgetrails.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.me_edit_name
import com.bainbridgetrails.resources.me_history
import com.bainbridgetrails.resources.me_history_empty
import com.bainbridgetrails.resources.me_history_row
import com.bainbridgetrails.resources.me_history_trails
import com.bainbridgetrails.resources.me_in_progress
import com.bainbridgetrails.resources.me_in_progress_empty
import com.bainbridgetrails.resources.me_leaderboard_row_signed_out
import com.bainbridgetrails.resources.me_settings
import com.bainbridgetrails.resources.me_stat_island
import com.bainbridgetrails.resources.me_stat_km
import com.bainbridgetrails.resources.me_stat_miles
import com.bainbridgetrails.resources.me_stat_trails
import com.bainbridgetrails.resources.me_stat_trails_value
import com.bainbridgetrails.resources.me_title
import org.jetbrains.compose.resources.pluralStringResource
import com.bainbridgetrails.ui.components.StatTile
import com.bainbridgetrails.ui.components.StatTileRow
import com.bainbridgetrails.ui.components.TrailRow
import org.jetbrains.compose.resources.stringResource

/** Me tab (F7). Placeholder: stat tiles, in-progress trails, walk history, links. */
@Composable
fun MeScreen(
    graph: AppGraph.Installed,
    settings: AppSettings,
    onTrail: (String) -> Unit,
    onLeaderboard: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    val history by graph.walks.history.collectAsStateWithLifecycle()
    DatasetGate(modifier) { data ->
        val s = snapshot
        val inProgress = s?.trails.orEmpty()
            .filter { it.fraction > 0 && it.status != TrailStatus.COMPLETE }
            .sortedByDescending { it.fraction }
            .take(5)
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(Res.string.me_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp)) }
            item {
                StatTileRow {
                    StatTile(Format.percent(s?.islandFraction ?: 0.0), stringResource(Res.string.me_stat_island), Modifier.weight(1f), emphasis = true)
                    StatTile(
                        // Denominator from the dataset (never hard-coded).
                        stringResource(Res.string.me_stat_trails_value, s?.trailsCompleted ?: 0, data.dataset.trails.size),
                        stringResource(Res.string.me_stat_trails),
                        Modifier.weight(1f),
                    )
                    StatTile(
                        Format.distance(history?.trailMetresWalked ?: 0.0, settings.units).substringBefore(' '),
                        stringResource(if (settings.units == Units.MILES) Res.string.me_stat_miles else Res.string.me_stat_km),
                        Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onLeaderboard) { Text(stringResource(Res.string.me_leaderboard_row_signed_out)) }
                    OutlinedButton(onClick = onProfile) { Text(stringResource(Res.string.me_edit_name)) }
                    OutlinedButton(onClick = onSettings) { Text(stringResource(Res.string.me_settings)) }
                }
            }
            item { Text(stringResource(Res.string.me_in_progress), style = MaterialTheme.typography.titleMedium) }
            if (inProgress.isEmpty()) item { Text(stringResource(Res.string.me_in_progress_empty)) }
            items(inProgress, key = { "p-" + it.trailId }) { t ->
                TrailRow(t.name, Format.lengthMi(t.lengthMi, settings.units), t.fraction, t.status, onClick = { onTrail(t.trailId) })
            }
            item { Text(stringResource(Res.string.me_history), style = MaterialTheme.typography.titleMedium) }
            val walks = history?.walks.orEmpty()
            if (walks.isEmpty()) item { Text(stringResource(Res.string.me_history_empty)) }
            items(walks, key = { "w-" + it.sessionId }) { w ->
                // TODO(ux): date line ("Sat, Oct 18") needs kotlinx-datetime for local time; add with the final Me screen.
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(stringResource(Res.string.me_history_row, Format.duration(w.durationMs), Format.distance(w.distanceM, settings.units)))
                    Text(
                        pluralStringResource(Res.plurals.me_history_trails, w.trails.size, w.trails.size) +
                            w.trails.take(2).mapNotNull { t -> s?.trail(t.trailId)?.name }.joinToString(prefix = if (w.trails.isEmpty()) "" else " · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
