package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TrailResult(val trailId: String, val name: String, val before: Double, val after: Double, val status: TrailStatus)

data class SessionSummary(
    val sessionId: String,
    val islandBefore: Double,
    val islandAfter: Double,
    val trails: List<TrailResult>,
    val verified: Boolean,
    val droppedVehicle: Int,
    val droppedSteps: Int,
    val newlyCompleted: List<String>,
    /** The walk was recorded with approximate (reduced-accuracy) location: trails may not match. */
    val approximateLocation: Boolean = false,
)

/**
 * Gates a finished session, matches it to the trail network and merges it into [coverage].
 * Raw session files are kept (the caller decides when to delete them).
 * [onWork] is a test hook invoked inside the background context.
 */
class Finalizer(
    private val dataset: Dataset,
    private val index: SegmentIndex,
    private val store: SessionStore,
    private val coverage: CoverageStore,
    private val params: CoverageParams = CoverageParams(),
    private val onWork: suspend () -> Unit = {},
    /** Walk history index (no coordinates), updated on every finalize; null = not kept. */
    private val walks: WalkIndex? = null,
) {
    private val progress = Progress(dataset, index, params)
    private val mutex = Mutex()

    /** Ids already finalised after their Stop. Blocking file read: call off the main thread. */
    fun finalizedIds(): Set<String> = coverage.finalizedIds()

    /** Early-merged sessions whose summary was already shown. Blocking file read: call off the main thread. */
    fun shownIds(): Set<String> = coverage.shownIds()

    suspend fun finalize(sessionId: String): SessionSummary = withContext(Dispatchers.Default) { mutex.withLock {
        onWork()
        val session = store.load(sessionId)
        val gate = Gating.gate(session, index.projection)
        val before = coverage.load()
        val after = before.union(index.coverFixes(gate.fixes))
        // A session without its Stop is merged early (stop timeout); its summary is shown now, so remember that.
        coverage.merge(sessionId, after, gate.verified, finalized = session.finished, summaryShown = !session.finished)

        val rose = mutableListOf<TrailResult>()
        val completed = mutableListOf<String>()
        for (t in dataset.trails) {
            val b = progress.trailFraction(before, t.id)
            val a = progress.trailFraction(after, t.id)
            if (a > b) {
                val status = progress.status(a)
                rose += TrailResult(t.id, t.name, b, a, status)
                if (status == TrailStatus.COMPLETE && progress.status(b) != TrailStatus.COMPLETE) completed += t.id
            }
        }
        val sorted = rose.sortedByDescending { it.after }
        walks?.upsert(
            WalkRecord(
                sessionId = sessionId,
                startMs = session.fixes.minOfOrNull { it.timeMs } ?: startOf(sessionId),
                endMs = session.fixes.maxOfOrNull { it.timeMs } ?: startOf(sessionId),
                distanceM = WalkIndex.distanceM(gate.fixes.filterNot { it.isMock }.sortedBy { it.timeMs }, index.projection),
                trailIds = sorted.map { it.trailId },
                verified = gate.verified,
            ),
        )
        SessionSummary(
            sessionId = sessionId,
            islandBefore = progress.islandFraction(before),
            islandAfter = progress.islandFraction(after),
            trails = sorted,
            verified = gate.verified,
            droppedVehicle = gate.droppedVehicle,
            droppedSteps = gate.droppedSteps,
            newlyCompleted = completed,
            approximateLocation = session.approximateLocation,
        )
    } }
}

/** Session ids are "s<startMs>-<rand>"; 0 when the id carries no start time. */
private fun startOf(id: String): Long = id.removePrefix("s").substringBefore('-').toLongOrNull() ?: 0L
