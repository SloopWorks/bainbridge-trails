package com.bainbridgetrails.coverage

/**
 * How progress is counted for a goal (docs/roads/product.md §5.1, RP-18). Trails: every trail counts, a trail's fraction
 * is over all its segments, every matched metre is "trail miles". Roads ([countedOnly]): only `counted` roads are
 * completable and in the denominator, a road's fraction is over its counted (`included`) segments, and walked distance
 * counts only the goal's own segments ([matchedSegmentPrefix]; the trail competitors appended to the roads data, RD-2b,
 * are excluded).
 */
data class GoalRules(
    val countedOnly: Boolean,
    /** Segment id prefix of the goal's own segments for "miles on this phone"; null = every matched segment. */
    val matchedSegmentPrefix: String?,
) {
    /** Distinct matched metres of [state] on this goal's segments (a walk's `matchedM`). */
    fun matchedM(state: CoverageState, index: SegmentIndex): Double {
        val prefix = matchedSegmentPrefix ?: return state.totalCoveredM(index)
        return state.totalCoveredM(index) { it.startsWith(prefix) }
    }

    companion object {
        val TRAILS = GoalRules(countedOnly = false, matchedSegmentPrefix = null)
        val ROADS = GoalRules(countedOnly = true, matchedSegmentPrefix = "rseg-")
    }
}
