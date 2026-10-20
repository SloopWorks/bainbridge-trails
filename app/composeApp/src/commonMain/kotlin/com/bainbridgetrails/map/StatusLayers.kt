package com.bainbridgetrails.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.contains
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.util.MaplibreComposable

/**
 * Trail status drawn app-side (decisions.md TL-1): the style only has the base map; these LineLayers sit over its
 * "trails" source (source-layer "segments"). One layer per covered bucket, filtered by segment id, so a coverage
 * change only changes a filter — the base style never reloads.
 *
 * Interim (foundation phase): flat lines in the palette from scripts/build-map-styles.py; the final encoding
 * (dots/dashes/casing, not-counted categories, selected halo, dim) comes with the map screen.
 */
@Composable
@MaplibreComposable
fun StatusLayers(buckets: Map<TrailStatus, List<String>>, dark: Boolean) {
    val trails = getBaseSource<VectorSource>("trails") ?: return // style still loading
    val c = if (dark) DarkStatus else TopoStatus
    LineLayer(
        id = "app-trails-base",
        source = trails,
        sourceLayer = "segments",
        color = const(c.getValue(TrailStatus.NOT_STARTED)),
        width = const(1.6.dp),
        cap = const(LineCap.Round),
    )
    for (status in listOf(TrailStatus.PARTWAY, TrailStatus.NEARLY_DONE, TrailStatus.COMPLETE)) {
        val ids = buckets[status].orEmpty()
        if (ids.isEmpty()) continue
        LineLayer(
            id = "app-trails-${status.name.lowercase()}",
            source = trails,
            sourceLayer = "segments",
            filter = const(ids).contains(feature["id"].asString()),
            color = const(c.getValue(status)),
            width = const(if (status == TrailStatus.COMPLETE) 4.dp else 3.dp),
            cap = const(LineCap.Round),
        )
    }
}

private val TopoStatus = mapOf(
    TrailStatus.NOT_STARTED to Color(0xFF6E6A64),
    TrailStatus.PARTWAY to Color(0xFFC98A00),
    TrailStatus.NEARLY_DONE to Color(0xFFE2562A),
    TrailStatus.COMPLETE to Color(0xFF9B1FA8),
)

private val DarkStatus = mapOf(
    TrailStatus.NOT_STARTED to Color(0xFFA9A49B),
    TrailStatus.PARTWAY to Color(0xFFF0B43C),
    TrailStatus.NEARLY_DONE to Color(0xFFFF7B47),
    TrailStatus.COMPLETE to Color(0xFFD45CFF),
)
