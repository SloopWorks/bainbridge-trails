package com.bainbridgetrails.ui

import com.bainbridgetrails.data.Units

/**
 * Roads milestones (docs/roads/product.md §5.3, RP-8): derived from a walk's before/after numbers or the current
 * snapshot, never stored. Distances are whole units of the user's setting (mi or km).
 */
sealed interface Milestone {
    /** Priority for "which one leads" (product: island > type > roads > distance). Higher wins. */
    val rank: Int

    /** [value] miles/km of counted road walked (ladder 1, 5, 10, 15, …). */
    data class Distance(val value: Int, val units: Units) : Milestone {
        override val rank: Int get() = 1
    }

    /** [count] roads complete (ladder 1, 10, 25, 50, 100, 150, … 400). */
    data class Roads(val count: Int) : Milestone {
        override val rank: Int get() = 2
    }

    /** Every counted road of [category] complete. */
    data class TypeComplete(val category: String) : Milestone {
        override val rank: Int get() = 3
    }

    data object Island : Milestone {
        override val rank: Int get() = 4
    }
}

/** A milestone with how far along it is (Me strip / Milestones screen). */
data class MilestoneProgress(val milestone: Milestone, val state: MilestoneState, val current: Double, val target: Double) {
    val fraction: Double get() = if (target <= 0) 0.0 else (current / target).coerceIn(0.0, 1.0)
}

enum class MilestoneState { EARNED, NEXT, LOCKED }

/** What the milestone math needs to know about a finished walk. */
data class WalkMilestoneInput(
    val islandBefore: Double,
    val islandAfter: Double,
    val islandTotalM: Double,
    val units: Units,
    /** Counted roads this walk completed. */
    val newlyCompleted: List<String>,
    /** Counted roads complete after the walk. */
    val completedAfter: Int,
    val categoryOf: (String) -> String?,
    /** After the walk: complete / counted roads per category. */
    val completedByCategory: Map<String, Int>,
    val countedByCategory: Map<String, Int>,
    val islandReached: Boolean = false,
)

object Milestones {
    /** The categories with an "every road of a type" milestone, heaviest first. */
    val TypeCategories = listOf("state_route", "arterial", "collector", "residential")

    val RoadsLadder = listOf(1, 10, 25, 50, 100, 150, 200, 250, 300, 350, 400)

    private const val METRES_PER_MILE = 1609.344

    fun unitsOf(metres: Double, units: Units): Double = when (units) {
        Units.MILES -> metres / METRES_PER_MILE
        Units.KILOMETRES -> metres / 1000.0
    }

    /** Distance ladder: 1, then every 5 (5, 10, 15, …). */
    fun distanceStep(i: Int): Int = if (i == 0) 1 else 5 * i

    /** Highest distance step ≤ [value], or null below 1. */
    fun distanceReached(value: Double): Int? = when {
        value < 1.0 - EPS -> null
        value < 5.0 - EPS -> 1
        else -> 5 * kotlin.math.floor((value + EPS) / 5).toInt()
    }

    /** The next distance step above [value]. */
    fun nextDistance(value: Double): Int = when {
        value < 1.0 - EPS -> 1
        else -> 5 * (kotlin.math.floor((value + EPS) / 5).toInt() + 1)
    }

    fun roadsReached(count: Int): Int? = RoadsLadder.lastOrNull { it <= count }

    /** Next roads step above [count] that the dataset can still reach ([counted] roads), or null. */
    fun nextRoads(count: Int, counted: Int): Int? = RoadsLadder.firstOrNull { it > count && it <= counted }

    /** Milestones this walk crossed, highest [Milestone.rank] first (product.md §5.3; M-distance: only the highest). */
    fun crossed(w: WalkMilestoneInput): List<Milestone> {
        val out = ArrayList<Milestone>()
        if (w.islandReached) out += Milestone.Island
        for (cat in TypeCategories) {
            val total = w.countedByCategory[cat] ?: 0
            if (total > 0 && w.completedByCategory[cat] == total && w.newlyCompleted.any { w.categoryOf(it) == cat }) {
                out += Milestone.TypeComplete(cat)
            }
        }
        val before = w.completedAfter - w.newlyCompleted.size
        RoadsLadder.lastOrNull { it in (before + 1)..w.completedAfter }?.let { out += Milestone.Roads(it) }
        val dBefore = unitsOf(w.islandBefore * w.islandTotalM, w.units)
        val dAfter = unitsOf(w.islandAfter * w.islandTotalM, w.units)
        val reachedAfter = distanceReached(dAfter)
        if (reachedAfter != null && reachedAfter != distanceReached(dBefore)) out += Milestone.Distance(reachedAfter, w.units)
        return out.sortedByDescending { it.rank }
    }

    /**
     * Every milestone with its state for the current numbers: distance and roads ladders up to the next step
     * (earned, then next, then the following steps locked up to [lockedAhead]) and the four type milestones.
     */
    fun ladder(
        coveredM: Double,
        units: Units,
        completed: Int,
        counted: Int,
        completedByCategory: Map<String, Int>,
        countedByCategory: Map<String, Int>,
        lockedAhead: Int = 2,
    ): Map<String, List<MilestoneProgress>> {
        val d = unitsOf(coveredM, units)
        val distances = buildList {
            var i = 0
            var nextSeen = 0
            while (true) {
                val step = distanceStep(i++)
                val state = when {
                    step <= d + EPS -> MilestoneState.EARNED
                    nextSeen++ == 0 -> MilestoneState.NEXT
                    else -> MilestoneState.LOCKED
                }
                add(MilestoneProgress(Milestone.Distance(step, units), state, d, step.toDouble()))
                if (state != MilestoneState.EARNED && nextSeen > lockedAhead) break
            }
        }
        val roads = buildList {
            var nextSeen = 0
            for (step in RoadsLadder) {
                if (step > counted) break
                val state = when {
                    step <= completed -> MilestoneState.EARNED
                    nextSeen++ == 0 -> MilestoneState.NEXT
                    else -> MilestoneState.LOCKED
                }
                add(MilestoneProgress(Milestone.Roads(step), state, completed.toDouble(), step.toDouble()))
            }
        }
        val types = TypeCategories.mapNotNull { cat ->
            val total = countedByCategory[cat] ?: return@mapNotNull null
            if (total == 0) return@mapNotNull null
            val done = completedByCategory[cat] ?: 0
            MilestoneProgress(Milestone.TypeComplete(cat), if (done >= total) MilestoneState.EARNED else MilestoneState.LOCKED, done.toDouble(), total.toDouble())
        }
        return mapOf(LADDER_DISTANCE to distances, LADDER_ROADS to roads, LADDER_TYPES to types)
    }

    /**
     * Me strip (docs/roads/ux.md §5.3): last earned (max 2, highest of each ladder, types first) then next 2 (the next
     * distance step and the next roads step).
     */
    fun strip(ladder: Map<String, List<MilestoneProgress>>): List<MilestoneProgress> {
        val all = ladder.values.flatten()
        val earned = all.filter { it.state == MilestoneState.EARNED }
        val lastEarned = listOfNotNull(
            earned.filter { it.milestone is Milestone.TypeComplete }.lastOrNull(),
            earned.filter { it.milestone is Milestone.Roads }.lastOrNull(),
            earned.filter { it.milestone is Milestone.Distance }.lastOrNull(),
        ).take(2)
        val next = listOfNotNull(
            ladder[LADDER_DISTANCE]?.firstOrNull { it.state == MilestoneState.NEXT },
            ladder[LADDER_ROADS]?.firstOrNull { it.state == MilestoneState.NEXT },
        )
        return lastEarned + next
    }

    const val LADDER_DISTANCE = "distance"
    const val LADDER_ROADS = "roads"
    const val LADDER_TYPES = "types"

    private const val EPS = 1e-9
}
