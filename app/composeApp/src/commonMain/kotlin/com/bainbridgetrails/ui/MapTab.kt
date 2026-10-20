package com.bainbridgetrails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.IslandCamera
import com.bainbridgetrails.map.StatusLayers
import com.bainbridgetrails.map.TrailMap
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_loading_trails
import com.bainbridgetrails.resources.me_stat_island
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraPosition

/** Map tab (F2). Placeholder: the map in the user's style + an island % pill. Status colors/tap-to-preview come next. */
@Composable
fun MapTab(graph: AppGraph.Installed, settings: AppSettings, camera: CameraPosition?, modifier: Modifier = Modifier) {
    val dataset by AppGraph.datasets.state.collectAsStateWithLifecycle()
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        val style = settings.effectiveMapStyle(isSystemInDarkTheme())
        TrailMap(style, Modifier.fillMaxSize(), initialCamera = camera ?: IslandCamera, controlsTopPadding = 52.dp) {
            StatusLayers(snapshot?.segmentBuckets.orEmpty(), style.isDark)
        }
        val pill = when {
            dataset is Loadable.Loading -> stringResource(Res.string.common_loading_trails)
            snapshot != null -> "${stringResource(Res.string.me_stat_island)} ${Format.percent(snapshot!!.islandFraction)}"
            else -> null
        }
        if (pill != null) {
            Text(
                pill,
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
