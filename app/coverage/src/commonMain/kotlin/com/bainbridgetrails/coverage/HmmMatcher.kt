package com.bainbridgetrails.coverage

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Connectivity-aware map matching (Newson & Krumm 2009) with an off-network state, per filtered piece.
 *
 * - Stationary: a piece whose extent (bounding-box diagonal) is < headingBaselineM credits nothing (Spike 3's
 *   "no >= 15 m baseline, no credit").
 * - Observations: the piece resampled every hmmStepM of path length, first and last points included.
 * - States per observation: OFF (index 0), then the nearest tick of each segment within bufferM, up to
 *   maxCandidates segments, ordered by (distance, slot).
 * - Log emission: -d^2 / (2 sigma^2); OFF = -offNetworkM^2 / (2 sigma^2).
 * - Log transition u -> v: -|route(u, v) - g| / beta, g = straight distance between the observations, route from
 *   [TickGraph.routesFrom] bounded at 2g + 50 (no route = impossible); OFF -> OFF = 0; to or from OFF = -offSwitchPenalty.
 * - Viterbi in log space; ties go to the lower state index (so OFF wins exact ties).
 * - Credit: every on-network state's own tick, plus every tick on the shortest route between consecutive on-network
 *   states (reconstructed from the Dijkstra predecessors).
 */
class HmmMatcher(
    private val index: SegmentIndex,
    private val graph: TickGraph,
    private val params: CoverageParams,
) {
    private val pts: List<List<Xy>> = index.tickPoints
    private val twoSigma2 = 2 * params.emissionSigmaM * params.emissionSigmaM
    private val offEmission = -params.offNetworkM * params.offNetworkM / twoSigma2

    fun cover(pieces: List<List<Xy>>): CoverageState {
        val bits = HashMap<Int, LongArray>()
        fun mark(node: Long) {
            val s = (node ushr 32).toInt()
            val t = node.toInt()
            val w = bits.getOrPut(s) { LongArray((pts[s].size + 63) / 64) }
            w[t / 64] = w[t / 64] or (1L shl (t % 64))
        }
        for (piece in pieces) {
            if (piece.size < 2 || extent(piece) < params.headingBaselineM) continue
            matchPiece(observations(piece), ::mark)
        }
        return CoverageState(bits.mapKeys { index.segmentIdOf(it.key) })
    }

    /** Candidate ticks of one observation (OFF excluded) and their distances. */
    private class Candidates(val nodes: LongArray, val dist: DoubleArray)

    private fun candidates(p: Xy): Candidates {
        val bestD = HashMap<Int, Double>()
        val bestN = HashMap<Int, Long>()
        for (n in index.queryTicks(p, params.bufferM)) {
            val s = (n ushr 32).toInt()
            val tp = pts[s][n.toInt()]
            val d = hypot(tp.x - p.x, tp.y - p.y)
            val cur = bestD[s]
            if (cur == null || d < cur || (d == cur && n < bestN.getValue(s))) { bestD[s] = d; bestN[s] = n }
        }
        val order = bestD.keys.sortedWith(compareBy<Int>({ bestD.getValue(it) }, { it })).take(params.maxCandidates)
        return Candidates(LongArray(order.size) { bestN.getValue(order[it]) }, DoubleArray(order.size) { bestD.getValue(order[it]) })
    }

    private fun matchPiece(obs: List<Xy>, mark: (Long) -> Unit) {
        val cands = obs.map { candidates(it) }
        // state 0 = OFF, state i >= 1 = cands[k].nodes[i - 1]
        val back = arrayOfNulls<IntArray>(obs.size)
        /** preds[k][i - 1] = Dijkstra predecessors from candidate i of observation k (k < last) */
        val preds = arrayOfNulls<Array<HashMap<Long, Long>>>(obs.size)

        var score = DoubleArray(cands[0].nodes.size + 1) { i -> emission(cands[0], i) }
        for (k in 1 until obs.size) {
            val prev = cands[k - 1]
            val cur = cands[k]
            val g = hypot(obs[k].x - obs[k - 1].x, obs[k].y - obs[k - 1].y)
            val maxM = 2 * g + 50.0
            // routes[i - 1][j - 1] = route metres prev candidate i -> cur candidate j, NaN = none
            val pk = Array(prev.nodes.size) { HashMap<Long, Long>() }
            val routes = Array(prev.nodes.size) { i ->
                val r = graph.routesFrom(prev.nodes[i], maxM, pk[i])
                DoubleArray(cur.nodes.size) { j -> r[cur.nodes[j]] ?: Double.NaN }
            }
            preds[k - 1] = pk
            val next = DoubleArray(cur.nodes.size + 1)
            val bk = IntArray(cur.nodes.size + 1)
            for (j in 0..cur.nodes.size) {
                var best = Double.NEGATIVE_INFINITY
                var arg = 0
                for (i in 0..prev.nodes.size) {
                    if (score[i] == Double.NEGATIVE_INFINITY) continue
                    val tr = when {
                        i == 0 && j == 0 -> 0.0
                        i == 0 || j == 0 -> -params.offSwitchPenalty
                        else -> {
                            val r = routes[i - 1][j - 1]
                            if (r.isNaN()) continue // no route within maxM: impossible
                            -abs(r - g) / params.transitionBetaM
                        }
                    }
                    val v = score[i] + tr
                    if (v > best) { best = v; arg = i }
                }
                next[j] = best + emission(cur, j)
                bk[j] = arg
            }
            back[k] = bk
            score = next
        }

        // backtrack
        val path = IntArray(obs.size)
        var bestEnd = 0
        for (i in score.indices) if (score[i] > score[bestEnd]) bestEnd = i
        path[obs.size - 1] = bestEnd
        for (k in obs.size - 1 downTo 1) path[k - 1] = back[k]!![path[k]]

        for (k in obs.indices) {
            val j = path[k]
            if (j == 0) continue
            val v = cands[k].nodes[j - 1]
            mark(v)
            val i = if (k > 0) path[k - 1] else 0
            if (i == 0) continue
            val from = cands[k - 1].nodes[i - 1]
            val pred = preds[k - 1]!![i - 1]
            var n = v
            while (n != from) {
                n = pred.getValue(n)
                mark(n)
            }
        }
    }

    private fun emission(c: Candidates, state: Int): Double =
        if (state == 0) offEmission else -c.dist[state - 1] * c.dist[state - 1] / twoSigma2

    /** Piece resampled every hmmStepM of path length; first and last points included. */
    private fun observations(piece: List<Xy>): List<Xy> {
        val step = params.hmmStepM
        val out = ArrayList<Xy>()
        out += piece[0]
        var next = step // path length of the next observation
        var acc = 0.0
        for (i in 1 until piece.size) {
            val a = piece[i - 1]
            val b = piece[i]
            val len = hypot(b.x - a.x, b.y - a.y)
            while (len > 0 && next <= acc + len) {
                val f = (next - acc) / len
                out += Xy(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y))
                next += step
            }
            acc += len
        }
        if (out.last() != piece.last()) out += piece.last()
        return out
    }

    private fun extent(piece: List<Xy>): Double {
        var x0 = Double.POSITIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var y1 = Double.NEGATIVE_INFINITY
        for (p in piece) { x0 = min(x0, p.x); y0 = min(y0, p.y); x1 = max(x1, p.x); y1 = max(y1, p.y) }
        return hypot(x1 - x0, y1 - y0)
    }
}
