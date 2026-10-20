package com.bainbridgetrails.coverage

enum class TrailStatus { NOT_STARTED, PARTWAY, NEARLY_DONE, COMPLETE }

/** One trail's progress inside a [ProgressSnapshot]. */
data class TrailProgress(val trailId: String, val name: String, val fraction: Double, val status: TrailStatus, val lengthMi: Double)

/**
 * Everything the UI shows about a [CoverageState], computed in one pass by [Progress.snapshot]. Cache it per state
 * instead of calling [Progress.islandFraction] / [Progress.trailsCompleted] (each walks every segment) per frame.
 */
data class ProgressSnapshot(
    /** Dataset trail order. */
    val trails: List<TrailProgress>,
    val islandFraction: Double,
    val trailsCompleted: Int,
    /** Covered metres on included (island-total) segments; shared segments count once. */
    val coveredM: Double,
    /**
     * Segment ids by their **own** covered fraction's status (decisions.md TL-1: one map layer per bucket,
     * `filter = id in literal(ids)`). Only covered buckets are present; untouched segments are the base layer.
     */
    val segmentBuckets: Map<TrailStatus, List<String>> = emptyMap(),
) {
    private val byId: Map<String, TrailProgress> by lazy { trails.associateBy { it.trailId } }

    val trailCount: Int get() = trails.size

    fun trail(id: String): TrailProgress? = byId[id]

    companion object {
        val EMPTY = ProgressSnapshot(emptyList(), 0.0, 0, 0.0)
    }
}

class Progress(
    private val dataset: Dataset,
    private val index: SegmentIndex,
    private val params: CoverageParams,
) {
    private val trailsById: Map<String, TrailMeta> = dataset.trails.associateBy { it.id }

    /** segment id -> trails whose segment_ids contain it, in dataset trail order. */
    private val trailsBySegment: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for (t in dataset.trails) for (s in t.segmentIds.distinct()) getOrPut(s) { ArrayList() } += t.id
    }

    private fun coveredClamped(state: CoverageState, segmentId: String): Double {
        val len = index.segments[segmentId]?.lengthM ?: return 0.0
        return state.coveredM(index, segmentId).coerceIn(0.0, len)
    }

    fun trailFraction(state: CoverageState, trailId: String): Double {
        val trail = trailsById[trailId] ?: return 0.0
        var total = 0.0
        var covered = 0.0
        for (s in trail.segmentIds.distinct()) {
            total += index.segments[s]?.lengthM ?: continue
            covered += coveredClamped(state, s)
        }
        return if (total <= 0.0) 0.0 else (covered / total).coerceIn(0.0, 1.0)
    }

    /** Fraction 0 is NOT_STARTED; callers pass a fraction from [trailFraction]. */
    fun status(fraction: Double): TrailStatus = when {
        fraction <= 0.0 -> TrailStatus.NOT_STARTED
        fraction >= params.completionThreshold -> TrailStatus.COMPLETE
        fraction >= params.nearlyDoneThreshold -> TrailStatus.NEARLY_DONE
        else -> TrailStatus.PARTWAY
    }

    fun islandFraction(state: CoverageState): Double {
        if (dataset.islandTotalM <= 0.0) return 0.0
        var covered = 0.0
        for (s in dataset.segments) if (s.included) covered += coveredClamped(state, s.id)
        return (covered / dataset.islandTotalM).coerceIn(0.0, 1.0)
    }

    fun trailsCompleted(state: CoverageState): Int =
        dataset.trails.count { status(trailFraction(state, it.id)) == TrailStatus.COMPLETE }

    /** Every trail using [segmentId] when that segment has any covered tick; else empty. */
    fun trailsOn(state: CoverageState, segmentId: String): List<String> =
        if (state.covered(segmentId).isEmpty()) emptyList() else trailsBySegment[segmentId].orEmpty()

    /**
     * One pass over segments and trails: per-trail fraction + status, island fraction, completed count, covered metres,
     * segment status buckets. [stickyCompleted] (decisions.md TL-7) are trails completed before: they stay COMPLETE
     * whatever the current coverage, and count toward [ProgressSnapshot.trailsCompleted].
     */
    fun snapshot(state: CoverageState, stickyCompleted: Set<String> = emptySet()): ProgressSnapshot {
        val coveredBySegment = HashMap<String, Double>()
        fun covered(id: String) = coveredBySegment.getOrPut(id) { coveredClamped(state, id) }
        var islandCovered = 0.0
        val buckets = HashMap<TrailStatus, MutableList<String>>()
        for (s in dataset.segments) {
            val c = covered(s.id)
            if (s.included) islandCovered += c
            val len = index.segments[s.id]?.lengthM ?: s.lengthM
            if (c > 0.0 && len > 0.0) buckets.getOrPut(status(c / len)) { ArrayList() } += s.id
        }
        val trails = dataset.trails.map { t ->
            var total = 0.0
            var cov = 0.0
            for (s in t.segmentIds.distinct()) {
                total += index.segments[s]?.lengthM ?: continue
                cov += covered(s)
            }
            val f = if (total <= 0.0) 0.0 else (cov / total).coerceIn(0.0, 1.0)
            TrailProgress(t.id, t.name, f, if (t.id in stickyCompleted) TrailStatus.COMPLETE else status(f), t.displayLengthMi)
        }
        val island = if (dataset.islandTotalM <= 0.0) 0.0 else (islandCovered / dataset.islandTotalM).coerceIn(0.0, 1.0)
        return ProgressSnapshot(trails, island, trails.count { it.status == TrailStatus.COMPLETE }, islandCovered, buckets)
    }
}

