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
    /** Start event wall clock (epoch ms). */
    val startedAt: Long = 0,
    /** Stop event wall clock (or last fix when merged before the Stop). */
    val endedAt: Long = 0,
    /** Gated, filtered GPS path length. */
    val distanceM: Double = 0.0,
    /** Distinct matched ticks of this walk on any mapped segment × tick length. */
    val matchedM: Double = 0.0,
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

    /** Sessions whose results sheet was already shown (walk index). Blocking file read: call off the main thread. */
    fun shownIds(): Set<String> = walks?.shownIds().orEmpty()

    /** Records that [sessionId]'s results sheet was shown. Blocking file write: call off the main thread. */
    fun markShown(sessionId: String) {
        walks?.markShown(sessionId)
    }

    suspend fun finalize(sessionId: String): SessionSummary = withContext(Dispatchers.Default) { mutex.withLock {
        onWork()
        val session = store.load(sessionId)
        val gate = Gating.gate(session, index.projection)
        val before = coverage.load()
        val sticky = coverage.completedIds()
        val walked = index.coverFixes(gate.fixes)
        val after = before.union(walked)

        val rose = mutableListOf<TrailResult>()
        val completed = mutableListOf<String>()
        val nowComplete = HashSet<String>()
        for (t in dataset.trails) {
            val b = progress.trailFraction(before, t.id)
            val a = progress.trailFraction(after, t.id)
            if (progress.status(a) == TrailStatus.COMPLETE) nowComplete += t.id
            if (a > b) {
                val status = progress.status(a)
                rose += TrailResult(t.id, t.name, b, a, status)
                // TL-7: celebrate only the walk that first completes a trail; sticky completions never repeat.
                if (status == TrailStatus.COMPLETE && progress.status(b) != TrailStatus.COMPLETE && t.id !in sticky) completed += t.id
            }
        }
        CoverageStore.writeLock.withLock {
            coverage.merge(sessionId, after, gate.verified, finalized = session.finished, completed = nowComplete)
        }
        val sorted = rose.sortedByDescending { it.after }
        val startedAt = session.startMs ?: session.fixes.minOfOrNull { it.timeMs } ?: startOf(sessionId)
        val endedAt = session.stopMs ?: session.fixes.maxOfOrNull { it.timeMs } ?: startedAt
        val distanceM = WalkIndex.distanceM(gate.fixes, index.projection, params)
        val matchedM = walked.totalCoveredM(index)
        val islandBefore = progress.islandFraction(before)
        val islandAfter = progress.islandFraction(after)
        walks?.upsert(
            WalkRecord(
                sessionId = sessionId,
                startedAt = startedAt,
                endedAt = endedAt,
                distanceM = distanceM,
                matchedM = matchedM,
                verified = gate.verified,
                approximateLocation = session.approximateLocation,
                islandBefore = islandBefore,
                islandAfter = islandAfter,
                trails = sorted.map { WalkTrail(it.trailId, it.before, it.after) },
                newlyCompleted = completed,
            ),
        )
        SessionSummary(
            sessionId = sessionId,
            islandBefore = islandBefore,
            islandAfter = islandAfter,
            trails = sorted,
            verified = gate.verified,
            droppedVehicle = gate.droppedVehicle,
            droppedSteps = gate.droppedSteps,
            newlyCompleted = completed,
            approximateLocation = session.approximateLocation,
            startedAt = startedAt,
            endedAt = endedAt,
            distanceM = distanceM,
            matchedM = matchedM,
        )
    } }
}

/** Session ids are "s<startMs>-<rand>"; 0 when the id carries no start time. */
private fun startOf(id: String): Long = id.removePrefix("s").substringBefore('-').toLongOrNull() ?: 0L
