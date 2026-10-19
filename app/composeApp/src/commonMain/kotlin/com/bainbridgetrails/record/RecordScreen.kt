package com.bainbridgetrails.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.map.TrailMap
import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.tracking.RecordingController
import com.bainbridgetrails.tracking.RecordingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Clock

private sealed interface Load {
    data object Loading : Load
    data class Ready(val controller: RecordingController) : Load
    data class Failed(val message: String) : Load
}

/** Record a walk over the Topo map: Start/Stop, elapsed, distance, GPS chip, live track, results. */
@Composable
fun RecordScreen(launch: SpikeLaunch = SpikeLaunch(), modifier: Modifier = Modifier) {
    val context = rememberPlatformContext()
    val load by produceState<Load>(Load.Loading, context) {
        value = try {
            Load.Ready(RecordingGraph.controller(context).await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed("Trail data failed to load: ${e.message}")
        }
    }
    when (val l = load) {
        is Load.Ready -> RecordContent(l.controller, launch, context, modifier)
        else -> Box(modifier.fillMaxSize()) {
            TrailMap(MapStyle.TOPO, Modifier.fillMaxSize(), initialCamera = launch.camera)
            BottomPanel(Modifier.align(Alignment.BottomCenter)) {
                Text(if (l is Load.Failed) l.message else "Loading trails…", fontSize = 16.sp, color = Color(0xFF1E2A24))
            }
        }
    }
}

@Composable
private fun RecordContent(controller: RecordingController, launch: SpikeLaunch, context: PlatformContext, modifier: Modifier) {
    val state by controller.state.collectAsState()
    val track by controller.track.collectAsState()
    val notice by controller.notice.collectAsState()
    val requester = rememberPermissionRequester()
    var permissionQueue by remember { mutableStateOf<List<Perm>>(emptyList()) }
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }

    val recording = state is RecordingState.Recording
    LaunchedEffect(recording) {
        while (recording) {
            now = Clock.System.now().toEpochMilliseconds()
            controller.tick()
            delay(1_000)
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6_000)
            controller.clearNotice()
        }
    }

    fun startWithPermissions(granted: Set<Perm>) {
        controller.onPermissions(granted)
        controller.start()
    }

    fun onStartTapped() {
        val ask = permissionsToAsk(requester, RecordingGraph.askedOptional)
        if (ask.isEmpty()) startWithPermissions(Perm.entries.filterTo(mutableSetOf()) { requester.status(it) == PermStatus.GRANTED })
        else permissionQueue = ask
    }

    val last = track.lastOrNull()
    Box(modifier.fillMaxSize()) {
        TrailMap(
            MapStyle.TOPO,
            Modifier.fillMaxSize(),
            initialCamera = launch.camera,
            controlsTopPadding = 48.dp,
            follow = last?.let { Position(longitude = it.lon, latitude = it.lat) },
        ) {
            TrackLayer(track)
        }

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
            Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (state as? RecordingState.Recording)?.let { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (r.waitingForGps) Chip("Waiting for GPS", Color(0xFFFFE9B0), Color(0xFF6B4A00))
                        else Chip("GPS ok", Color(0xFFD7F0DC), Color(0xFF1B5E20))
                        if (!r.activityAvailable || !r.stepsAvailable) Chip("Unverified", Color(0xFFEDE7DA), Color(0xFF5A4A2A))
                    }
                }
                notice?.let { Chip(it, Color(0xEE26302B), Color.White) }
            }
        }

        when (val s = state) {
            is RecordingState.Done -> ResultsSheet(s.summary, onClose = { controller.dismiss() }, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
            is RecordingState.Recover -> BottomPanel(Modifier.align(Alignment.BottomCenter)) {
                Text("Unfinished walk found", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E2A24))
                Text("The app stopped while recording. Count what was recorded?", fontSize = 15.sp, color = Color(0xFF2E3A33))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { RecordingGraph.scope.launch { controller.finishRecovered(s.sessionId) } }) { Text("Finish walk") }
                    TextButton(onClick = { controller.discardRecovered(s.sessionId) }) { Text("Discard") }
                }
            }
            is RecordingState.NeedsPermission -> BottomPanel(Modifier.align(Alignment.BottomCenter)) {
                Text("Location is needed to record a walk.", fontSize = 16.sp, color = Color(0xFF1E2A24))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (requester.status(Perm.LOCATION_WHILE_IN_USE) == PermStatus.PERMANENTLY_DENIED) {
                        Button(onClick = { openAppSettings(context) }) { Text("Open settings") }
                    } else {
                        Button(onClick = { onStartTapped() }) { Text("Allow location") }
                    }
                    TextButton(onClick = { controller.dismiss() }) { Text("Not now") }
                }
            }
            is RecordingState.Finalizing, is RecordingState.Stopped -> BottomPanel(Modifier.align(Alignment.BottomCenter)) {
                if (s is RecordingState.Stopped) Text("Recording stopped: ${s.reason}", fontSize = 15.sp, color = Color(0xFFB3261E))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                    Text("Matching your walk to trails…", fontSize = 16.sp, color = Color(0xFF1E2A24))
                }
            }
            is RecordingState.Recording, RecordingState.Idle -> BottomPanel(Modifier.align(Alignment.BottomCenter)) {
                val r = s as? RecordingState.Recording
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Time", elapsed(r?.let { (now - it.startedMs).coerceAtLeast(0) } ?: 0))
                    Stat("Distance", distance(r?.distanceM ?: 0.0))
                    Stat("Fixes", (r?.fixCount ?: 0).toString())
                }
                Button(
                    onClick = { if (r != null) RecordingGraph.scope.launch { controller.stop() } else onStartTapped() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (r != null) Color(0xFFB3261E) else Color(0xFF1B6E3A)),
                ) { Text(if (r != null) "Stop" else "Start", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            }
        }

        if (permissionQueue.isNotEmpty()) {
            PermissionFlow(
                queue = permissionQueue,
                requester = requester,
                onAsked = { if (!rationale(it).required) RecordingGraph.askedOptional += it },
                onOpenSettings = { openAppSettings(context) },
                onDone = { granted ->
                    permissionQueue = emptyList()
                    startWithPermissions(granted)
                },
            )
        }
    }
}

@Composable
private fun BottomPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 12.dp, end = 12.dp, bottom = 36.dp)
            .fillMaxWidth()
            .background(Color(0xF7FFFFFF), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color) {
    Text(
        text,
        Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E2A24))
        Text(label, fontSize = 12.sp, color = Color(0xFF5A635D))
    }
}

internal fun elapsed(ms: Long): String {
    val s = ms / 1000
    val two = { v: Long -> v.toString().padStart(2, '0') }
    return if (s >= 3600) "${s / 3600}:${two(s / 60 % 60)}:${two(s % 60)}" else "${two(s / 60)}:${two(s % 60)}"
}

internal fun distance(m: Double): String = if (m < 1000) "${m.toLong()} m" else "${oneDecimal(m / 1000)} km"
