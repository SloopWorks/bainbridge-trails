package com.bainbridgetrails.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.detail_covered
import com.bainbridgetrails.resources.detail_length
import com.bainbridgetrails.resources.detail_start_walk
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.components.ProgressBar
import com.bainbridgetrails.ui.components.StatTile
import com.bainbridgetrails.ui.components.StatusChip
import org.jetbrains.compose.resources.stringResource

/** Trail detail (F4). Placeholder: name, status, covered %, length, Start walk. Mini map/community come next. */
@Composable
fun TrailDetailScreen(graph: AppGraph.Installed, settings: AppSettings, trailId: String, onBack: () -> Unit, onStartWalk: () -> Unit) {
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    DatasetGate { data ->
        val meta = data.dataset.trails.firstOrNull { it.id == trailId }
        val p = snapshot?.trail(trailId)
        PushedScreen(meta?.name ?: trailId, onBack) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                val status = p?.status ?: TrailStatus.NOT_STARTED
                val fraction = p?.fraction ?: 0.0
                StatusChip(status)
                ProgressBar(fraction, status)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(Format.percent(fraction), stringResource(Res.string.detail_covered), Modifier.weight(1f))
                    StatTile(Format.lengthMi(meta?.displayLengthMi ?: 0.0, settings.units), stringResource(Res.string.detail_length), Modifier.weight(1f))
                }
                PrimaryButton(stringResource(Res.string.detail_start_walk), onStartWalk, Modifier.fillMaxWidth())
            }
        }
    }
}
