package com.bainbridgetrails.play.fx.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxHostState
import com.bainbridgetrails.play.fx.FxStatsWindow
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.delay

/** Frame stats for the HUD (engine PF-7): MapLibre render ms per frame in an [FxStatsWindow], plus the host. */
class FxShowcaseStats {
    val renderMs = FxStatsWindow(240)
    var frames: Long = 0
    var host: FxHostState? = null
    var note by mutableStateOf("")
}

/**
 * Top-right budget readout on `mapChrome` (ux §3.19): fps, render p50/p95, clock mode, `maxFps`, live / static counts and
 * what animates. Updates at 2 Hz.
 */
@Composable
fun FxHud(stats: FxShowcaseStats, modifier: Modifier = Modifier) {
    val text by produceState("…", stats) {
        var last = stats.frames
        while (true) {
            delay(500)
            val f = stats.frames - last
            last = stats.frames
            val host = stats.host
            value = "fps ${f * 2} · render p50 ${fmt(stats.renderMs.p50)} p95 ${fmt(stats.renderMs.p95)} ms\n" +
                "clock ${host?.clock?.mode ?: "-"} · maxFps ${host?.maximumFps ?: "std"}" +
                (host?.let { "\n${it.debugCounts}" } ?: "") +
                (if (stats.note.isNotEmpty()) "\n${stats.note}" else "")
        }
    }
    Text(
        text,
        modifier.background(BtTheme.colors.mapChrome, RoundedCornerShape(8.dp)).padding(8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = BtTheme.colors.onMapChrome,
    )
}

private fun fmt(v: Float): String = ((v * 10).toInt() / 10.0).toString()

/**
 * The bottom control strip (ux §3.19) on `surfaceContainerLow`: effect, preset and state pickers, theme and
 * reduced-motion toggles, Play/Pause and the timeline scrubber (0 → 3 000 ms, applied to Layer-tier `globalState`).
 */
@Composable
fun FxControls(
    preset: FxPreset,
    state: String,
    dark: Boolean,
    reduced: Boolean,
    pausedAt: Long?,
    onPreset: (FxPreset) -> Unit,
    onState: (String) -> Unit,
    onDark: (Boolean) -> Unit,
    onReduced: (Boolean) -> Unit,
    onPausedAt: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in FxPresets.effects) {
                FilterChip(selected = e == preset.effect, onClick = { onPreset(FxPresets.forEffect(e).first()) }, label = { Text(e) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (p in FxPresets.forEffect(preset.effect)) FilterChip(selected = p == preset, onClick = { onPreset(p) }, label = { Text(p.id) })
            for (s in preset.states) FilterChip(selected = s == state, onClick = { onState(s) }, label = { Text(s) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dark", style = MaterialTheme.typography.labelMedium)
            Switch(dark, onDark, Modifier.semantics { contentDescription = "Dark theme" })
            Text("Reduced", style = MaterialTheme.typography.labelMedium)
            Switch(reduced, onReduced, Modifier.semantics { contentDescription = "Reduced motion" })
            FilterChip(selected = pausedAt != null, onClick = { onPausedAt(if (pausedAt == null) 0L else null) }, label = { Text(if (pausedAt == null) "Pause" else "Play") })
            Text(pausedAt?.let { "t=$it" } ?: "live", style = MaterialTheme.typography.labelMedium)
        }
        if (pausedAt != null) {
            Slider(
                value = pausedAt.toFloat(), onValueChange = { onPausedAt(it.toLong()) }, valueRange = 0f..3_000f,
                modifier = Modifier.semantics { contentDescription = "Timeline, ms" },
            )
        }
    }
}
