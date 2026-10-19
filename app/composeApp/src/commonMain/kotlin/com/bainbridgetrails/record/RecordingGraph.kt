package com.bainbridgetrails.record

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.tracking.CoverageStore
import com.bainbridgetrails.tracking.Finalizer
import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.tracking.Recorder
import com.bainbridgetrails.tracking.RecordingBus
import com.bainbridgetrails.tracking.RecordingController
import com.bainbridgetrails.tracking.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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

    private var dataset: Deferred<Dataset>? = null
    private var controller: Deferred<RecordingController>? = null

    /** Ruling R1: parse the bundled dataset once per process, off the main thread. Call at app start. */
    fun dataset(): Deferred<Dataset> = dataset?.takeUnless { it.failed() } ?: scope.async(Dispatchers.Default) {
        val trails = Res.readBytes("files/trails.json").decodeToString()
        val segments = Res.readBytes("files/coverage_segments.json").decodeToString()
        Dataset.parse(trails, segments)
    }.also { dataset = it }

    /**
     * Builds the segment index (lazily: first use of the Record screen) and the controller, once per process.
     * Completes after the recovery check, so the screen never offers Start before a pending Recover is known.
     */
    fun controller(context: PlatformContext): Deferred<RecordingController> = controller?.takeUnless { it.failed() } ?: scope.async {
        val ds = dataset().await()
        val dir = trackingDir(context)
        val (index, store, coverage) = withContext(Dispatchers.Default) {
            Triple(SegmentIndex(ds.segments, CoverageParams(), LocalProjection()), SessionStore(dir), CoverageStore(dir))
        }
        val recorder = object : Recorder {
            override fun start(sessionId: String, resume: Boolean) = startRecordingService(context, sessionId, resume)
            override fun stop() = stopRecordingService(context)
        }
        RecordingController(store, Finalizer(ds, index, store, coverage), { Clock.System.now().toEpochMilliseconds() }, recorder, scope).also {
            it.bind(RecordingBus.events, RecordingBus.lost)
            it.onLaunch()
        }
    }.also { controller = it }

    /** A failed load is not cached: the next call (the screen's Retry) starts over. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun Deferred<*>.failed(): Boolean = isCompleted && getCompletionExceptionOrNull() != null
}
