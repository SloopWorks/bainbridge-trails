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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.trails_count
import com.bainbridgetrails.resources.trails_title
import com.bainbridgetrails.ui.components.BtTopBar
import com.bainbridgetrails.ui.components.TrailRow
import com.bainbridgetrails.ui.components.fillStaggerMs
import org.jetbrains.compose.resources.stringResource

/** Trails tab (F3). Placeholder: every named trail by name with status + %. Search/filter/sort come next. */
@Composable
fun TrailsScreen(graph: AppGraph.Installed, settings: AppSettings, onTrail: (String) -> Unit, modifier: Modifier = Modifier) {
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    DatasetGate(modifier) { _ ->
        val trails = remember(snapshot) { snapshot?.trails.orEmpty().sortedBy { it.name.lowercase() } }
        Column(Modifier.fillMaxSize()) {
            BtTopBar(stringResource(Res.string.trails_title))
            Text(
                stringResource(Res.string.trails_count, trails.size, trails.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(trails, key = { _, t -> t.trailId }) { i, t ->
                    TrailRow(
                        name = t.name,
                        subtitle = Format.lengthMi(t.lengthMi, settings.units),
                        fraction = t.fraction,
                        status = t.status,
                        onClick = { onTrail(t.trailId) },
                        animationDelayMs = fillStaggerMs(i),
                    )
                }
            }
        }
    }
}
