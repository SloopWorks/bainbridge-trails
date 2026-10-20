package com.bainbridgetrails.data

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** The parsed dataset plus everything derived from it once per process. */
class TrailData(
    val dataset: Dataset,
    val index: SegmentIndex,
    val params: CoverageParams,
    /** Wall time of [Dataset.parse] / [SegmentIndex] construction (debug drawer, perf notes). */
    val parseMs: Long,
    val indexMs: Long,
) {
    val progress: Progress = Progress(dataset, index, params)
}

/**
 * Parses the bundled dataset once per process on [compute] (never Main) and builds the segment index right after.
 * [load] is idempotent while loading/loaded; after a failure it starts over (the UI's Retry).
 */
class DatasetRepository(
    private val scope: CoroutineScope,
    /** Reads trails.json and coverage_segments.json (resources); may suspend on I/O. */
    private val readSources: suspend () -> Pair<String, String>,
    private val params: CoverageParams = CoverageParams(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<Loadable<TrailData>>(Loadable.Loading)
    val state: StateFlow<Loadable<TrailData>> = _state.asStateFlow()

    private var job: Job? = null

    /** Number of parses started (tests: proves parse-once). */
    var parseCount: Int = 0
        private set

    fun load() {
        if (job?.isActive == true || _state.value is Loadable.Ready) return
        _state.value = Loadable.Loading
        job = scope.launch {
            _state.value = try {
                Loadable.Ready(withContext(compute) { build() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "dataset load failed: ${e.message}")
                Loadable.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** Starts loading if needed and suspends until ready; throws if the load fails. */
    suspend fun await(): TrailData {
        load()
        return when (val s = _state.filter { it !is Loadable.Loading }.first()) {
            is Loadable.Ready -> s.value
            is Loadable.Failed -> throw IllegalStateException("Trail data failed to load: ${s.message}")
            Loadable.Loading -> error("unreachable")
        }
    }

    private suspend fun build(): TrailData {
        parseCount++
        val (trails, segments) = readSources()
        val t0 = TimeSource.Monotonic.markNow()
        val dataset = Dataset.parse(trails, segments)
        val parseMs = t0.elapsedNow().inWholeMilliseconds
        val t1 = TimeSource.Monotonic.markNow()
        val index = SegmentIndex(dataset.segments, params, LocalProjection())
        val indexMs = t1.elapsedNow().inWholeMilliseconds
        AppLog.i(TAG, "dataset ${dataset.version}: ${dataset.trails.size} trails, ${dataset.segments.size} segments; parse $parseMs ms, index $indexMs ms")
        return TrailData(dataset, index, params, parseMs, indexMs)
    }

    private companion object {
        const val TAG = "dataset"
    }
}
