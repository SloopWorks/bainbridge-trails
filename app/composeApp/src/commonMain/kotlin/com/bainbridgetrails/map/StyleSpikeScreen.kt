package com.bainbridgetrails.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

/**
 * Spike-only launch options, so screenshots can be scripted (Android intent extras / iOS launch
 * arguments, see MainActivity and MainViewController): initial style and camera, and an optional
 * automatic switch to another style after a delay (exercises runtime restyling where taps can't be
 * scripted, e.g. the iOS simulator).
 */
data class SpikeLaunch(
    val screen: Screen = Screen.RECORD,
    val style: MapStyle = MapStyle.Default,
    val camera: CameraPosition = IslandCamera,
    val switchTo: MapStyle? = null,
    val switchAfterMs: Long = 4000,
) {
    enum class Screen { RECORD, STYLE }

    companion object {
        /**
         * Parses `key=value` options: screen=record|style, style=TOPO|MINIMAL_LIGHT|DARK, lat, lon, zoom, switchTo,
         * switchAfterMs. Without `screen`, a `style` or `switchTo` option still opens the style spike (older scripts).
         */
        fun parse(options: Map<String, String>): SpikeLaunch {
            fun style(v: String?) = v?.let { s -> MapStyle.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
            val lat = options["lat"]?.toDoubleOrNull()
            val lon = options["lon"]?.toDoubleOrNull()
            val zoom = options["zoom"]?.toDoubleOrNull()
            val screen = when (options["screen"]?.lowercase()) {
                "style" -> Screen.STYLE
                "record" -> Screen.RECORD
                else -> if ("style" in options || "switchTo" in options) Screen.STYLE else Screen.RECORD
            }
            return SpikeLaunch(
                screen = screen,
                style = style(options["style"]) ?: MapStyle.Default,
                camera = CameraPosition(
                    center = Position(longitude = lon ?: IslandCamera.center.longitude, latitude = lat ?: IslandCamera.center.latitude),
                    zoom = zoom ?: IslandCamera.zoom,
                ),
                switchTo = style(options["switchTo"]),
                switchAfterMs = options["switchAfterMs"]?.toLongOrNull() ?: 4000,
            )
        }
    }
}

/** Map + style switcher + status legend (spike UI for picking a style direction). */
@Composable
fun StyleSpikeScreen(launch: SpikeLaunch = SpikeLaunch(), modifier: Modifier = Modifier) {
    var style by remember { mutableStateOf(launch.style) }
    LaunchedEffect(launch) {
        val target = launch.switchTo ?: return@LaunchedEffect
        delay(launch.switchAfterMs)
        style = target
    }
    Box(modifier.fillMaxSize()) {
        TrailMap(style, Modifier.fillMaxSize(), initialCamera = launch.camera, controlsTopPadding = 52.dp)
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
            StyleSwitcher(style, onSelect = { style = it }, modifier = Modifier.align(Alignment.TopCenter))
            StatusLegend(style, Modifier.align(Alignment.BottomEnd).padding(bottom = 48.dp))
        }
    }
}

private class Chrome(val surface: Color, val outline: Color, val text: Color, val selected: Color, val onSelected: Color)

private fun chrome(style: MapStyle) = if (style.isDark) {
    Chrome(Color(0xEB13201B), Color(0xFF34443D), Color(0xFFA9B8B1), Color(0xFFDCE6E1), Color(0xFF13201B))
} else {
    Chrome(Color(0xF2FFFFFF), Color(0xFFD3D8D0), Color(0xFF4A544E), Color(0xFF26302B), Color(0xFFFFFFFF))
}

@Composable
private fun StyleSwitcher(current: MapStyle, onSelect: (MapStyle) -> Unit, modifier: Modifier = Modifier) {
    val c = chrome(current)
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .background(c.surface, shape)
            .border(1.dp, c.outline, shape)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (s in MapStyle.entries) {
            val selected = s == current
            Text(
                s.label,
                modifier = Modifier
                    .background(if (selected) c.selected else Color.Transparent, shape)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(s) })
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                color = if (selected) c.onSelected else c.text,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

/** Mirrors the status palette in scripts/build-map-styles.py (spike: duplicated by hand). */
private class StatusColors(val notStarted: Color, val partway: Color, val nearlyDone: Color, val complete: Color, val casing: Color, val notCounted: Color)

private fun statusColors(style: MapStyle) = when (style) {
    MapStyle.TOPO -> StatusColors(Color(0xFF6E6A64), Color(0xFFC98A00), Color(0xFFE2562A), Color(0xFF9B1FA8), Color(0xFF5A1F0C), Color(0xFF7D8679))
    MapStyle.MINIMAL_LIGHT -> StatusColors(Color(0xFF66625C), Color(0xFFC08300), Color(0xFFDE4F22), Color(0xFF931B9F), Color(0xFF4A1A09), Color(0xFF9AA19A))
    MapStyle.DARK -> StatusColors(Color(0xFFA9A49B), Color(0xFFF0B43C), Color(0xFFFF7B47), Color(0xFFD45CFF), Color(0xFF0B1410), Color(0xFF5E6E66))
}

private enum class Swatch { COMPLETE, NEARLY_DONE, PARTWAY, NOT_STARTED, NOT_COUNTED }

@Composable
private fun StatusLegend(style: MapStyle, modifier: Modifier = Modifier) {
    val c = chrome(style)
    val colors = statusColors(style)
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.background(c.surface, shape).border(1.dp, c.outline, shape).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((swatch, label) in listOf(
            Swatch.COMPLETE to "Done",
            Swatch.NEARLY_DONE to "Nearly done",
            Swatch.PARTWAY to "Partway",
            Swatch.NOT_STARTED to "Not started",
            Swatch.NOT_COUNTED to "Not counted",
        )) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendLine(swatch, colors)
                Text(label, Modifier.padding(start = 6.dp), color = c.text, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun LegendLine(swatch: Swatch, c: StatusColors) {
    Canvas(Modifier.size(width = 26.dp, height = 10.dp)) {
        val y = size.height / 2
        val a = Offset(2.dp.toPx(), y)
        val b = Offset(size.width - 2.dp.toPx(), y)
        when (swatch) {
            Swatch.COMPLETE -> drawLine(c.complete, a, b, 5.dp.toPx(), StrokeCap.Round)
            Swatch.NEARLY_DONE -> {
                drawLine(c.casing, a, b, 5.8.dp.toPx(), StrokeCap.Round)
                drawLine(c.nearlyDone, a, b, 3.6.dp.toPx(), StrokeCap.Round)
            }
            Swatch.PARTWAY -> {
                val w = 3.dp.toPx()
                drawLine(c.partway, a, b, w, StrokeCap.Butt, PathEffect.dashPathEffect(floatArrayOf(2.6f * w, 1.4f * w)))
            }
            Swatch.NOT_STARTED -> {
                val w = 2.6.dp.toPx()
                drawLine(c.notStarted, a, b, w, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(0.01f, 1.9f * w)))
            }
            Swatch.NOT_COUNTED -> drawLine(c.notCounted, a, b, 1.dp.toPx(), StrokeCap.Round)
        }
    }
}
