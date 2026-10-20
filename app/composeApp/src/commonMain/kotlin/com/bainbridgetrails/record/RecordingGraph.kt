package com.bainbridgetrails.record

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.tracking.CoverageStore
import com.bainbridgetrails.tracking.Finalizer
import com.bainbridgetrails.tracking.Geofences
import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.tracking.Recorder
import com.bainbridgetrails.tracking.RecordingBus
import com.bainbridgetrails.tracking.RecordingController
import com.bainbridgetrails.tracking.SessionStore
import com.bainbridgetrails.tracking.Trailhead
import com.bainbridgetrails.tracking.WalkIndex
import com.bainbridgetrails.graph.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Process-wide recording objects. The controller outlives screens and activities (it must, to finish a
 * walk after a configuration change), so it lives here rather than in composition.
 */
internal object RecordingGraph {
    /** Main: the controller is single-threaded; Finalizer and loading move their own work off it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Optional permissions already offered in this process (not asked again on every Start). */
    val askedOptional = mutableSetOf<Perm>()

    private var trailheads: Deferred<List<Trailhead>>? = null
    private var controller: Deferred<RecordingController>? = null

    /** Ruling R1: the bundled dataset, parsed once per process off Main (shared with the UI via [AppGraph.datasets]). */
    fun dataset(): Deferred<Dataset> = scope.async { AppGraph.datasets.await().dataset }

    /** Segment index for the bundled dataset, built once per process off Main together with the dataset. */
    fun index(): Deferred<SegmentIndex> = scope.async { AppGraph.datasets.await().index }

    /** Bundled trailhead geofence candidates (`files/trailheads.json`), parsed once per process. */
    fun trailheads(): Deferred<List<Trailhead>> = trailheads?.takeUnless { it.failed() } ?: scope.async(Dispatchers.Default) {
        Geofences.parse(Res.readBytes("files/trailheads.json").decodeToString())
    }.also { trailheads = it }

    /**
     * Arrival notification text for [trailheadId] from the persisted coverage (null if unknown). Suspends while
     * the dataset/index load and reads coverage on a background dispatcher; safe to call from a receiver.
     */
    suspend fun arrivalText(context: PlatformContext, trailheadId: String): String? {
        val th = trailheads().await().firstOrNull { it.id == trailheadId } ?: return null
        val ds = dataset().await()
        val idx = index().await()
        return withContext(Dispatchers.Default) {
            val coverage = CoverageStore(trackingDir(context)).load()
            Geofences.arrivalText(th, coverage, Progress(ds, idx, CoverageParams()))
        }
    }

    /**
     * Builds the segment index (lazily: first use of the Record screen) and the controller, once per process.
     * Completes after the recovery check, so the screen never offers Start before a pending Recover is known.
     */
    fun controller(context: PlatformContext): Deferred<RecordingController> = controller?.takeUnless { it.failed() } ?: scope.async {
        val ds = dataset().await()
        val dir = trackingDir(context)
        val index = index().await()
        val (store, coverage) = withContext(Dispatchers.Default) { SessionStore(dir) to CoverageStore(dir) }
        val recorder = object : Recorder {
            override fun start(sessionId: String, resume: Boolean) = startRecordingService(context, sessionId, resume)
            override fun stop() = stopRecordingService(context)
        }
        RecordingController(store, Finalizer(ds, index, store, coverage, walks = WalkIndex(dir)), { Clock.System.now().toEpochMilliseconds() }, recorder, scope).also {
            it.bind(RecordingBus.events, RecordingBus.lost)
            it.onLaunch()
        }
    }.also { controller = it }

    private var live: LiveCoverageSession? = null

    /** A walk the user chose to "Count it" after a crash: its results show the recovered banner. */
    var recoveredSessionId: String? = null

    /**
     * Provisional coverage for the recording [sessionId] (TL-8), kept for the process so minimising the Record
     * screen does not lose it. The pre-walk state is read from the coverage store on first use (the walk itself is
     * only merged when it is finalised).
     */
    fun liveCoverage(context: PlatformContext, sessionId: String): LiveCoverageSession =
        live?.takeIf { it.sessionId == sessionId } ?: LiveCoverageSession(sessionId, create = {
            val data = AppGraph.datasets.await()
            val (pre, sticky) = withContext(Dispatchers.IO) { CoverageStore(trackingDir(context)).let { it.load() to it.completedIds() } }
            LiveCoverage.forDataset(data, pre, sticky)
        }).also { live = it }

    /** A failed load is not cached: the next call (the screen's Retry) starts over. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun Deferred<*>.failed(): Boolean = isCompleted && getCompletionExceptionOrNull() != null
}
