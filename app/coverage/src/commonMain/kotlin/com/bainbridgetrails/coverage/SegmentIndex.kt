package com.bainbridgetrails.coverage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/** One segment as in coverage_segments.json; coords are [lon, lat]. */
@Serializable
data class SegmentGeom(
    val id: String,
    @SerialName("length_m") val lengthM: Double,
    val included: Boolean,
    @SerialName("trail_ids") val trailIds: List<String>,
    val coords: List<List<Double>>,
    val category: String? = null,
)

/**
 * Ticks every ~tickM along each segment, in a 50 m grid, plus competitive map matching (rulings R3 + R6).
 *
 * Each piece is densified to points q every tickM along each line (vertices included). q's heading h comes from
 * a displacement baseline >= headingBaselineM around its line (R6); with no such baseline q is stationary and
 * credits nothing. For each q:
 * - candidates = ticks within bufferM of q that pass the heading check
 *   (headingToleranceDeg == null, or axialDiffDeg(h, tick bearing) <= headingToleranceDeg). The tick bearing is
 *   smoothed over the same scale as the walker's baseline (R11): bearing(P(s - b/2), P(s + b/2)), s = the tick's
 *   distance along its segment, b = headingBaselineM, both clamped to [0, L]; if L < b, the end-to-end bearing;
 * - d_S = nearest candidate tick of segment S; d* = min over S;
 * - every segment with d_S <= d* + matchMarginM gets its nearest candidate tick and that tick's index
 *   neighbours (+-1, heading-checked) marked, each only if within bufferM of q.
 */
class SegmentIndex(
    segments: List<SegmentGeom>,
    private val params: CoverageParams,
    private val proj: LocalProjection = LocalProjection(),
) {
    val segments: Map<String, SegmentGeom> = segments.associateBy { it.id }

    private val ids: List<String> = segments.map { it.id }
    private val slot: Map<String, Int> = ids.withIndex().associate { (i, id) -> id to i }
    private val ticks: List<List<Tick>>

    /** Per tick: bearing used for the heading check, smoothed over headingBaselineM along the segment (ruling R11). */
    private val tickBearing: List<DoubleArray>

    init {
        val lines = segments.map { s -> s.coords.map { proj.toXy(it[0], it[1]) } }
        ticks = lines.map { if (it.size >= 2) resample(it, params.tickM) else emptyList() }
        tickBearing = lines.mapIndexed { i, line -> smoothedBearings(line, ticks[i], params.headingBaselineM) }
    }

    /** cell key -> packed (segment slot, tick index) */
    private val grid: Map<Long, LongArray> = run {
        val m = HashMap<Long, MutableList<Long>>()
        ticks.forEachIndexed { s, ts ->
            ts.forEachIndexed { t, tick ->
                m.getOrPut(cellKey(cell(tick.p.x), cell(tick.p.y))) { ArrayList() } += pack(s, t)
            }
        }
        m.mapValues { it.value.toLongArray() }
    }

    fun tickCount(segmentId: String): Int = slot[segmentId]?.let { ticks[it].size } ?: 0

    fun tickLengthM(segmentId: String): Double = slot[segmentId]?.let { ticks[it].firstOrNull()?.lengthM } ?: 0.0

    fun coverFixes(fixes: List<Fix>): CoverageState = cover(filterFixes(fixes, params, proj))

    fun cover(pieces: List<List<Xy>>): CoverageState {
        val bits = arrayOfNulls<LongArray>(ticks.size)
        val dS = DoubleArray(ticks.size) { Double.POSITIVE_INFINITY }
        val bestT = IntArray(ticks.size)
        var cand = IntArray(64)
        val buf = params.bufferM
        val tol = params.headingToleranceDeg

        fun mark(s: Int, t: Int, q: Xy) {
            if (t < 0 || t >= ticks[s].size) return
            val p = ticks[s][t].p
            if (hypot(p.x - q.x, p.y - q.y) > buf) return
            val w = bits[s] ?: LongArray((ticks[s].size + 63) / 64).also { bits[s] = it }
            w[t / 64] = w[t / 64] or (1L shl (t % 64))
        }

        fun visit(q: Xy, h: Double) {
            var n = 0 // distinct segments with a candidate
            for (cx in cell(q.x - buf)..cell(q.x + buf)) for (cy in cell(q.y - buf)..cell(q.y + buf)) {
                val entries = grid[cellKey(cx, cy)] ?: continue
                for (e in entries) {
                    val s = (e ushr 32).toInt()
                    val t = e.toInt()
                    val tick = ticks[s][t]
                    val d = hypot(tick.p.x - q.x, tick.p.y - q.y)
                    if (d > buf) continue
                    if (tol != null && axialDiffDeg(h, tickBearing[s][t]) > tol) continue
                    if (dS[s] == Double.POSITIVE_INFINITY) {
                        if (n == cand.size) cand = cand.copyOf(n * 2)
                        cand[n++] = s
                    }
                    if (d < dS[s]) { dS[s] = d; bestT[s] = t }
                }
            }
            if (n == 0) return
            var dStar = Double.POSITIVE_INFINITY
            for (i in 0 until n) if (dS[cand[i]] < dStar) dStar = dS[cand[i]]
            val limit = dStar + params.matchMarginM
            for (i in 0 until n) {
                val s = cand[i]
                if (dS[s] <= limit) {
                    val t = bestT[s]
                    // nearest tick + index neighbours; neighbours must pass the heading check too
                    mark(s, t, q)
                    for (nt in intArrayOf(t - 1, t + 1)) {
                        if (nt !in ticks[s].indices) continue
                        if (tol != null && axialDiffDeg(h, tickBearing[s][nt]) > tol) continue
                        mark(s, nt, q)
                    }
                }
                dS[s] = Double.POSITIVE_INFINITY
            }
        }

        for (piece in pieces) {
            for (i in 0 until piece.size - 1) {
                val h = baselineHeading(piece, i) ?: continue // stationary: no credit
                val a = piece[i]
                val b = piece[i + 1]
                val len = hypot(b.x - a.x, b.y - a.y)
                var off = 0.0
                while (off < len || off == 0.0) {
                    val f = if (len == 0.0) 0.0 else off / len
                    visit(Xy(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y)), h)
                    off += params.tickM
                }
                if (i == piece.size - 2) visit(b, h)
            }
        }

        val out = HashMap<String, LongArray>()
        bits.forEachIndexed { s, w -> if (w != null) out[ids[s]] = w }
        return CoverageState(out)
    }

    /**
     * Heading for points on line i (piece[i]..piece[i+1]) from a displacement baseline (ruling R6). Pairs
     * a = piece[i - j], b = piece[i + 1 + m] with j, m in 0..[MAX_BASELINE_STEPS] (clamped to the piece) are tried
     * in order of total span j + m, most balanced first; the first pair >= headingBaselineM apart gives
     * h = bearing(a, b). Asymmetric pairs matter at U-turns, where a symmetric pair stays folded. null = stationary.
     */
    private fun baselineHeading(piece: List<Xy>, i: Int): Double? {
        val maxJ = minOf(MAX_BASELINE_STEPS, i)
        val maxM = minOf(MAX_BASELINE_STEPS, piece.size - 2 - i)
        val min2 = params.headingBaselineM * params.headingBaselineM
        for (span in 0..(maxJ + maxM)) {
            val lo = maxOf(0, span - maxM)
            val hi = minOf(maxJ, span)
            if (lo > hi) continue
            for (j in (lo..hi).sortedBy { abs(2 * it - span) }) {
                val a = piece[i - j]
                val b = piece[i + 1 + span - j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (dx * dx + dy * dy >= min2) return bearingDeg(a, b)
            }
        }
        return null
    }

    private companion object {
        fun smoothedBearings(line: List<Xy>, ts: List<Tick>, baselineM: Double): DoubleArray {
            if (ts.isEmpty()) return DoubleArray(0)
            val cum = DoubleArray(line.size)
            for (k in 1 until line.size) cum[k] = cum[k - 1] + hypot(line[k].x - line[k - 1].x, line[k].y - line[k - 1].y)
            val total = cum.last()
            if (total < baselineM) {
                val b = bearingDeg(line.first(), line.last())
                return DoubleArray(ts.size) { b }
            }
            fun at(d: Double): Xy {
                // k = last vertex leg starting at or before d (cum is non-decreasing)
                var lo = 0
                var hi = line.size - 2
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (cum[mid] <= d) lo = mid else hi = mid - 1
                }
                val k = lo
                val len = cum[k + 1] - cum[k]
                val f = if (len == 0.0) 0.0 else ((d - cum[k]) / len).coerceIn(0.0, 1.0)
                return Xy(line[k].x + f * (line[k + 1].x - line[k].x), line[k].y + f * (line[k + 1].y - line[k].y))
            }
            // tick i sits at distance (i + 0.5) * share (see resample)
            return DoubleArray(ts.size) { i ->
                val sAt = (i + 0.5) * ts[i].lengthM
                bearingDeg(at((sAt - baselineM / 2).coerceIn(0.0, total)), at((sAt + baselineM / 2).coerceIn(0.0, total)))
            }
        }

        const val CELL_M = 50.0
        const val MAX_BASELINE_STEPS = 30
        fun cell(v: Double): Int = floor(v / CELL_M).toInt()
        fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
        fun pack(s: Int, t: Int): Long = (s.toLong() shl 32) or (t.toLong() and 0xFFFFFFFFL)
    }
}
