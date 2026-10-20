package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.LocalProjection
import kotlin.math.hypot

data class GateResult(val fixes: List<Fix>, val droppedVehicle: Int, val droppedSteps: Int, val verified: Boolean)

/**
 * Drops fixes that cannot be on-foot travel. Two independent checks, both only ever remove fixes:
 *  1. Activity: a fix is dropped when the latest activity sample at or before it is VEHICLE or BICYCLE with
 *     confidence >= MEDIUM. A sample governs until the next one, capped at [ACTIVITY_GOVERNS_MS] (iOS reports
 *     transitions only, so a single VEHICLE sample may be all there is for a whole drive).
 *  2. Steps: fixes are grouped into [STEP_WINDOW_MS] windows from the first fix. Each window [ws, we) is judged
 *     by an evidence span [ws - STEP_EVIDENCE_PAD_MS, we + STEP_EVIDENCE_PAD_MS], clamped to the session's fix
 *     time range. The window's fixes are dropped only if the distance moved over the span exceeds
 *     3 * [STEP_MIN_MOVE_M] and the span's steps * [MAX_STRIDE_M] cannot cover it.
 *     Why a span and not the window alone: step counters stall for about a minute while the user keeps walking
 *     (observed on a Pixel 10 Pro: 2 steps over 71 m, then a normal 109 steps the next minute; Activity
 *     Recognition independently reported STILL (HIGH) for ~40 s, then UNKNOWN, so the phone sensed no stepping
 *     motion, likely held steady or carried differently. Not a background restriction: standby bucket ACTIVE,
 *     sensor still registered, GPS at 1 Hz throughout), whereas a bike
 *     or car ride lasts minutes. Rides shorter than the span are still caught by the activity gate (1.) and by
 *     the speed filter in coverage.
 *     Moved distance is the path length over the span's fixes, except that a step between consecutive fixes adds
 *     0 when the activity gate removed any fix between them, or when they are more than [MAX_GAP_MS] apart (the
 *     coverage piece-break gap): a dropped ride or a GPS gap is not walking that steps must explain.
 *     Cumulative steps at the span edges are interpolated between the nearest samples; after the last sample
 *     the count is held (no new steps: Android reports only on change, and the recorder writes a heartbeat
 *     sample every [STEP_HEARTBEAT_MS]). Skipped when step data is unavailable, when no sample exists at or
 *     before the span start, or when the counter reset (decreased) inside the span. Because spans overlap, a
 *     reset also suspends the step check for the neighbouring windows whose span reaches it (up to ±1 window).
 * A session is verified only when both sources were available; missing sources never drop fixes.
 */
object Gating {
    const val ACTIVITY_GOVERNS_MS = 600_000L
    const val STEP_WINDOW_MS = 60_000L
    const val STEP_MIN_MOVE_M = 60.0
    const val MAX_STRIDE_M = 2.5
    const val STEP_EVIDENCE_PAD_MS = 60_000L

    /** Distance an evidence span must exceed before missing steps can drop its window. */
    private const val SPAN_MIN_MOVE_M = 3 * STEP_MIN_MOVE_M

    /** Fix gap beyond which no distance is counted between two fixes; matches the coverage piece break. */
    val MAX_GAP_MS: Long = (CoverageParams().maxGapS * 1000).toLong()

    fun gate(session: RecordedSession, proj: LocalProjection = LocalProjection()): GateResult {
        val sorted = session.fixes.sortedBy { it.timeMs }
        val acts = session.activities.sortedBy { it.timeMs }

        // 1. activity
        var droppedVehicle = 0
        val afterActivity = ArrayList<Fix>(sorted.size)
        // breakBefore[i]: the activity gate dropped a fix between kept fix i-1 and kept fix i
        val breakBefore = ArrayList<Boolean>(sorted.size)
        var droppedSinceKept = false
        for (f in sorted) {
            val a = acts.lastOrNull { it.timeMs <= f.timeMs }
            val governs = a != null && f.timeMs - a.timeMs <= ACTIVITY_GOVERNS_MS
            if (governs && (a.kind == ActivityKind.VEHICLE || a.kind == ActivityKind.BICYCLE) && a.confidence >= Confidence.MEDIUM) {
                droppedVehicle++
                droppedSinceKept = true
            } else {
                afterActivity += f
                breakBefore += droppedSinceKept
                droppedSinceKept = false
            }
        }

        // 2. steps
        var droppedSteps = 0
        val steps = session.steps.sortedBy { it.timeMs }
        val result: List<Fix> = if (!session.stepsAvailable || steps.isEmpty() || afterActivity.isEmpty()) afterActivity else {
            val start = afterActivity.first().timeMs
            val end = afterActivity.last().timeMs
            val times = LongArray(afterActivity.size) { afterActivity[it].timeMs }
            // path length from the first fix to fix i; no distance across activity-dropped fixes or GPS gaps
            val path = DoubleArray(afterActivity.size)
            for (i in 1 until afterActivity.size) {
                val counts = !breakBefore[i] && times[i] - times[i - 1] <= MAX_GAP_MS
                val a = proj.toXy(afterActivity[i - 1].lon, afterActivity[i - 1].lat)
                val b = proj.toXy(afterActivity[i].lon, afterActivity[i].lat)
                path[i] = path[i - 1] + if (counts) hypot(b.x - a.x, b.y - a.y) else 0.0
            }
            val kept = ArrayList<Fix>(afterActivity.size)
            val windows: List<List<Fix>> = afterActivity.groupBy { (it.timeMs - start) / STEP_WINDOW_MS }.entries.sortedBy { it.key }.map { it.value }
            for (window in windows) {
                val ws = start + ((window.first().timeMs - start) / STEP_WINDOW_MS) * STEP_WINDOW_MS
                val we = ws + STEP_WINDOW_MS
                val ss = maxOf(ws - STEP_EVIDENCE_PAD_MS, start)
                val se = minOf(we + STEP_EVIDENCE_PAD_MS, end)
                val covered = steps.first().timeMs <= ss
                // fixes with time in [ss, se]: first index >= ss, last index <= se
                val i0 = times.indexOfFirst { it >= ss }
                val i1 = times.indexOfLast { it <= se }
                val moved = if (i1 > i0) path[i1] - path[i0] else 0.0
                val n = if (covered) cumulativeAt(steps, se) - cumulativeAt(steps, ss) else 0.0
                // a counter reset (cumulative decreases inside the span) means no usable step data
                val reset = covered && steps.zipWithNext().any { (a, b) -> b.timeMs > ss && a.timeMs < se && b.cumulativeSteps < a.cumulativeSteps }
                if (covered && !reset && n >= 0.0 && moved > SPAN_MIN_MOVE_M && n * MAX_STRIDE_M < moved) droppedSteps += window.size
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
