package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.Band
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Item
import kotlin.math.floor
import kotlin.math.max

/**
 * Walk-derived marks of a whole coverage snapshot (product §3.2, §3.6.4): W-COV, W-DONE and W-SQUARE. The same formula
 * gives the opt-in starting balance and, as a difference of two snapshots, a walk's own marks.
 */
data class WalkDerived(val coverage: Int, val complete: Int, val squares: Int) {
    val total: Int get() = coverage + complete + squares
}

/**
 * The marks rules (product §3.2–§3.3, PD-2), pure and deterministic. Mirrors `backend/src/play/marks.ts`; the server
 * derives every mark it credits, so a value here is what the phone shows until the server answers.
 */
object Marks {
    /** W-SQUARE: first non-quiet coverage in a chart square. */
    const val SQUARE = 25

    /** W-DAY: first walk of the island day with ≥ [DAY_MIN_DURATION_MS] and ≥ [DAY_MIN_MATCHED_M] matched. */
    const val DAY = 15
    const val DAY_MIN_DURATION_MS = 10 * 60_000L
    const val DAY_MIN_MATCHED_M = 500.0

    /** W-WEEK ladder: 40, +10 per consecutive kept week before it, at most 100. */
    const val WEEK_BASE = 40
    const val WEEK_STEP = 10
    const val WEEK_MAX = 100

    /** F-RIDDLE per star; each hint −25 %, floor 50 %, at most [MAX_HINTS] hints. */
    const val RIDDLE_STAR = 40
    const val MAX_HINTS = 2

    /** W-COV fixed rate: metres per mark (trails 10, roads 25). */
    fun covRateM(goal: Goal): Int = if (goal == Goal.trails) 10 else 25

    /** W-DONE per completed trail (100) / road (25). */
    fun doneMarks(goal: Goal): Int = if (goal == Goal.trails) 100 else 25

    /** W-COV, cumulative: `floor(nonQuietCoveredM / rate)`; never a share of the island total. */
    fun coverage(goal: Goal, nonQuietCoveredM: Double): Int =
        if (nonQuietCoveredM <= 0.0) 0 else floor(nonQuietCoveredM / covRateM(goal)).toInt()

    /** A walk's W-COV: the cumulative value after minus before, never negative (monotone, PD-2). */
    fun coverageDelta(goal: Goal, beforeM: Double, afterM: Double): Int =
        max(0, coverage(goal, afterM) - coverage(goal, beforeM))

    /**
     * W-COV + W-DONE + W-SQUARE of a snapshot: [nonQuietCoveredM] counted metres outside quiet trails,
     * [completedNonQuiet] completed counted features that are not quiet, [squares] chart squares with non-quiet coverage.
     */
    fun walkDerived(goal: Goal, nonQuietCoveredM: Double, completedNonQuiet: Int, squares: Int): WalkDerived =
        WalkDerived(coverage(goal, nonQuietCoveredM), completedNonQuiet * doneMarks(goal), squares * SQUARE)

    /** W-DAY eligibility of one walk (the caller pays the first eligible walk of the island day only). */
    fun isWalkingDay(durationMs: Long, matchedM: Double): Boolean =
        durationMs >= DAY_MIN_DURATION_MS && matchedM >= DAY_MIN_MATCHED_M

    /** W-WEEK for a kept week after [consecutiveBefore] kept weeks in a row (server-derived; shown locally). */
    fun keptWeek(consecutiveBefore: Int): Int = minOf(WEEK_MAX, WEEK_BASE + WEEK_STEP * max(0, consecutiveBefore))

    /**
     * F-INK: the highest bucket whose `pct` the walked [share] (0..1) of a stretch reaches, or null below the lowest
     * (60 %). Buckets are the pack's `bands` (60 → 60, 80 → 70, 95 → 80).
     */
    fun inkBand(bands: List<Band>, share: Double): Band? =
        bands.filter { share * 100.0 + EPS >= it.pct }.maxByOrNull { it.pct }

    /** F-RIDDLE: `floor(stars × 40 × max(0.5, 1 − 0.25 × hints))`, hints clamped to 0..2. */
    fun riddle(stars: Int, hints: Int): Int {
        val h = hints.coerceIn(0, MAX_HINTS)
        return floor(max(0, stars) * RIDDLE_STAR * max(0.5, 1.0 - 0.25 * h)).toInt()
    }

    /** F-EGG / F-LORE / C-SOLO: the item's fixed `marks`; remembrance pays 0, always (product §4.6.3). */
    fun found(item: Item): Int = if (item.lore == REMEMBRANCE) 0 else max(0, item.marks)

    const val REMEMBRANCE = "remembrance"
    private const val EPS = 1e-9
}
