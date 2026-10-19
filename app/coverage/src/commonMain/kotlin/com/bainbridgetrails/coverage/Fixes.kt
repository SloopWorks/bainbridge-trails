package com.bainbridgetrails.coverage

import kotlin.math.hypot

data class Fix(
    val timeMs: Long,
    val lon: Double,
    val lat: Double,
    val accuracyM: Double? = null,
    val isMock: Boolean = false,
)

private const val MIN_SPEED_BASIS_S = 10.0

/**
 * Continuous runs of accepted fixes, in time order. Lines are drawn only within a piece.
 *
 * Rules, in order: (1) sort, drop duplicate timestamps; (2) drop mock / inaccurate fixes; (2b) drop spikes;
 * (3) break pieces on time gap > maxGapS or jump > maxJumpM; (4) speed check; (5) drop pieces < 2 fixes.
 *
 * Spike filter (rule 2b, ruling R11): fix i, other than the first or last of the sequence left after rule 2, is
 * dropped when d(i-1, i) > spikeM and d(i, i+1) > spikeM but d(i-1, i+1) < spikeM / 2, i.e. it leaves and returns
 * to where the track was. Neighbours are those after rule 2, all judged in one pass (a drop never changes another
 * fix's neighbours), so only single-fix spikes are caught; two consecutive outliers pass.
 *
 * Speed check (rule 4): the reference fix j is chosen from the whole sequence left after rules 1-2b,
 * including fixes the speed check itself dropped, not just the current piece. j = latest fix with
 * t_i - t_j >= speedWindowS; if none, the sequence's first fix, provided >= 10 s elapsed. Fixes within
 * 10 s of the sequence start are accepted unchecked (start-of-recording allowance only). If speed j->i
 * exceeds maxSpeedMps, fix i is dropped and the piece breaks. Breaks never reset the reference.
 */
fun filterFixes(fixes: List<Fix>, params: CoverageParams, proj: LocalProjection): List<List<Xy>> {
    val accepted = fixes.sortedBy { it.timeMs }
        .distinctBy { it.timeMs }
        .filter { !it.isMock && (it.accuracyM ?: 0.0) <= params.maxAccuracyM }
    val accXy = accepted.map { proj.toXy(it.lon, it.lat) }
    // rule 2b: single-fix spikes, judged against the original neighbours in one pass (no cascade)
    val keep = BooleanArray(accepted.size) { i ->
        if (i == 0 || i == accepted.size - 1) return@BooleanArray true
        val a = accXy[i - 1]
        val p = accXy[i]
        val b = accXy[i + 1]
        !(hypot(p.x - a.x, p.y - a.y) > params.spikeM && hypot(b.x - p.x, b.y - p.y) > params.spikeM &&
            hypot(b.x - a.x, b.y - a.y) < params.spikeM / 2)
    }
    val seq = accepted.filterIndexed { i, _ -> keep[i] }
    val xy = accXy.filterIndexed { i, _ -> keep[i] }

    val pieces = ArrayList<List<Xy>>()
    var pts = ArrayList<Xy>()
    fun flush() {
        if (pts.size >= 2) pieces.add(pts)
        pts = ArrayList()
    }

    for (i in seq.indices) {
        val t = seq[i].timeMs
        val p = xy[i]
        if (i > 0) {
            val gapS = (t - seq[i - 1].timeMs) / 1000.0
            val q = xy[i - 1]
            if (gapS > params.maxGapS || hypot(p.x - q.x, p.y - q.y) > params.maxJumpM) flush()
        }
        var j = -1
        for (k in i - 1 downTo 0) {
            if ((t - seq[k].timeMs) / 1000.0 >= params.speedWindowS) { j = k; break }
        }
        if (j < 0 && i > 0 && (t - seq[0].timeMs) / 1000.0 >= MIN_SPEED_BASIS_S) j = 0
        if (j >= 0) {
            val dt = (t - seq[j].timeMs) / 1000.0
            if (hypot(p.x - xy[j].x, p.y - xy[j].y) / dt > params.maxSpeedMps) {
                flush()
                continue
            }
        }
        pts.add(p)
    }
    flush()
    return pieces
}
