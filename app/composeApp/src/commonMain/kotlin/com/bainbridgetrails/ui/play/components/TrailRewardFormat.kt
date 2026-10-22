package com.bainbridgetrails.ui.play.components

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_unit_feet_a11y
import com.bainbridgetrails.resources.play_unit_km_a11y
import com.bainbridgetrails.resources.play_unit_metres_a11y
import com.bainbridgetrails.resources.play_unit_miles_a11y
import kotlin.math.floor
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.PluralStringResource

/**
 * Display unit of a trails reward value: [abbrev] follows the number ("820 ft"); [spoken] is the plural a11y strings use
 * instead, because TalkBack / VoiceOver do not reliably expand "ft" or "m" (ux § 1).
 */
enum class TrailUnit(val abbrev: String) {
    FEET("ft"), METRES("m"), MILES("mi"), KILOMETRES("km");

    val spoken: PluralStringResource
        get() = when (this) {
            FEET -> Res.plurals.play_unit_feet_a11y
            METRES -> Res.plurals.play_unit_metres_a11y
            MILES -> Res.plurals.play_unit_miles_a11y
            KILOMETRES -> Res.plurals.play_unit_km_a11y
        }
}

/**
 * A formatted trails reward value: [number] ("1,207", "16.1") in [unit]. [text] is what screens show; a11y speaks
 * [number] through the unit's `play_unit_*_a11y` plural with [pluralQuantity] (English: one only for "1").
 */
data class TrailValue(val number: String, val unit: TrailUnit) {
    val text: String get() = "$number ${unit.abbrev}"
    val pluralQuantity: Int get() = if (number == "1") 1 else OTHER

    companion object {
        /** A quantity that selects the English `other` form ("1.0 miles", "0 feet"). */
        const val OTHER = 2
    }
}

/**
 * Display formatting of the trails rewards (docs/play/trails/ux.md § 1). Rules store and pay climb in **metres** and
 * trail miles in **miles**; this converts for the Units setting (miles ⇒ ft + mi, kilometres ⇒ m + km) and never
 * decides anything. So-far values floor, steps and targets round to nearest, and the clamp keeps the shown progress
 * honest: below a step (in its stored unit) it is at most the shown step − 1 display unit, at or past it at least the
 * shown step ("492 of 492 ft" is never an unfinished challenge, "2,460 ft" never sits under an earned 2,461 ft step).
 * The ladders and the mile are the rules' own ([TrailRewards.CLIMB_STEPS_M], [TrailRewards.mileSteps],
 * [TrailRewards.METRES_PER_MILE]); this never re-derives them.
 */
object TrailRewardFormat {
    private const val FEET_PER_METRE = 3.28084
    private const val METRES_PER_MILE = TrailRewards.METRES_PER_MILE
    private const val METRES_PER_KM = 1000.0

    /** Float noise allowance of a floor (a sum that is 1,640.0 ft must not floor to 1,639). */
    private const val EPS = 1e-9

    /** Climb so far: floor, whole ft / m; with [targetM], clamped against that step or target. */
    fun climb(metres: Double, units: Units, targetM: Double? = null): TrailValue {
        val k = climbFactor(units)
        var shown = floorTicks(metres * k)
        if (targetM != null) shown = clamp(shown, climbTicks(targetM, units), decimetres(metres) >= decimetres(targetM))
        return TrailValue(whole(shown), climbUnit(units))
    }

    /**
     * Climb "So far" against a whole ladder ([stepsM], metres; ux § 1, review L2-1 #1): floor, then at least the
     * highest reached step as shown, then at most the next step as shown − 1. So 750 m under its earned 2,461 ft step
     * shows "2,461 ft" (not the floor 2,460) and 499.9 m short of the 1,640 ft step shows "1,639 ft".
     */
    fun climbSoFar(metres: Double, units: Units, stepsM: List<Int> = TrailRewards.CLIMB_STEPS_M): TrailValue {
        val dm = decimetres(metres)
        val reached = stepsM.lastOrNull { dm >= it * 10L }
        val next = stepsM.firstOrNull { dm < it * 10L }
        var shown = floorTicks(metres * climbFactor(units))
        if (reached != null) shown = maxOf(shown, climbTicks(reached.toDouble(), units))
        if (next != null) shown = minOf(shown, (climbTicks(next.toDouble(), units) - 1).coerceAtLeast(0))
        return TrailValue(whole(shown), climbUnit(units))
    }

    /** A climb step or challenge target: nearest, whole ft / m ("820 ft" for 250 m). */
    fun climbStep(metres: Double, units: Units): TrailValue = TrailValue(whole(climbTicks(metres, units)), climbUnit(units))

    /**
     * Challenge climb progress of a target (ux § 7), the "progress" half of "progress of target" (the target is
     * [climbStep]): capped at the target once done ("150 of 150 m climbed"), clamped below it while not ("149 of 150 m").
     */
    fun climbProgress(progressM: Double, targetM: Double, units: Units): TrailValue {
        val done = decimetres(progressM) >= decimetres(targetM)
        return climb(if (done) targetM else progressM, units, targetM)
    }

    /**
     * Trail distance "So far" against the mile ladder [stepsMi] (statute miles; ux § 1, review L2-1 #1): floor to one
     * decimal, then at least the highest reached step as shown, then at most the next step as shown − 0.1. So 10 mi in
     * km shows "16.1 km" (its earned step), and 1,605 m short of the 1 mi step shows "1.5 km".
     */
    fun distanceSoFar(metres: Double, units: Units, stepsMi: List<Int>): TrailValue {
        val reachedSet = TrailRewards.mileSteps(metres / METRES_PER_MILE).toSet()
        val reached = stepsMi.lastOrNull { it in reachedSet }
        val next = stepsMi.firstOrNull { it !in reachedSet }
        var shown = floorTicks(metres / metresPerUnit(units) * 10)
        if (reached != null) shown = maxOf(shown, mileStepTenths(reached, units))
        if (next != null) shown = minOf(shown, (mileStepTenths(next, units) - 1).coerceAtLeast(0))
        return TrailValue(tenths(shown), distanceUnit(units))
    }

    /** A trail-mile step: "10 mi" (whole), or in km nearest to one decimal ("16.1 km"). */
    fun mileStep(miles: Int, units: Units): TrailValue = when (units) {
        Units.MILES -> TrailValue(whole(miles.toLong()), TrailUnit.MILES)
        Units.KILOMETRES -> TrailValue(tenths(mileStepTenths(miles, units)), TrailUnit.KILOMETRES)
    }

    fun climbUnit(units: Units): TrailUnit = if (units == Units.MILES) TrailUnit.FEET else TrailUnit.METRES

    fun distanceUnit(units: Units): TrailUnit = if (units == Units.MILES) TrailUnit.MILES else TrailUnit.KILOMETRES

    private fun climbFactor(units: Units) = if (units == Units.MILES) FEET_PER_METRE else 1.0

    private fun climbTicks(metres: Double, units: Units): Long = (metres * climbFactor(units)).roundToLong()

    private fun metresPerUnit(units: Units) = if (units == Units.MILES) METRES_PER_MILE else METRES_PER_KM

    /** A mile step in tenths of the display unit. */
    private fun mileStepTenths(miles: Int, units: Units): Long = when (units) {
        Units.MILES -> miles * 10L
        Units.KILOMETRES -> (miles * METRES_PER_MILE / METRES_PER_KM * 10).roundToLong()
    }

    /** Climb rules compare whole decimetres (plan D6: 99.9 m = 999 < 1000). */
    private fun decimetres(metres: Double): Long = (metres * 10).roundToLong()

    private fun floorTicks(v: Double): Long = floor(v + EPS).toLong().coerceAtLeast(0)

    private fun clamp(shown: Long, step: Long, reached: Boolean): Long =
        if (reached) maxOf(shown, step) else minOf(shown, (step - 1).coerceAtLeast(0))

    /** Clamped, never wrapped past [Int.MAX_VALUE] (final review P2-3). */
    private fun whole(n: Long): String = PlayFormat.marks(n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

    private fun tenths(n: Long): String = "${whole(n / 10)}.${n % 10}"
}
