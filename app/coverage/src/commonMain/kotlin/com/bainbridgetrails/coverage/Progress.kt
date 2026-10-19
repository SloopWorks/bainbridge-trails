package com.bainbridgetrails.coverage

enum class TrailStatus { NOT_STARTED, PARTWAY, NEARLY_DONE, COMPLETE }

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
}
