package com.bainbridgetrails.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.tracking.CoverageStore
import com.bainbridgetrails.tracking.SessionStore
import com.bainbridgetrails.ui.Format
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dataset version, counts and load timings (parse once per process: ruling R1). */
object TrailsDataPlugin : DebugPlugin {
    override val id = "bt-trails-data"
    override val title = "Trails data"

    @Composable
    override fun Content(scope: DebugScope) {
        val state by AppGraph.datasets.state.collectAsStateWithLifecycle()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val s = state) {
                Loadable.Loading -> Text("Loading…")
                is Loadable.Failed -> Text("Failed: ${s.message}")
                is Loadable.Ready -> with(s.value) {
                    Text("Dataset version: ${dataset.version}")
                    Text("Trails: ${dataset.trails.size}")
                    Text("Segments: ${dataset.segments.size} (${dataset.segments.count { it.included }} counted)")
                    Text("Island total: ${Format.oneDecimal(dataset.islandTotalM / 1000)} km")
                    Text("Parse: $parseMs ms · index: $indexMs ms")
                    Text("Parses this process: ${AppGraph.datasets.parseCount}")
                }
            }
        }
    }
}

/** Island %, reset coverage, complete a trail (to exercise the celebration). */
class CoveragePlugin(private val context: Context) : DebugPlugin {
    override val id = "bt-coverage"
    override val title = "Coverage"

    @Composable
    override fun Content(scope: DebugScope) {
        val graph = remember { AppGraph.install(context) }
        val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
        val data by AppGraph.datasets.state.collectAsStateWithLifecycle()
        var message by remember { mutableStateOf<String?>(null) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            snapshot?.let {
                Text("Island: ${Format.percent(it.islandFraction)} (${Format.oneDecimal(it.coveredM / 1000)} km)")
                Text("Trails completed: ${it.trailsCompleted} / ${it.trailCount}")
                Text("Segment buckets: " + it.segmentBuckets.entries.joinToString { (k, v) -> "${k.name.lowercase()}=${v.size}" })
            }
            OutlinedButton(onClick = {
                AppGraph.scope.launch {
                    withContext(Dispatchers.IO) { CoverageStore(graph.trackingDir).reset() }
                    graph.coverageChanged()
                    message = "Coverage reset"
                    AppLog.w("debug", "coverage reset from debug drawer")
                }
            }) { Text("Reset coverage") }
            val ready = (data as? Loadable.Ready)?.value
            Button(enabled = ready != null && snapshot != null, onClick = {
                val d = ready ?: return@Button
                val target = snapshot?.trails?.firstOrNull { it.status != TrailStatus.COMPLETE } ?: return@Button
                AppGraph.scope.launch {
                    withContext(Dispatchers.IO) {
                        val store = CoverageStore(graph.trackingDir)
                        store.inject(coverTrail(d, target.trailId))
                        store.addCompleted(setOf(target.trailId))
                    }
                    graph.coverageChanged()
                    AppGraph.requestCelebration(AppGraph.Celebration("debug-${target.trailId}", listOf(target.trailId)))
                    message = "Completed ${target.name}"
                    AppLog.i("debug", "marked ${target.trailId} complete from debug drawer")
                }
            }) { Text("Complete next unfinished trail") }
            message?.let { Text(it) }
        }
    }

    /** Coverage for every segment of [trailId]: a synthetic 1.5 m/s walk along each segment's geometry. */
    private fun coverTrail(d: TrailData, trailId: String) = run {
        val trail = d.dataset.trails.first { it.id == trailId }
        val segments = d.dataset.segments.associateBy { it.id }
        var t = 0L
        val fixes = ArrayList<Fix>()
        for (sid in trail.segmentIds.distinct()) {
            val coords = segments[sid]?.coords ?: continue
            for (i in 1 until coords.size) {
                val (lon0, lat0) = coords[i - 1]
                val (lon1, lat1) = coords[i]
                val steps = 20
                for (k in 0..steps) {
                    val f = k.toDouble() / steps
                    fixes += Fix(t, lon0 + (lon1 - lon0) * f, lat0 + (lat1 - lat0) * f, 3.0, false)
                    t += 1_000
                }
            }
            t += 120_000 // a gap between segments: no spurious joins
        }
        d.index.coverFixes(fixes)
    }
}

/** Controller state and on-disk session counts. */
class RecordingPlugin(private val context: Context) : DebugPlugin {
    override val id = "bt-recording"
    override val title = "Recording"

    @Composable
    override fun Content(scope: DebugScope) {
        val graph = remember { AppGraph.install(context) }
        val controller by produceState<com.bainbridgetrails.tracking.RecordingController?>(null) { value = runCatching { graph.controller().await() }.getOrNull() }
        val history by graph.walks.history.collectAsStateWithLifecycle()
        val counts by produceState<Pair<Int, Int>?>(null) {
            value = withContext(Dispatchers.IO) { SessionStore(graph.trackingDir).let { it.finished().size to it.unfinished().size } }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val state = controller?.state?.collectAsStateWithLifecycle()?.value
            Text("State: ${state?.let { it::class.simpleName } ?: "not started"}")
            counts?.let { (finished, open) -> Text("Session files: $finished finished, $open unfinished") }
            Text("Walks in history: ${history?.walks?.size ?: 0}")
            Text("Trail km walked: ${Format.oneDecimal((history?.trailMetresWalked ?: 0.0) / 1000)}")
        }
    }
}
