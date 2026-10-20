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
    /** Which segments file was parsed: "bin" (coverage_segments.bin) or "json" (fallback). */
    val segmentsFormat: String = "json",
) {
    val progress: Progress = Progress(dataset, index, params)
}

/**
 * The bundled dataset files (resources in the app, strings in tests); each reader may suspend on I/O.
 * [segmentsBin] (coverage_segments.bin, [com.bainbridgetrails.coverage.SegmentsBinary]) is preferred; the JSON is
 * read only when the binary is absent, unreadable or of another dataset version.
 */
class DatasetSources(
    val trails: suspend () -> String,
    val segmentsJson: suspend () -> String,
    val segmentsBin: suspend () -> ByteArray? = { null },
)

/**
 * Parses the bundled dataset once per process on [compute] (never Main) and builds the segment index right after
 * (tick counts only: enough for [Progress]). The index's matching geometry is then prepared in the background so
 * the first walk/geofence match is fast, without delaying [state] Ready.
 * [load] is idempotent while loading/loaded; after a failure it starts over (the UI's Retry).
 */
class DatasetRepository(
    private val scope: CoroutineScope,
    private val sources: DatasetSources,
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
                val data = withContext(compute) { build() }
                scope.launch(compute) { prepare(data) }
                Loadable.Ready(data)
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
        val trails = sources.trails()
        val bin = sources.segmentsBin()
        val t0 = TimeSource.Monotonic.markNow()
        var format = "bin"
        val dataset = bin?.let { bytes ->
            try {
                Dataset.parse(trails, bytes)
            } catch (e: Exception) { // corrupt, truncated or another dataset version
                AppLog.w(TAG, "coverage_segments.bin unusable (${e.message}); parsing the JSON")
                null
            }
        } ?: run {
            format = "json"
            Dataset.parse(trails, sources.segmentsJson())
        }
        val parseMs = t0.elapsedNow().inWholeMilliseconds
        val t1 = TimeSource.Monotonic.markNow()
        val index = SegmentIndex(dataset.segments, params, LocalProjection())
        val indexMs = t1.elapsedNow().inWholeMilliseconds
        AppLog.i(TAG, "dataset ${dataset.version}: ${dataset.trails.size} trails, ${dataset.segments.size} segments; parse ($format) $parseMs ms, index $indexMs ms")
        return TrailData(dataset, index, params, parseMs, indexMs, format)
    }

    private fun prepare(data: TrailData) {
        val t = TimeSource.Monotonic.markNow()
        try {
            data.index.prepare()
            AppLog.i(TAG, "index prepared in ${t.elapsedNow().inWholeMilliseconds} ms")
        } catch (e: Exception) {
            AppLog.e(TAG, "index prepare failed: ${e.message}") // the first match retries (lazy)
        }
    }

    private companion object {
        const val TAG = "dataset"
    }
}
