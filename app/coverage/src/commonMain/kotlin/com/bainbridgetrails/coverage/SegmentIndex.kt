package com.bainbridgetrails.coverage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
)

/**
 * Ticks every ~tickM along each segment, in a 50 m grid, plus competitive map matching (ruling R3).
 *
 * Each piece is densified to points q every tickM along each line (vertices included); q carries the
 * line's bearing h (none if the line is < 2 m). For each q:
 * - candidates = ticks within bufferM of q that pass the heading check
 *   (headingToleranceDeg == null, or no h, or axialDiffDeg(h, tick bearing) <= headingToleranceDeg);
 * - d_S = nearest candidate tick of segment S; d* = min over S;
 * - every segment with d_S <= d* + matchMarginM gets all its candidate ticks of q marked covered.
 */
class SegmentIndex(
    segments: List<SegmentGeom>,
    private val params: CoverageParams,
    private val proj: LocalProjection = LocalProjection(),
) {
    val segments: Map<String, SegmentGeom> = segments.associateBy { it.id }

    private val ids: List<String> = segments.map { it.id }
    private val slot: Map<String, Int> = ids.withIndex().associate { (i, id) -> id to i }
    private val ticks: List<List<Tick>> = segments.map { s ->
        val line = s.coords.map { proj.toXy(it[0], it[1]) }
        if (line.size >= 2) resample(line, params.tickM) else emptyList()
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
        var candSArr = IntArray(256)
        var candTArr = IntArray(256)

        fun visit(q: Xy, h: Double?) {
            var n = 0
            val buf = params.bufferM
            for (cx in cell(q.x - buf)..cell(q.x + buf)) for (cy in cell(q.y - buf)..cell(q.y + buf)) {
                val entries = grid[cellKey(cx, cy)] ?: continue
                for (e in entries) {
                    val s = (e ushr 32).toInt()
                    val t = e.toInt()
                    val tick = ticks[s][t]
                    val d = hypot(tick.p.x - q.x, tick.p.y - q.y)
                    if (d > buf) continue
                    val tol = params.headingToleranceDeg
                    if (tol != null && h != null && axialDiffDeg(h, tick.bearingDeg) > tol) continue
                    if (n == candSArr.size) {
                        candSArr = candSArr.copyOf(n * 2)
                        candTArr = candTArr.copyOf(n * 2)
                    }
                    candSArr[n] = s
                    candTArr[n] = t
                    n++
                    if (d < dS[s]) dS[s] = d
                }
            }
            if (n == 0) return
            var dStar = Double.POSITIVE_INFINITY
            for (i in 0 until n) if (dS[candSArr[i]] < dStar) dStar = dS[candSArr[i]]
            val limit = dStar + params.matchMarginM
            for (i in 0 until n) {
                val s = candSArr[i]
                if (dS[s] > limit) continue
                val t = candTArr[i]
                val w = bits[s] ?: LongArray((ticks[s].size + 63) / 64).also { bits[s] = it }
                w[t / 64] = w[t / 64] or (1L shl (t % 64))
            }
            for (i in 0 until n) dS[candSArr[i]] = Double.POSITIVE_INFINITY
        }

        for (piece in pieces) {
            if (piece.size == 1) visit(piece[0], null)
            for (i in 0 until piece.size - 1) {
                val a = piece[i]
                val b = piece[i + 1]
                val len = hypot(b.x - a.x, b.y - a.y)
                val h = if (len < MIN_HEADING_LINE_M) null else bearingDeg(a, b)
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

    private companion object {
        const val CELL_M = 50.0
        const val MIN_HEADING_LINE_M = 2.0
        fun cell(v: Double): Int = floor(v / CELL_M).toInt()
        fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
        fun pack(s: Int, t: Int): Long = (s.toLong() shl 32) or (t.toLong() and 0xFFFFFFFFL)
    }
}
