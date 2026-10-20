package com.bainbridgetrails.coverage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TickGraphTest {
    private val proj = LocalProjection()

    private fun seg(id: String, vararg pts: Xy): SegmentGeom {
        var len = 0.0
        for (i in 1 until pts.size) len += kotlin.math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        return SegmentGeom(id, len, true, emptyList(), pts.map { proj.toLonLat(it).let { (lo, la) -> listOf(lo, la) } })
    }

    private fun graph(params: CoverageParams = CoverageParams(), vararg s: SegmentGeom): Pair<SegmentIndex, TickGraph> {
        val idx = SegmentIndex(s.toList(), params, proj)
        return idx to TickGraph(idx)
    }

    private fun lastTick(idx: SegmentIndex, id: String) = idx.tickCount(id) - 1

    @Test fun withinSegmentChain() {
        val (idx, g) = graph(CoverageParams(tickM = 10.0), seg("A", Xy(0.0, 0.0), Xy(100.0, 0.0)))
        val r = g.routesFrom(g.node("A", 0), 500.0)
        val d = assertNotNull(r[g.node("A", lastTick(idx, "A"))])
        assertEquals(90.0, d, 1.0)
    }

    @Test fun endpointsJoin() {
        val (idx, g) = graph(s = arrayOf(seg("A", Xy(0.0, 0.0), Xy(100.0, 0.0)), seg("B", Xy(100.0, 0.0), Xy(200.0, 0.0))))
        val d = assertNotNull(g.routesFrom(g.node("A", 0), 500.0)[g.node("B", lastTick(idx, "B"))])
        assertEquals(195.0, d, 2.0)
    }

    @Test fun nearEndsConnect() {
        val (idx, g) = graph(s = arrayOf(seg("A", Xy(0.0, 0.0), Xy(100.0, 0.0)), seg("B", Xy(102.5, 0.0), Xy(202.5, 0.0))))
        assertNotNull(g.routesFrom(g.node("A", 0), 500.0)[g.node("B", lastTick(idx, "B"))])
    }

    @Test fun parallelNotConnected() {
        val (idx, g) = graph(s = arrayOf(seg("A", Xy(0.0, 0.0), Xy(100.0, 0.0)), seg("B", Xy(0.0, 12.0), Xy(100.0, 12.0))))
        val r = g.routesFrom(g.node("A", 0), 500.0)
        for (t in 0 until idx.tickCount("B")) assertFalse(g.node("B", t) in r)
    }

    @Test fun tJunctionConnects() {
        val (idx, g) = graph(s = arrayOf(seg("A", Xy(0.0, 0.0), Xy(100.0, 0.0)), seg("C", Xy(50.0, 51.0), Xy(50.0, 1.0))))
        val c = g.node("C", lastTick(idx, "C"))
        val mid = idx.tickCount("A") / 2
        val best = (mid - 1..mid).minOf { g.routesFrom(c, 500.0)[g.node("A", it)] ?: Double.MAX_VALUE }
        assertTrue(best < 10.0, "route $best")
    }

    @Test fun boundedSearch() {
        val (_, g) = graph(s = arrayOf(seg("A", Xy(0.0, 0.0), Xy(100.0, 0.0))))
        val r = g.routesFrom(g.node("A", 5), 20.0)
        assertTrue(r.size > 1)
        assertTrue(r.values.all { it <= 20.0 })
    }

    @Test fun closedLoopSelfJoins() {
        val r = 200.0 / (2 * kotlin.math.PI)
        val pts = (0..64).map { i ->
            val a = 2 * kotlin.math.PI * i / 64
            Xy(r * kotlin.math.cos(a), r * kotlin.math.sin(a))
        }
        val (idx, g) = graph(s = arrayOf(seg("L", *pts.toTypedArray())))
        val d = assertNotNull(g.routesFrom(g.node("L", lastTick(idx, "L")), 500.0)[g.node("L", 0)])
        assertTrue(d < 10.0, "route $d")
    }
}
