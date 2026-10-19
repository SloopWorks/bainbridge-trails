package com.bainbridgetrails.coverage

import kotlin.math.hypot

/**
 * Tick connectivity graph. Node = (segment slot shl 32 | tick index). Undirected edges:
 * - consecutive ticks within a segment;
 * - end joins: segment endpoints (first/last coord) within [junctionM] of another segment's endpoint join those
 *   segments' end ticks;
 * - T-joins: a segment endpoint within [junctionM] of another segment's polyline joins that segment's nearest tick.
 * Edge weight = distance between the two tick positions.
 */
class TickGraph(private val index: SegmentIndex, private val junctionM: Double = 3.0) {
    private val pts: List<List<Xy>> = index.tickPoints
    private val adj = HashMap<Long, MutableList<Pair<Long, Double>>>()

    init {
        // build-time only: dropped after init so the graph retains adjacency alone (CSR deferred to before v1)
        val edgeSeen = HashSet<Pair<Long, Long>>()
        fun link(a: Long, b: Long) = link(a, b, edgeSeen)
        val ids = pts.indices.map { index.segmentIdOf(it) }
        val polylines = ids.map { id ->
            index.segments.getValue(id).coords.map { index.projection.toXy(it[0], it[1]) }
        }
        for (s in pts.indices) for (t in 0 until pts[s].size - 1) link(pack(s, t), pack(s, t + 1))

        // endpoints: (slot, isLast, position); only segments that have ticks
        class End(val slot: Int, val last: Boolean, val p: Xy) {
            val node: Long get() = pack(slot, if (last) pts[slot].size - 1 else 0)
        }
        val ends = ArrayList<End>()
        for (s in pts.indices) {
            if (pts[s].isEmpty()) continue
            ends += End(s, false, polylines[s].first())
            ends += End(s, true, polylines[s].last())
        }
        val cellSize = maxOf(junctionM, 1.0)
        fun c(v: Double) = kotlin.math.floor(v / cellSize).toInt()
        val endGrid = HashMap<Long, MutableList<End>>()
        for (e in ends) endGrid.getOrPut((c(e.p.x).toLong() shl 32) or (c(e.p.y).toLong() and 0xFFFFFFFFL)) { ArrayList() } += e

        for (e in ends) {
            // end joins
            for (cx in c(e.p.x - junctionM)..c(e.p.x + junctionM)) for (cy in c(e.p.y - junctionM)..c(e.p.y + junctionM)) {
                for (o in endGrid[(cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)] ?: continue) {
                    if (o.slot == e.slot && (o.last == e.last || e.last)) continue // own ends: join once, first->last
                    if (hypot(o.p.x - e.p.x, o.p.y - e.p.y) <= junctionM) link(e.node, o.node)
                }
            }
            // T-joins: nearest tick of any other segment whose polyline passes within junctionM
            selfTJoin(e.slot, e.last, e.p, e.node, edgeSeen)
            val bySlot = HashMap<Int, Long>()
            val bestD = HashMap<Int, Double>()
            for (n in index.queryTicks(e.p, junctionM + MAX_TICK_REACH_M)) {
                val s = slotOf(n)
                if (s == e.slot) continue
                val tp = pts[s][tickOf(n)]
                val d = hypot(tp.x - e.p.x, tp.y - e.p.y)
                if (d < (bestD[s] ?: Double.POSITIVE_INFINITY)) { bestD[s] = d; bySlot[s] = n }
            }
            for ((s, n) in bySlot) {
                val line = polylines[s]
                var dl = Double.POSITIVE_INFINITY
                for (i in 0 until line.size - 1) dl = minOf(dl, distanceToSegment(e.p, line[i], line[i + 1]))
                if (dl <= junctionM) link(e.node, n)
            }
        }
    }

    /** T-join of an endpoint against its own segment's interior, skipping ticks within 2*junctionM along the line. */
    private fun selfTJoin(s: Int, last: Boolean, p: Xy, endNode: Long, edgeSeen: MutableSet<Pair<Long, Long>>) {
        val n = pts[s].size
        val share = index.tickLengthM(index.segmentIdOf(s))
        var best = -1
        var bestD = Double.POSITIVE_INFINITY
        for (t in 0 until n) {
            val along = ((if (last) n - 1 - t else t) + 0.5) * share
            if (along < 2 * junctionM) continue
            val d = hypot(pts[s][t].x - p.x, pts[s][t].y - p.y)
            if (d < bestD) { bestD = d; best = t }
        }
        if (best >= 0 && bestD <= junctionM + share / 2) link(endNode, pack(s, best), edgeSeen)
    }

    fun node(segmentId: String, tick: Int): Long = pack(requireNotNull(index.slotOf(segmentId)) { "unknown segment $segmentId" }, tick)

    fun neighbors(node: Long): List<Pair<Long, Double>> = adj[node] ?: emptyList()

    /**
     * Shortest route metres from [from] to every node within [maxM] (inclusive), bounded Dijkstra. If [pred] is given it
     * receives each reached node's predecessor on its shortest route ([from] has none).
     */
    fun routesFrom(from: Long, maxM: Double, pred: MutableMap<Long, Long>? = null): Map<Long, Double> {
        val dist = HashMap<Long, Double>()
        val done = HashSet<Long>()
        val heap = MinHeap()
        dist[from] = 0.0
        heap.push(0.0, from)
        while (heap.size > 0) {
            val d = heap.topKey()
            val u = heap.pop()
            if (!done.add(u)) continue
            for ((v, w) in neighbors(u)) {
                val nd = d + w
                if (nd > maxM) continue
                if (nd < (dist[v] ?: Double.POSITIVE_INFINITY)) {
                    dist[v] = nd
                    pred?.put(v, u)
                    heap.push(nd, v)
                }
            }
        }
        return dist
    }

    private fun link(a: Long, b: Long, edgeSeen: MutableSet<Pair<Long, Long>>) {
        if (a == b || !edgeSeen.add(if (a < b) a to b else b to a)) return
        val pa = pts[slotOf(a)][tickOf(a)]
        val pb = pts[slotOf(b)][tickOf(b)]
        val w = hypot(pa.x - pb.x, pa.y - pb.y)
        adj.getOrPut(a) { ArrayList() } += b to w
        adj.getOrPut(b) { ArrayList() } += a to w
    }

    /** Binary min-heap on (key, node); duplicates allowed (lazy deletion). */
    private class MinHeap {
        private var keys = DoubleArray(16)
        private var vals = LongArray(16)
        var size = 0
            private set

        fun topKey(): Double = keys[0]

        fun push(k: Double, v: Long) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= k) break
                keys[i] = keys[p]; vals[i] = vals[p]; i = p
            }
            keys[i] = k; vals[i] = v
        }

        fun pop(): Long {
            val top = vals[0]
            size--
            if (size > 0) {
                val k = keys[size]
                val v = vals[size]
                var i = 0
                while (true) {
                    var ch = 2 * i + 1
                    if (ch >= size) break
                    if (ch + 1 < size && keys[ch + 1] < keys[ch]) ch++
                    if (keys[ch] >= k) break
                    keys[i] = keys[ch]; vals[i] = vals[ch]; i = ch
                }
                keys[i] = k; vals[i] = v
            }
            return top
        }
    }

    private companion object {
        /** Slack for "polyline within junctionM implies a tick within junctionM + this" (tick spacing <= ~tickM). */
        const val MAX_TICK_REACH_M = 15.0
        fun pack(s: Int, t: Int): Long = (s.toLong() shl 32) or (t.toLong() and 0xFFFFFFFFL)
        fun slotOf(n: Long): Int = (n ushr 32).toInt()
        fun tickOf(n: Long): Int = n.toInt()
    }
}
