package com.bainbridgetrails.ui.play.me

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.play.rules.TrailRewardInput
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.rules.TrailTables
import com.bainbridgetrails.ui.MilestoneState
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.play.components.TrailRewardFormat
import com.bainbridgetrails.ui.play.components.TrailValue

/** A Play park's row in the Trail milestones sheet (docs/play/trails/ux.md § 5.2). */
enum class ParkState { FINISHED, STARTED, NOT_STARTED }

data class TrailParkRow(val park: String, val state: ParkState)

/** A high-ground (W-STEEP) trail's row: [gainDm] the tables' integer decimetres; [earned] it is completed. */
data class TrailHighRow(val trailId: String, val name: String, val gainDm: Int, val earned: Boolean)

/**
 * The unit-free numbers of Me › Trail milestones (ux § 5), computed off composition from the rules' [TrailTables] and
 * [TrailRewardInput] ([TrailMilestonesLogic.model]): [climbDm] Σ gain of completed non-quiet trails; [coveredM]
 * non-quiet trail metres walked; [mileSteps] the W-MILE ladder up to the island's reachable top; [parks] every Play
 * park, alphabetical; [high] every non-quiet trail with gain ≥ [TrailRewards.STEEP_DM], steepest first.
 */
data class TrailMilestonesModel(
    val climbDm: Int,
    val coveredM: Double,
    val mileSteps: List<Int>,
    val parks: List<TrailParkRow>,
    val high: List<TrailHighRow>,
) {
    val climbM: Double get() = climbDm / 10.0
    val parksFinished: Int get() = parks.count { it.state == ParkState.FINISHED }
    val parksStarted: Int get() = parks.count { it.state == ParkState.STARTED }
    val highEarned: Int get() = high.count { it.earned }
}

/** The four ladders of the strip, in its fixed order (ux § 5.1). */
enum class TrailLadder { CLIMB, MILES, PARKS, HIGH }

/**
 * One medallion of the strip or the sheet, formatted for a Units setting. [step]: the ladder step (climb metres, trail
 * miles) or, for parks / high ground, the total. [mark]: the medallion number ("820", "16.1", "19"). [value]: the
 * climb / mile step as shown ("820 ft"), the title's and a11y's subject; null for parks and high ground. [progress]:
 * NEXT only, the "so far / step" pair as shown ("673" to "820"); [fraction] in the stored unit.
 */
data class TrailBadge(
    val ladder: TrailLadder,
    val step: Int,
    val state: MilestoneState,
    val fraction: Double,
    val mark: String,
    val value: TrailValue?,
    val progress: Pair<String, String>?,
) {
    val key: String get() = "${ladder.name}:$step"
}

/**
 * Me › Trail milestones logic (docs/play/trails/ux.md § 5, plan L2-4): pure, no Compose. Every count and ladder comes
 * from the tables and the rules ([TrailRewards]), never a constant (ux § 0.5: 19 parks and 10 trails are today's).
 */
object TrailMilestonesLogic {
    /** Locked steps shown past the next on the sheet's mile ladder (roads `Milestones.ladder` `lockedAhead`). */
    private const val LOCKED_AHEAD = 2

    /**
     * The model, or null while the tables or the input are unknown, which includes roads: its tables are always null
     * (ux § 0.1; `PlayRepository.trailRewardTables`, and MeScreen does not ask on roads). [names]: a trail id's display
     * name (the dataset's), falling back to the id.
     */
    fun model(tables: TrailTables?, input: TrailRewardInput?, names: (String) -> String?): TrailMilestonesModel? {
        if (tables == null || input == null) return null
        val done = input.completed.filter { it in tables.gainDm && it !in tables.quietFeatures }.toSet()
        val touched = input.touchedParks.toSet()
        val parks = tables.parks.sorted().map { p ->
            val members = tables.parkMembers[p].orEmpty()
            val state = when {
                members.isNotEmpty() && members.all { it in done } -> ParkState.FINISHED
                p in touched || members.any { it in done } -> ParkState.STARTED
                else -> ParkState.NOT_STARTED
            }
            TrailParkRow(p, state)
        }
        val high = tables.gainDm.entries
            .filter { (t, dm) -> t !in tables.quietFeatures && dm >= TrailRewards.STEEP_DM }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (t, dm) -> TrailHighRow(t, names(t) ?: t, dm, t in done) }
        val islandM = tables.segmentLengthM.entries.sumOf { (id, m) -> if (id in tables.quietSegments) 0.0 else m }
        return TrailMilestonesModel(
            climbDm = done.sumOf { tables.gainDm.getValue(it) },
            coveredM = input.nonQuietCoveredM,
            // W-MILE's own ladder up to the island's reachable top; an island shorter than a mile still shows the
            // 1 mi step (the strip is always four badges).
            mileSteps = TrailRewards.mileSteps(islandM / TrailRewards.METRES_PER_MILE).ifEmpty { listOf(1) },
            parks = parks,
            high = high,
        )
    }

    /** The strip (ux § 5.1): always four badges, Climb, Trail miles, Parks finished, High-ground trails. */
    fun strip(m: TrailMilestonesModel, units: Units): List<TrailBadge> {
        val climb = climbLadder(m, units)
        val miles = mileBadges(m, units)
        return listOf(
            climb.firstOrNull { it.state == MilestoneState.NEXT } ?: climb.last(),
            miles.firstOrNull { it.state == MilestoneState.NEXT } ?: miles.last(),
            countBadge(TrailLadder.PARKS, m.parksFinished, m.parks.size),
            countBadge(TrailLadder.HIGH, m.highEarned, m.high.size),
        )
    }

    /** The sheet's climb section: all [TrailRewards.CLIMB_STEPS_M] (earned, the next, the rest locked). */
    fun climbLadder(m: TrailMilestonesModel, units: Units): List<TrailBadge> {
        val soFar = climbSoFar(m, units).number
        var nextSeen = false
        return TrailRewards.CLIMB_STEPS_M.map { step ->
            val value = TrailRewardFormat.climbStep(step.toDouble(), units)
            val state = when {
                m.climbDm >= step * 10 -> MilestoneState.EARNED
                !nextSeen -> MilestoneState.NEXT.also { nextSeen = true }
                else -> MilestoneState.LOCKED
            }
            badge(TrailLadder.CLIMB, step, state, m.climbDm / (step * 10.0), value, soFar)
        }
    }

    /** The sheet's trail-miles section: every earned step, the next, then [LOCKED_AHEAD] locked, capped at the top. */
    fun mileLadder(m: TrailMilestonesModel, units: Units): List<TrailBadge> {
        val all = mileBadges(m, units)
        val next = all.indexOfFirst { it.state != MilestoneState.EARNED }
        return if (next < 0) all else all.take(next + 1 + LOCKED_AHEAD)
    }

    /** "So far: 1,207 ft": floor, clamped against the whole climb ladder (TrailRewardFormat.climbSoFar). */
    fun climbSoFar(m: TrailMilestonesModel, units: Units): TrailValue =
        TrailRewardFormat.climbSoFar(m.climbM, units)

    /** "So far: 41.3 mi": floor to one decimal, clamped against the reachable mile ladder. */
    fun milesSoFar(m: TrailMilestonesModel, units: Units): TrailValue =
        TrailRewardFormat.distanceSoFar(m.coveredM, units, m.mileSteps)

    /** A high-ground row's climb (ux § 1: nearest, whole ft / m; "302 ft" for Peter's Trail's 92.1 m). */
    fun highClimb(row: TrailHighRow, units: Units): TrailValue = TrailRewardFormat.climbStep(row.gainDm / 10.0, units)

    private fun mileBadges(m: TrailMilestonesModel, units: Units): List<TrailBadge> {
        val reached = TrailRewards.mileSteps(m.coveredM / TrailRewards.METRES_PER_MILE).toSet()
        val soFar = milesSoFar(m, units).number
        var nextSeen = false
        return m.mileSteps.map { step ->
            val state = when {
                step in reached -> MilestoneState.EARNED
                !nextSeen -> MilestoneState.NEXT.also { nextSeen = true }
                else -> MilestoneState.LOCKED
            }
            badge(TrailLadder.MILES, step, state, m.coveredM / (step * TrailRewards.METRES_PER_MILE), TrailRewardFormat.mileStep(step, units), soFar)
        }
    }

    private fun badge(ladder: TrailLadder, step: Int, state: MilestoneState, fraction: Double, value: TrailValue, soFar: String) = TrailBadge(
        ladder = ladder,
        step = step,
        state = state,
        fraction = if (state == MilestoneState.EARNED) 1.0 else fraction.coerceIn(0.0, 1.0),
        mark = value.number,
        value = value,
        progress = if (state == MilestoneState.NEXT) soFar to value.number else null,
    )

    private fun countBadge(ladder: TrailLadder, n: Int, total: Int): TrailBadge {
        val earned = total > 0 && n >= total
        return TrailBadge(
            ladder = ladder,
            step = total,
            state = if (earned) MilestoneState.EARNED else MilestoneState.NEXT,
            fraction = if (total <= 0) 0.0 else (n.toDouble() / total).coerceIn(0.0, 1.0),
            mark = PlayFormat.marks(total),
            value = null,
            progress = if (earned) null else PlayFormat.marks(n) to PlayFormat.marks(total),
        )
    }
}
