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
import com.bainbridgetrails.data.DatasetProfile
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The roads prototype (docs/roads/plan.md P1): runs the whole app on `data/roads/dist` instead of the trail dataset.
 *
 * The dataset is parsed, indexed and rendered into the map style once per process (ruling R1), so the switch stores
 * the choice and takes effect on the next launch. `adb shell am start -n <pkg>/.MainActivity --es dataset roads` does
 * the same for one launch without the drawer.
 */
class DatasetPlugin(private val context: Context) : DebugPlugin {
    override val id = "bt-dataset"
    override val title = "Dataset"

    @Composable
    override fun Content(scope: DebugScope) {
        val running = remember { DatasetProfile.current }
        var stored by remember { mutableStateOf(DebugTools.storedDataset(context)) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Running: ${running.key}")
            Text(running.goal)
            if (DatasetProfile.isFixed) {
                Text("Fixed by the ${BuildConfig.GOAL} flavour (RD-1). Install the trails debug build to switch.")
                return@Column
            }
            Text("Next launch: ${stored.key}")
            for (p in DatasetProfile.entries) OutlinedButton(
                enabled = p != stored,
                onClick = {
                    DebugTools.storeDataset(context, p)
                    stored = p
                    AppLog.w("debug", "dataset for the next launch: ${p.key}")
                },
            ) { Text("Use ${p.things} (${p.metadataFile})") }
            if (stored != running) {
                Text("Kill and relaunch the app to run on ${stored.key}.")
                Button(onClick = { kotlin.system.exitProcess(0) }) { Text("Kill the app now") }
            }
        }
    }
}

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
                    Text("Dataset: ${DatasetProfile.current.key} ${dataset.version}")
                    Text("${DatasetProfile.current.things.replaceFirstChar { it.uppercase() }}: ${dataset.trails.size}")
                    Text("Segments: ${dataset.segments.size} (${dataset.segments.count { it.included }} counted)")
                    Text("Island total: ${Format.oneDecimal(dataset.islandTotalM / 1000)} km")
                    Text("Parse ($format): $parseMs ms · index: $indexMs ms")
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
                    withContext(Dispatchers.IO) { CoverageStore.writeLock.withLock { CoverageStore(graph.trackingDir).reset() } }
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
                    withContext(Dispatchers.IO) { CoverageStore.writeLock.withLock {
                        val store = CoverageStore(graph.trackingDir)
                        store.inject(coverTrail(d, target.trailId))
                        store.addCompleted(setOf(target.trailId))
                    } }
                    graph.coverageChanged()
                    AppGraph.requestCelebration(AppGraph.Celebration("debug-${target.trailId}", listOf(target.trailId)))
                    message = "Completed ${target.name}"
                    AppLog.i("debug", "marked ${target.trailId} complete from debug drawer")
                }
            }) { Text("Complete next unfinished trail") }
            // Map screenshots: partial statuses on the next untouched trail (first 40% / 80% of its walk).
            for ((label, share) in listOf("Partway (40%)" to 0.4, "Nearly done (80%)" to 0.8)) {
                OutlinedButton(enabled = ready != null && snapshot != null, onClick = {
                    val d = ready ?: return@OutlinedButton
                    val target = snapshot?.trails?.firstOrNull { it.status == TrailStatus.NOT_STARTED } ?: return@OutlinedButton
                    AppGraph.scope.launch {
                        withContext(Dispatchers.IO) { CoverageStore.writeLock.withLock { CoverageStore(graph.trackingDir).inject(coverTrail(d, target.trailId, share)) } }
                        graph.coverageChanged()
                        message = "$label: ${target.name}"
                    }
                }) { Text("$label next untouched trail") }
            }
            // Replays the celebration (plural variant) without touching coverage: the 3 shortest trails of 0.2 mi or more.
            OutlinedButton(enabled = snapshot != null, onClick = {
                val picks = snapshot?.trails?.filter { it.lengthMi >= 0.2 }?.sortedBy { it.lengthMi }?.take(3)?.map { it.trailId } ?: return@OutlinedButton
                AppGraph.requestCelebration(AppGraph.Celebration("debug-multi-${picks.joinToString()}-${System.currentTimeMillis()}", picks))
            }) { Text("Show celebration (3 trails)") }
            // P1-9: the whole-island variant with final numbers (100%, every trail), coverage untouched.
            OutlinedButton(onClick = {
                AppGraph.requestCelebration(AppGraph.Celebration("debug-island-${System.currentTimeMillis()}", emptyList(), island = true, preview = true))
            }) { Text("Show island 100% celebration") }
            message?.let { Text(it) }
        }
    }

    /** Coverage for every segment of [trailId]: a synthetic 1.5 m/s walk along each segment's geometry. */
    private fun coverTrail(d: TrailData, trailId: String, share: Double = 1.0) = run {
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
        d.index.coverFixes(if (share >= 1.0) fixes else fixes.take((fixes.size * share).toInt()))
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
