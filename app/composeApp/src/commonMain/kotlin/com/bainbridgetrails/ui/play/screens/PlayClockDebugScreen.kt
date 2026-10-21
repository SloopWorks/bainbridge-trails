package com.bainbridgetrails.ui.play.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.play.DebugPlayClock
import com.bainbridgetrails.play.PlayClockMode
import com.bainbridgetrails.play.PlayConfigSource
import com.bainbridgetrails.play.PlayTideOverride
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_settings_clock_debug
import com.bainbridgetrails.ui.ListScreen
import com.bainbridgetrails.ui.play.components.rememberPlay
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.days

/** Play clock (debug; ux.md § 3.19): the in-app face of [PlayClockDebugBody] (also the drawer's PlayClockPlugin). */
@Composable
fun PlayClockDebugScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit) {
    ListScreen(stringResource(Res.string.play_settings_clock_debug), onBack = onBack) {
        item { PlayClockDebugBody(graph, Modifier.padding(horizontal = BtTheme.spacing.screenH)) }
    }
}

/**
 * Play time travel and pack tools (schedule.md § 4.1, plan.md L2-3), shared by [PlayClockDebugScreen] and the debug
 * drawer's PlayClockPlugin: Play on / off, Real / Fixed / Offset Play clock (+1 d, +7 d, next Thu 06:00 / 14:00,
 * Mar 30), tide override, config source (bundled / cached / fetch now, then a refresh), agenda dump, benchmark and
 * clear finds. Writes `AppGraph.playClock` (a DebugPlayClock bound by the debug tools); work runs on `AppGraph.scope`
 * so closing the drawer never cancels it.
 */
@Composable
fun PlayClockDebugBody(graph: AppGraph.Installed, modifier: Modifier = Modifier) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val enabled = settings?.play?.enabled == true
    val play = rememberPlay(graph, enabled)
    val clock = AppGraph.playClock as? DebugPlayClock
    var output by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
        Text("Play: ${if (enabled) "on" else "off"}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { graph.settings.update { it.copy(play = it.play.copy(enabled = !enabled, everToggled = true)) } }) {
            Text(if (enabled) "Turn Play off" else "Turn Play on (debug)")
        }
        if (clock == null) {
            Text("No debug Play clock bound.")
            return@Column
        }
        val o by clock.overrides.collectAsStateWithLifecycle()
        Text("Now: ${DebugPlayClock.describe(clock.now())} · ${o.mode}", style = MaterialTheme.typography.bodyMedium)
        Text("Tide: ${o.tide.name.lowercase()} · bundled pack forced: ${o.bundledConfig}", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
            OutlinedButton(onClick = { clock.set(PlayClockMode.Real) }) { Text("Real") }
            OutlinedButton(onClick = { clock.fixAt(clock.now()) }) { Text("Fixed") }
            OutlinedButton(onClick = { clock.startAt(clock.now()) }) { Text("Offset") }
            OutlinedButton(onClick = { clock.shift(1.days) }) { Text("+1 d") }
            OutlinedButton(onClick = { clock.shift(7.days) }) { Text("+7 d") }
            OutlinedButton(onClick = { clock.fixAt(DebugPlayClock.nextThursday(clock.now())) }) { Text("Next Thu 06:00") }
            OutlinedButton(onClick = { clock.fixAt(DebugPlayClock.nextThursday(clock.now(), hour = 14)) }) { Text("Next Thu 14:00") }
            OutlinedButton(onClick = { clock.fixAt(DebugPlayClock.nextMar30(clock.now())) }) { Text("Mar 30") }
            for (t in PlayTideOverride.entries) OutlinedButton(enabled = t != o.tide, onClick = { clock.setTide(t) }) { Text(t.name.lowercase()) }
        }
        val cs = play?.config?.collectAsStateWithLifecycle()?.value
        Text("Pack: ${cs?.version ?: "—"} (${cs?.source?.name?.lowercase() ?: "Play off"})" + (cs?.rejected?.let { " · rejected: $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        if (play != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                OutlinedButton(onClick = { AppGraph.scope.launch { play.content.use(PlayConfigSource.BUNDLED); play.refresh() } }) { Text("Bundled") }
                OutlinedButton(onClick = { AppGraph.scope.launch { play.content.use(PlayConfigSource.CACHED); play.refresh() } }) { Text("Cached") }
                OutlinedButton(onClick = { AppGraph.scope.launch { play.content.fetchNow(); play.refresh() } }) { Text("Fetch now") }
                Button(onClick = { AppGraph.scope.launch { output = play.agendaDump().also { AppLog.i("play", it) } } }) { Text("Agenda dump") }
                OutlinedButton(onClick = { AppGraph.scope.launch { output = play.benchmark() } }) { Text("Benchmark") }
                OutlinedButton(onClick = { AppGraph.scope.launch { play.store.clearFinds(); output = "Finds cleared" } }) { Text("Clear finds") }
            }
        }
        output?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
