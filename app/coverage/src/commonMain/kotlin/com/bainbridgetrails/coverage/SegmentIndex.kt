package com.bainbridgetrails.coverage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

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
 * A segment whose credit a user can see: included in the island total, or part of some trail. Every other segment is
 * a matching competitor only; its credit never leaves matching ([SegmentIndex.coverFixes]).
 */
val SegmentGeom.isScored: Boolean get() = included || trailIds.isNotEmpty()

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
    private val slot: Map<String, Int> = HashMap<String, Int>(ids.size * 2).also { m -> ids.forEachIndexed { i, id -> m[id] = i } }

    // Ticks live in flat primitive arrays (startup on debuggable Android builds is dominated by per-object work):
    // slot s owns global tick indices tickStart[s] until tickStart[s + 1]; tick t of s is tickStart[s] + t.
    // Positions and spacing are exactly [resample]'s. Counts and spacing are eager (cheap: line lengths only, all
    // that [Progress] needs); positions, bearings and the grid are built on first matching use (or [prepare]).
    private val tickStart: IntArray
    private val segmentList: List<SegmentGeom> = segments

    /** Per slot: the length each of its ticks stands for ([Tick.lengthM]); 0 without ticks. */
    private val tickShare: DoubleArray

    init {
        val n = segments.size
        tickStart = IntArray(n + 1)
        tickShare = DoubleArray(n)
        for (s in 0 until n) {
            val c = segments[s].coords
            var k = 0
            if (c.size >= 2) {
                val total = lineLength(c)
                k = ticksFor(total, params.tickM)
                tickShare[s] = total / k
            }
            tickStart[s + 1] = tickStart[s] + k
        }
    }

    /** Projected length of [c]: the same sum, in the same order, as [resample]'s. */
    private fun lineLength(c: List<List<Double>>): Double {
        var total = 0.0
        if (c is PackedCoords) {
            var x0 = proj.x(c.lon(0))
            var y0 = proj.y(c.lat(0))
            for (i in 1 until c.size) {
                val x1 = proj.x(c.lon(i))
                val y1 = proj.y(c.lat(i))
                total += hypot(x1 - x0, y1 - y0)
                x0 = x1
                y0 = y1
            }
        } else {
            val line = c.map { proj.toXy(it[0], it[1]) }
            for (i in 1 until line.size) total += hypot(line[i].x - line[i - 1].x, line[i].y - line[i - 1].y)
        }
        return total
    }

    /**
     * [x]/[y]: tick positions; [bearing]: per tick, the bearing for the heading check, smoothed over headingBaselineM
     * (ruling R11); [grid]: cell key -> packed (segment slot, tick index), each cell's entries in (slot, tick) order.
     */
    private class Ticks(val x: DoubleArray, val y: DoubleArray, val bearing: DoubleArray, val grid: Map<Long, LongArray>)

    private val ticks: Ticks by lazy {
        val buf = TickBuffer(tickStart.last())
        for (s in segmentList.indices) {
            val line = segmentList[s].projectedLine(proj)
            if (line.size >= 2) buf.resample(line, params.tickM, params.headingBaselineM)
            check(buf.size == tickStart[s + 1]) { "tick count drift on segment ${ids[s]}" }
        }
        Ticks(buf.x, buf.y, buf.b, buildGrid(buf.x, buf.y))
    }

    /**
     * Builds tick positions, bearings and the grid (and, for [Matcher.HMM], the tick graph) now instead of on first
     * [coverFixes] / [cover]; idempotent and thread-safe. Call it off the main thread once the UI is up so the first
     * match is fast.
     */
    fun prepare() {
        ticks
        if (params.matcher == Matcher.HMM) hmm
    }

    private fun count(s: Int): Int = tickStart[s + 1] - tickStart[s]

    /**
     * Groups ticks by 50 m cell with one primitive sort: key = (cell number in the ticks' bounding box) shl 40 or
     * global tick index, so each cell's run is in tick order. Falls back to hashing for extents too large to pack.
     */
    private fun buildGrid(tickX: DoubleArray, tickY: DoubleArray): Map<Long, LongArray> {
        val total = tickX.size
        if (total == 0) return emptyMap()
        val cx = IntArray(total) { cell(tickX[it]) }
        val cy = IntArray(total) { cell(tickY[it]) }
        val minX = cx.min()
        val minY = cy.min()
        val w = cx.max().toLong() - minX + 1
        val h = cy.max().toLong() - minY + 1
        val slotOf = IntArray(total)
        for (s in 0 until tickStart.size - 1) for (g in tickStart[s] until tickStart[s + 1]) slotOf[g] = s
        fun packed(g: Int) = pack(slotOf[g], g - tickStart[slotOf[g]])
        if (w * h >= (1L shl 23) || total.toLong() >= (1L shl 40)) {
            val m = HashMap<Long, MutableList<Long>>()
            for (g in 0 until total) m.getOrPut(cellKey(cx[g], cy[g])) { ArrayList() } += packed(g)
            return m.mapValues { it.value.toLongArray() }
        }
        val keys = LongArray(total) { g -> (((cy[g].toLong() - minY) * w + (cx[g] - minX)) shl 40) or g.toLong() }
        keys.sort()
        val out = HashMap<Long, LongArray>()
        var i = 0
        while (i < total) {
            val c = keys[i] ushr 40
            var j = i
            while (j < total && keys[j] ushr 40 == c) j++
            val g0 = (keys[i] and TICK_MASK).toInt()
            out[cellKey(cx[g0], cy[g0])] = LongArray(j - i) { packed((keys[i + it] and TICK_MASK).toInt()) }
            i = j
        }
        return out
    }

    /** Read access for [TickGraph]: tick positions of every segment, indexed by slot. */
    internal val tickPoints: List<List<Xy>> by lazy {
        val t = ticks
        List(tickStart.size - 1) { s -> List(count(s)) { Xy(t.x[tickStart[s] + it], t.y[tickStart[s] + it]) } }
    }

    val projection: LocalProjection get() = proj

    internal fun slotOf(id: String): Int? = slot[id]

    internal fun segmentIdOf(slot: Int): String = ids[slot]

    /** Packed (slot shl 32 | tick) of every tick within [radius] of [p], via the 50 m grid. */
    internal fun queryTicks(p: Xy, radius: Double): List<Long> {
        val t = ticks
        val tickX = t.x
        val tickY = t.y
        val grid = t.grid
        val out = ArrayList<Long>()
        for (cx in cell(p.x - radius)..cell(p.x + radius)) for (cy in cell(p.y - radius)..cell(p.y + radius)) {
            val entries = grid[cellKey(cx, cy)] ?: continue
            for (e in entries) {
                val g = tickStart[(e ushr 32).toInt()] + e.toInt()
                if (hypot(tickX[g] - p.x, tickY[g] - p.y) <= radius) out += e
            }
        }
        return out
    }

    fun tickCount(segmentId: String): Int = slot[segmentId]?.let { count(it) } ?: 0

    fun tickLengthM(segmentId: String): Double = slot[segmentId]?.let { tickShare[it] } ?: 0.0

    /** Built on first HMM use, once per index. */
    private val hmm: HmmMatcher by lazy { HmmMatcher(this, TickGraph(this, params.junctionM), params) }

    private val scoredIds: Set<String> = segments.filter { it.isScored }.mapTo(HashSet()) { it.id }

    /**
     * Filters [fixes], then matches with [CoverageParams.matcher]: COMPETITIVE = [cover], HMM = [HmmMatcher.cover].
     * The returned state holds only scored segments ([isScored]); competitor credit never leaves matching (privacy,
     * spec §5/§8). [cover] and [HmmMatcher.cover] stay unfiltered for evaluation.
     */
    fun coverFixes(fixes: List<Fix>): CoverageState {
        val pieces = filterFixes(fixes, params, proj)
        val raw = when (params.matcher) {
            Matcher.COMPETITIVE -> cover(pieces)
            Matcher.HMM -> hmm.cover(pieces)
        }
        return raw.retainSegments(scoredIds)
    }

    /** Spike 3 competitive matcher, regardless of [CoverageParams.matcher]. */
    fun cover(pieces: List<List<Xy>>): CoverageState {
        val tk = ticks
        val tickX = tk.x
        val tickY = tk.y
        val tickBearing = tk.bearing
        val grid = tk.grid
        val nSeg = tickStart.size - 1
        val bits = arrayOfNulls<LongArray>(nSeg)
        val dS = DoubleArray(nSeg) { Double.POSITIVE_INFINITY }
        val bestT = IntArray(nSeg)
        var cand = IntArray(64)
        val buf = params.bufferM
        val tol = params.headingToleranceDeg

        fun mark(s: Int, t: Int, q: Xy) {
            if (t < 0 || t >= count(s)) return
            val g = tickStart[s] + t
            if (hypot(tickX[g] - q.x, tickY[g] - q.y) > buf) return
            val w = bits[s] ?: LongArray((count(s) + 63) / 64).also { bits[s] = it }
            w[t / 64] = w[t / 64] or (1L shl (t % 64))
        }

        fun visit(q: Xy, h: Double) {
            var n = 0 // distinct segments with a candidate
            for (cx in cell(q.x - buf)..cell(q.x + buf)) for (cy in cell(q.y - buf)..cell(q.y + buf)) {
                val entries = grid[cellKey(cx, cy)] ?: continue
                for (e in entries) {
                    val s = (e ushr 32).toInt()
                    val t = e.toInt()
                    val g = tickStart[s] + t
                    val d = hypot(tickX[g] - q.x, tickY[g] - q.y)
                    if (d > buf) continue
                    if (tol != null && axialDiffDeg(h, tickBearing[g]) > tol) continue
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
                        if (nt < 0 || nt >= count(s)) continue
                        if (tol != null && axialDiffDeg(h, tickBearing[tickStart[s] + nt]) > tol) continue
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
        const val TICK_MASK = (1L shl 40) - 1
        const val CELL_M = 50.0
        const val MAX_BASELINE_STEPS = 30
        fun cell(v: Double): Int = floor(v / CELL_M).toInt()
        fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
        fun pack(s: Int, t: Int): Long = (s.toLong() shl 32) or (t.toLong() and 0xFFFFFFFFL)
    }
}

/**
 * Growable flat tick arrays for [SegmentIndex]. [resample] appends one line's ticks with exactly [com.bainbridgetrails.coverage.resample]'s
 * positions plus their smoothed bearings (ruling R11), without per-tick objects.
 */
private class TickBuffer(capacity: Int) {
    var x = DoubleArray(capacity)
    var y = DoubleArray(capacity)
    var b = DoubleArray(capacity)
    var size = 0
        private set

    private var cum = DoubleArray(64)

    /**
     * Appends [line]'s ticks (>= 1; [line] has >= 2 points) and returns their share (tick length). Tick bearing:
     * bearing(P(s - b/2), P(s + b/2)), s = the tick's distance along the line, b = [baselineM], both clamped to
     * [0, L]; if L < b, the end-to-end bearing.
     */
    fun resample(line: List<Xy>, stepM: Double, baselineM: Double): Double {
        require(stepM > 0.0) { "stepM must be > 0" }
        val m = line.size
        if (cum.size < m) cum = DoubleArray(maxOf(m, cum.size * 2))
        val cum = cum
        cum[0] = 0.0
        for (k in 1 until m) cum[k] = cum[k - 1] + hypot(line[k].x - line[k - 1].x, line[k].y - line[k - 1].y)
        val total = cum[m - 1]
        val n = ticksFor(total, stepM)
        val share = total / n
        if (size + n > x.size) {
            val cap = maxOf(x.size * 2, size + n)
            x = x.copyOf(cap)
            y = y.copyOf(cap)
            b = b.copyOf(cap)
        }
        // positions: as resample() (segment k covers cum[k]..cum[k + 1])
        var seg = 0
        for (i in 0 until n) {
            val offset = (i + 0.5) * share
            while (seg < m - 2 && offset > cum[seg + 1]) seg++
            val a = line[seg]
            val e = line[seg + 1]
            val segLen = hypot(e.x - a.x, e.y - a.y)
            val t = if (segLen == 0.0) 0.0 else ((offset - cum[seg]) / segLen).coerceIn(0.0, 1.0)
            x[size + i] = a.x + t * (e.x - a.x)
            y[size + i] = a.y + t * (e.y - a.y)
        }
        // bearings, smoothed over baselineM
        if (total < baselineM) {
            val bb = bearingDeg(line[0], line[m - 1])
            for (i in 0 until n) b[size + i] = bb
        } else {
            for (i in 0 until n) {
                val sAt = (i + 0.5) * share
                val d0 = (sAt - baselineM / 2).coerceIn(0.0, total)
                val d1 = (sAt + baselineM / 2).coerceIn(0.0, total)
                val k0 = leg(m, d0)
                val k1 = leg(m, d1)
                b[size + i] = bearingDeg(px(line, k0, d0), py(line, k0, d0), px(line, k1, d1), py(line, k1, d1))
            }
        }
        size += n
        return share
    }

    /** k = last vertex leg starting at or before d (cum is non-decreasing). */
    private fun leg(m: Int, d: Double): Int {
        var lo = 0
        var hi = m - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun frac(k: Int, d: Double): Double {
        val len = cum[k + 1] - cum[k]
        return if (len == 0.0) 0.0 else ((d - cum[k]) / len).coerceIn(0.0, 1.0)
    }

    private fun px(line: List<Xy>, k: Int, d: Double): Double = line[k].x + frac(k, d) * (line[k + 1].x - line[k].x)

    private fun py(line: List<Xy>, k: Int, d: Double): Double = line[k].y + frac(k, d) * (line[k + 1].y - line[k].y)
}
