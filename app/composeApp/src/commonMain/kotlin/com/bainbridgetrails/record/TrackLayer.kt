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

/**
 * Battery: the map's GeoJSON source is rebuilt (and re-tiled by MapLibre) at most every [MIN_INTERVAL_MS]
 * of fix time, or sooner once the walker has moved [MIN_MOVE_M] from the last drawn point.
 */
internal object TrackThrottle {
    const val MIN_INTERVAL_MS = 5_000L
    const val MIN_MOVE_M = 10.0

    fun shouldRebuild(drawn: List<Fix>, track: List<Fix>): Boolean {
        if (track.isEmpty()) return drawn.isNotEmpty()
        if (drawn.isEmpty()) return true
        if (track.size < drawn.size || track.first() != drawn.first()) return true // new session
        if (track.size == drawn.size) return false
        val a = drawn.last()
        val b = track.last()
        return b.timeMs - a.timeMs >= MIN_INTERVAL_MS || metres(a, b) >= MIN_MOVE_M
    }

    private fun metres(a: Fix, b: Fix): Double {
        val k = kotlin.math.cos((a.lat + b.lat) / 2 * kotlin.math.PI / 180)
        return kotlin.math.hypot((b.lon - a.lon) * 111_320.0 * k, (b.lat - a.lat) * 110_574.0)
    }
}

/** [track], but only advanced when [TrackThrottle] says so; the same list instance otherwise (so callers skip). */
@Composable
internal fun rememberThrottledTrack(track: List<Fix>): List<Fix> {
    val drawn = remember { arrayOf(emptyList<Fix>()) }
    if (TrackThrottle.shouldRebuild(drawn[0], track)) drawn[0] = track
    return drawn[0]
}
