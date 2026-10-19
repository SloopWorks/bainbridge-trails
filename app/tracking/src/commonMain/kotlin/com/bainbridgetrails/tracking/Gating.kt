package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.LocalProjection
import kotlin.math.hypot

data class GateResult(val fixes: List<Fix>, val droppedVehicle: Int, val droppedSteps: Int, val verified: Boolean)

/**
 * Drops fixes that cannot be on-foot travel. Two independent checks, both only ever remove fixes:
 *  1. Activity: a fix is dropped when the latest activity sample at or before it is VEHICLE or BICYCLE with
 *     confidence >= MEDIUM. A sample governs until the next one, capped at [ACTIVITY_GOVERNS_MS] (iOS reports
 *     transitions only, so a single VEHICLE sample may be all there is for a whole drive).
 *  2. Steps: per [STEP_WINDOW_MS] window from the first fix, if the distance moved exceeds
 *     [STEP_MIN_MOVE_M] but steps * [MAX_STRIDE_M] cannot cover it, the window's fixes are dropped.
 *     Cumulative steps at the window edges are interpolated between the nearest samples; after the last sample
 *     the count is held (no new steps: Android reports only on change, and the recorder writes a heartbeat
 *     sample every [STEP_HEARTBEAT_MS]). Skipped when step data is unavailable, when no sample exists at or
 *     before the window start, or when the counter reset (decreased) inside the window.
 * A session is verified only when both sources were available; missing sources never drop fixes.
 */
object Gating {
    const val ACTIVITY_GOVERNS_MS = 600_000L
    const val STEP_WINDOW_MS = 60_000L
    const val STEP_MIN_MOVE_M = 60.0
    const val MAX_STRIDE_M = 2.5

    fun gate(session: RecordedSession, proj: LocalProjection = LocalProjection()): GateResult {
        val sorted = session.fixes.sortedBy { it.timeMs }
        val acts = session.activities.sortedBy { it.timeMs }

        // 1. activity
        var droppedVehicle = 0
        val afterActivity = ArrayList<Fix>(sorted.size)
        for (f in sorted) {
            val a = acts.lastOrNull { it.timeMs <= f.timeMs }
            val governs = a != null && f.timeMs - a.timeMs <= ACTIVITY_GOVERNS_MS
            if (governs && (a.kind == ActivityKind.VEHICLE || a.kind == ActivityKind.BICYCLE) && a.confidence >= Confidence.MEDIUM) droppedVehicle++
            else afterActivity += f
        }

        // 2. steps
        var droppedSteps = 0
        val steps = session.steps.sortedBy { it.timeMs }
        val result: List<Fix> = if (!session.stepsAvailable || steps.isEmpty() || afterActivity.isEmpty()) afterActivity else {
            val start = afterActivity.first().timeMs
            val kept = ArrayList<Fix>(afterActivity.size)
            val windows: List<List<Fix>> = afterActivity.groupBy { (it.timeMs - start) / STEP_WINDOW_MS }.entries.sortedBy { it.key }.map { it.value }
            for (window in windows) {
                val ws = start + ((window.first().timeMs - start) / STEP_WINDOW_MS) * STEP_WINDOW_MS
                val we = ws + STEP_WINDOW_MS
                val covered = steps.first().timeMs <= ws
                var moved = 0.0
                if (covered) {
                    for (i in 1 until window.size) {
                        val a = proj.toXy(window[i - 1].lon, window[i - 1].lat)
                        val b = proj.toXy(window[i].lon, window[i].lat)
                        moved += hypot(b.x - a.x, b.y - a.y)
                    }
                }
                val n = if (covered) cumulativeAt(steps, we) - cumulativeAt(steps, ws) else 0.0
                // a counter reset (cumulative decreases inside the window) means no usable step data
                val reset = covered && steps.zipWithNext().any { (a, b) -> b.timeMs > ws && a.timeMs < we && b.cumulativeSteps < a.cumulativeSteps }
                if (covered && !reset && n >= 0.0 && moved > STEP_MIN_MOVE_M && n * MAX_STRIDE_M < moved) droppedSteps += window.size
                else kept += window
            }
            kept
        }
        return GateResult(result, droppedVehicle, droppedSteps, session.activityAvailable && session.stepsAvailable)
    }

    /** Linear interpolation of cumulative steps; the last value is held after the last sample. [s] sorted, non-empty. */
    private fun cumulativeAt(s: List<StepSample>, t: Long): Double {
        if (t <= s.first().timeMs) return s.first().cumulativeSteps.toDouble()
        if (t >= s.last().timeMs) return s.last().cumulativeSteps.toDouble()
        val hi = s.indexOfFirst { it.timeMs >= t }
        val b = s[hi]
        val a = s[hi - 1]
        if (b.timeMs == t) return b.cumulativeSteps.toDouble()
        val frac = (t - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
        return a.cumulativeSteps + frac * (b.cumulativeSteps - a.cumulativeSteps)
    }
}
