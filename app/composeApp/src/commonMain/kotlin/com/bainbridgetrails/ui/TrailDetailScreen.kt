package com.bainbridgetrails.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
                StatusChip(p?.status ?: TrailStatus.NOT_STARTED, p?.fraction ?: 0.0)
                StatTile(stringResource(Res.string.detail_covered), Format.percent(p?.fraction ?: 0.0), Modifier.fillMaxWidth())
                StatTile(stringResource(Res.string.detail_length), Format.lengthMi(meta?.displayLengthMi ?: 0.0, settings.units), Modifier.fillMaxWidth())
                Button(onClick = onStartWalk, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(Res.string.detail_start_walk)) }
            }
        }
    }
}
