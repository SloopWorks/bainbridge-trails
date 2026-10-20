package com.bainbridgetrails.data

import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.ProgressSnapshot
import com.bainbridgetrails.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What progress is computed from: merged coverage + sticky completed trail ids (decisions.md TL-7). */
data class StoredCoverage(val state: CoverageState, val stickyCompleted: Set<String> = emptySet())

/**
 * The user's progress, recomputed only when the stored [CoverageState] changes (memoized by value), off Main.
 * Call [refresh] at start and after anything that writes coverage (finalized walk, debug tools, sync).
 * [snapshot] is null until the first computation (dataset still loading).
 */
class ProgressRepository(
    private val scope: CoroutineScope,
    private val data: DatasetRepository,
    /** Reads the persisted coverage (blocking file I/O; runs on [io]). */
    private val loadCoverage: () -> StoredCoverage,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _snapshot = MutableStateFlow<ProgressSnapshot?>(null)
    val snapshot: StateFlow<ProgressSnapshot?> = _snapshot.asStateFlow()

    private val mutex = Mutex()
    private var lastState: StoredCoverage? = null

    /** Snapshot computations so far (tests: proves memoization). */
    var computeCount: Int = 0
        private set

    fun refresh() {
        scope.launch {
            try {
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("progress", "refresh failed: ${e.message}")
            }
        }
    }

    /** Suspending [refresh]: returns the current snapshot. */
    suspend fun refreshNow(): ProgressSnapshot = mutex.withLock {
        val trailData = data.await()
        val state = withContext(io) { loadCoverage() }
        val cached = _snapshot.value
        if (cached != null && state == lastState) return cached
        val next = withContext(compute) { trailData.progress.snapshot(state.state, state.stickyCompleted) }
        computeCount++
        lastState = state
        _snapshot.value = next
        next
    }
}
