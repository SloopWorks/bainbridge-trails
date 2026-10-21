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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.graph.AppGraph
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

/** Play clock (debug; ux.md § 3.19): the in-app face of the drawer's PlayClockPlugin. Debug builds only. */
@Composable
fun PlayClockDebugScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit) {
    val clock = AppGraph.playClock as? DebugPlayClock
    val play = rememberPlay(graph, settings.play.enabled)
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf<String?>(null) }
    ListScreen(stringResource(Res.string.play_settings_clock_debug), onBack = onBack) {
        item {
            Column(Modifier.padding(horizontal = BtTheme.spacing.screenH), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                if (clock == null) {
                    Text("No debug Play clock bound.")
                    return@Column
                }
                val o by clock.overrides.collectAsStateWithLifecycle()
                Text("Now: ${DebugPlayClock.describe(clock.now())} · ${o.mode}", style = MaterialTheme.typography.bodyMedium)
                Text("Tide: ${o.tide.name.lowercase()} · bundled: ${o.bundledConfig}", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                    OutlinedButton(onClick = { clock.set(PlayClockMode.Real) }) { Text("Real") }
                    OutlinedButton(onClick = { clock.fixAt(clock.now()) }) { Text("Fixed") }
                    OutlinedButton(onClick = { clock.startAt(clock.now()) }) { Text("Offset") }
                    OutlinedButton(onClick = { clock.shift(1.days) }) { Text("+1 d") }
                    OutlinedButton(onClick = { clock.shift(7.days) }) { Text("+7 d") }
                    OutlinedButton(onClick = { clock.fixAt(DebugPlayClock.nextThursday(clock.now(), hour = 14)) }) { Text("Next Thu 14:00") }
                    for (t in PlayTideOverride.entries) OutlinedButton(enabled = t != o.tide, onClick = { clock.setTide(t) }) { Text(t.name.lowercase()) }
                }
                if (play != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                        OutlinedButton(onClick = { scope.launch { play.content.use(PlayConfigSource.BUNDLED); play.reload() } }) { Text("Bundled") }
                        OutlinedButton(onClick = { scope.launch { play.content.use(PlayConfigSource.CACHED); play.reload() } }) { Text("Cached") }
                        OutlinedButton(onClick = { scope.launch { play.content.fetchNow(); play.reload() } }) { Text("Fetch now") }
                        Button(onClick = { scope.launch { output = play.agendaDump() } }) { Text("Agenda dump") }
                        OutlinedButton(onClick = { scope.launch { output = play.benchmark() } }) { Text("Benchmark") }
                        OutlinedButton(onClick = { scope.launch { play.store.clearFinds(); output = "Finds cleared" } }) { Text("Clear finds") }
                    }
                }
                output?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
