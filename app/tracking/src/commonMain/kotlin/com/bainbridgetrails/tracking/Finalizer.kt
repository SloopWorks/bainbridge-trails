package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailStatus
import kotlinx.coroutines.Dispatchers
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
) {
    private val progress = Progress(dataset, index, params)

    suspend fun finalize(sessionId: String): SessionSummary = withContext(Dispatchers.Default) {
        onWork()
        val session = store.load(sessionId)
        val gate = Gating.gate(session)
        val before = coverage.load()
        val after = before.union(index.coverFixes(gate.fixes))
        coverage.merge(sessionId, after, gate.verified)

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
        SessionSummary(
            sessionId = sessionId,
            islandBefore = progress.islandFraction(before),
            islandAfter = progress.islandFraction(after),
            trails = rose.sortedByDescending { it.after },
            verified = gate.verified,
            droppedVehicle = gate.droppedVehicle,
            droppedSteps = gate.droppedSteps,
            newlyCompleted = completed,
        )
    }
}
