package com.bainbridgetrails.graph

import com.bainbridgetrails.analytics.Analytics
import com.bainbridgetrails.analytics.AnalyticsEvents
import com.bainbridgetrails.analytics.NoOpAnalytics
import com.bainbridgetrails.data.DatasetRepository
import com.bainbridgetrails.data.DatasetSources
import com.bainbridgetrails.data.ProgressRepository
import com.bainbridgetrails.data.SettingsRepository
import com.bainbridgetrails.data.StoredCoverage
import com.bainbridgetrails.data.WalkHistoryRepository
import com.bainbridgetrails.data.appDataDir
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.record.PlatformContext
import com.bainbridgetrails.record.RecordingGraph
import com.bainbridgetrails.record.trackingDir
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.tracking.CoverageStore
import com.bainbridgetrails.tracking.RecordingController
import com.bainbridgetrails.tracking.RecordingState
import com.bainbridgetrails.tracking.WalkIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide object graph (no DI framework). Context-free parts ([datasets]) exist from first use, so receivers
 * that run without UI (geofences) share the single parse; the rest is created by [install] with the platform
 * context (idempotent; the UI and the debug drawer both call it).
 *
 * Screens read state from the repositories' StateFlows (collectAsStateWithLifecycle); writes go through the
 * repositories. No ViewModels: everything here outlives configuration changes by living for the process.
 */
object AppGraph {
    /** Main: UI-facing coordination; heavy work moves itself to Default/IO. Shared with [RecordingGraph]. */
    val scope: CoroutineScope get() = RecordingGraph.scope

    /** Ruling R1: the bundled dataset, parsed once per process off Main. */
    val datasets: DatasetRepository by lazy {
        // Res.readBytes reads on the calling thread: keep every read on IO.
        DatasetRepository(
            scope,
            DatasetSources(
                trailsJson = { withContext(Dispatchers.IO) { Res.readBytes("files/trails.json") } },
                segmentsJson = { withContext(Dispatchers.IO) { Res.readBytes("files/coverage_segments.json").decodeToString() } },
                datasetBin = {
                    withContext(Dispatchers.IO) {
                        try {
                            Res.readBytes("files/dataset.bin")
                        } catch (e: Exception) { // not synced (scripts/sync-app-assets.sh): fall back to the JSON
                            if (e is CancellationException) throw e
                            null
                        }
                    }
                },
            ),
        )
    }

    /**
     * Starts the dataset load before any UI exists (platform entry points call it first thing), so parsing overlaps
     * the first composition instead of following it. Context-free and idempotent.
     */
    fun warmUp() {
        datasets.load()
    }

    /** Debug builds swap this for a logging implementation; release keeps [NoOpAnalytics] (spec: no analytics). */
    var analytics: Analytics = NoOpAnalytics

    private var installed: Installed? = null

    /** A request to show the celebration for [trailIds]; [key] dedupes (session id, or a debug-tool key). */
    data class Celebration(val key: String, val trailIds: List<String>)

    private val _celebrations = MutableSharedFlow<Celebration>(replay = 1, extraBufferCapacity = 4)
    /** Celebrations to show (finished walks, debug tools). Replay 1 so one emitted before the shell exists still shows. */
    val celebrations: SharedFlow<Celebration> = _celebrations.asSharedFlow()

    /** Keys whose celebration was already opened in this process (rotation/recomposition must not repeat it). */
    val celebrated: MutableSet<String> = mutableSetOf()

    fun requestCelebration(c: Celebration) {
        if (c.trailIds.isNotEmpty()) _celebrations.tryEmit(c)
    }

    class Installed internal constructor(val context: PlatformContext) {
        val trackingDir: String by lazy { trackingDir(context) }
        val progress = ProgressRepository(scope, datasets, loadCoverage = { CoverageStore(trackingDir).let { StoredCoverage(it.load(), it.completedIds()) } }, io = Dispatchers.IO)
        val walks = WalkHistoryRepository(scope, read = { WalkIndex(trackingDir).all() }, io = Dispatchers.IO)
        val settings = SettingsRepository(appDataDir(context) + "/settings.json", scope, io = Dispatchers.IO)

        /** The recording controller (built once; completes after crash recovery / auto-finalize on launch). */
        fun controller(): Deferred<RecordingController> = RecordingGraph.controller(context)

        /** Re-reads coverage-derived state (after a walk, a debug-tool write, or a sync). */
        fun coverageChanged() {
            progress.refresh()
            walks.refresh()
        }
    }

    /** Call from the UI entry point (and debug tools). Starts the dataset load and the controller. */
    fun install(context: PlatformContext): Installed = installed ?: Installed(context).also { graph ->
        installed = graph
        datasets.load()
        graph.coverageChanged()
        scope.launch {
            // Building the controller at launch finalizes stopped walks and finds unfinished ones right away
            // (progress and history are then current on every tab, not only after the Record tab opens).
            val controller = runCatching { graph.controller().await() }.getOrElse {
                AppLog.e("graph", "controller unavailable: ${it.message}")
                return@launch
            }
            controller.state.distinctUntilChanged { a, b -> a::class == b::class }.collect { s ->
                when (s) {
                    is RecordingState.Recording -> {
                        analytics.track(AnalyticsEvents.WALK_STARTED)
                        // Build the matching geometry during the walk, not at startup or when it is finalized.
                        scope.launch(Dispatchers.Default) {
                            try {
                                datasets.await().index.prepare()
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                AppLog.e("graph", "index prepare failed: ${e.message}") // finalizing retries (lazy)
                            }
                        }
                    }
                    is RecordingState.Done -> {
                        analytics.track(AnalyticsEvents.WALK_FINISHED, mapOf("verified" to s.summary.verified.toString(), "trails" to s.summary.trails.size.toString()))
                        s.summary.newlyCompleted.forEach { analytics.track(AnalyticsEvents.TRAIL_COMPLETED, mapOf("trail" to it)) }
                        graph.coverageChanged()
                        requestCelebration(Celebration(s.summary.sessionId, s.summary.newlyCompleted))
                    }
                    else -> Unit
                }
            }
        }
    }

    /** The installed graph, or null before the UI started (e.g. a geofence receiver in a cold process). */
    fun installedOrNull(): Installed? = installed
}
