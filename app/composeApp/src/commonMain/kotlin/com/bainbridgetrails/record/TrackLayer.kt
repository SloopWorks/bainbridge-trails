package com.bainbridgetrails.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.Fix
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/** GeoJSON FeatureCollection with the track as one LineString (empty until there are two fixes). */
internal fun trackGeoJson(track: List<Fix>): String = buildString {
    append("""{"type":"FeatureCollection","features":[""")
    if (track.size >= 2) {
        append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
        track.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append('[').append(f.lon).append(',').append(f.lat).append(']')
        }
        append("]}}")
    }
    append("]}")
}

/** The live track of the current session, drawn above the style (in memory only). */
@Composable
@MaplibreComposable
fun TrackLayer(track: List<Fix>) {
    val json = remember(track) { trackGeoJson(track) }
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
    LineLayer(
        id = "live-track-casing",
        source = source,
        color = const(Color.White),
        width = const(8.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    LineLayer(
        id = "live-track",
        source = source,
        color = const(Color(0xFF0B63CE)),
        width = const(4.5.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
}
