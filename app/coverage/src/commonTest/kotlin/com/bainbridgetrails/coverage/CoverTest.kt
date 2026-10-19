package com.bainbridgetrails.coverage

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()

    private fun lonLat(p: Xy): List<Double> = proj.toLonLat(p).let { listOf(it.first, it.second) }

    private fun seg(id: String, vararg pts: Xy): SegmentGeom {
        var len = 0.0
        for (i in 1 until pts.size) len += kotlin.math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        return SegmentGeom(id, len, true, emptyList(), pts.map { lonLat(it) })
    }

    private fun fix(t: Long, p: Xy): Fix {
        val (lon, lat) = proj.toLonLat(p)
        return Fix(t, lon, lat)
    }

    private fun gauss(r: Random): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-12)
        val u2 = r.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /** Fixes every [stepS] s at [speed] m/s from [from] to [to] (inclusive of start), optional gaussian noise. */
    private fun walk(
        from: Xy, to: Xy, speed: Double = 1.3, stepS: Int = 1, t0: Long = 0,
        sigma: Double = 0.0, rnd: Random? = null,
    ): List<Fix> {
        val len = kotlin.math.hypot(to.x - from.x, to.y - from.y)
        val n = (len / (speed * stepS)).toInt()
        return (0..n).map { k ->
            val f = (k * speed * stepS / len).coerceAtMost(1.0)
            var p = Xy(from.x + f * (to.x - from.x), from.y + f * (to.y - from.y))
            if (sigma > 0) p = Xy(p.x + sigma * gauss(rnd!!), p.y + sigma * gauss(rnd))
            fix(t0 + k * stepS * 1000L, p)
        }
    }

    private fun frac(idx: SegmentIndex, st: CoverageState, id: String) =
        st.covered(id).size.toDouble() / idx.tickCount(id)

    @Test fun straightWalkCoversSegment() {
        val idx = SegmentIndex(listOf(seg("seg-000001", Xy(0.0, 0.0), Xy(200.0, 0.0))), params, proj)
        val st = idx.coverFixes(walk(Xy(0.0, 0.0), Xy(200.0, 0.0)))
        assertEquals(1.0, frac(idx, st, "seg-000001"))
        assertEquals(200.0, st.coveredM(idx, "seg-000001"), 1e-6)
    }

    @Test fun parallelNeighborNotCredited() {
        val idx = SegmentIndex(
            listOf(
                seg("seg-A", Xy(0.0, 0.0), Xy(200.0, 0.0)),
                seg("seg-B", Xy(0.0, 12.0), Xy(200.0, 12.0)),
            ),
            params, proj,
        )
        val st = idx.coverFixes(walk(Xy(0.0, 0.0), Xy(200.0, 0.0), sigma = 5.0, rnd = Random(1)))
        val a = frac(idx, st, "seg-A")
        val b = frac(idx, st, "seg-B")
        println("parallelNeighborNotCredited: A=$a B=$b")
        assertTrue(a >= 0.90, "A=$a")
        assertTrue(b < 0.50, "B=$b")
    }

    @Test fun perpendicularCrossingCreditsLittle() {
        val segs = listOf(seg("seg-000001", Xy(0.0, 0.0), Xy(200.0, 0.0)))
        val fixes = walk(Xy(100.0, -50.0), Xy(100.0, 50.0))
        val off = SegmentIndex(segs, params.copy(headingToleranceDeg = null), proj).coverFixes(fixes)
        val on = SegmentIndex(segs, params, proj).coverFixes(fixes)
        val nOff = off.covered("seg-000001").size
        val nOn = on.covered("seg-000001").size
        println("perpendicularCrossingCreditsLittle: headingOff=$nOff headingOn=$nOn")
        assertTrue(nOff <= (2 * params.bufferM / params.tickM).toInt() + 1, "off=$nOff")
        assertTrue(nOff > 0, "off=$nOff")
        assertEquals(0, nOn)
    }

    @Test fun stationaryJitterBounded() {
        val o = Xy(0.0, 0.0)
        val ends = listOf(0.0, 120.0, 240.0).map { b ->
            val r = b * PI / 180.0
            Xy(100.0 * sin(r), 100.0 * cos(r))
        }
        val segs = ends.mapIndexed { i, e -> seg("seg-00000${i + 1}", o, e) }
        val idx = SegmentIndex(segs, params, proj)
        val rnd = Random(1)
        val fixes = (0 until 600).map { s -> fix(s * 1000L, Xy(8.0 * gauss(rnd), 8.0 * gauss(rnd))) }
        val st = idx.coverFixes(fixes)
        val limit = 2 * params.bufferM + params.tickM
        for (s in segs) {
            val m = st.coveredM(idx, s.id)
            println("stationaryJitterBounded: ${s.id} covered=${m}m (limit ${limit}m)")
            assertTrue(m <= limit, "${s.id}=$m")
        }
    }

    @Test fun sparseFixesInterpolate() {
        val idx = SegmentIndex(listOf(seg("seg-000001", Xy(0.0, 0.0), Xy(200.0, 0.0))), params, proj)
        val st = idx.coverFixes(walk(Xy(0.0, 0.0), Xy(210.0, 0.0), stepS = 20))
        val f = frac(idx, st, "seg-000001")
        println("sparseFixesInterpolate: $f")
        assertTrue(f >= 0.95, "f=$f")
    }

    private val partialSeg = listOf(seg("seg-000001", Xy(0.0, 0.0), Xy(300.0, 0.0)))

    @Test fun repeatAndReverseIdempotent() {
        val idx = SegmentIndex(partialSeg, params, proj)
        val fwd = walk(Xy(0.0, 0.0), Xy(150.0, 0.0))
        // exact reverse of the same path, continuing in time
        val back = fwd.reversed().mapIndexed { k, f -> f.copy(timeMs = fwd.last().timeMs + (k + 1) * 1000L) }
        val sF = idx.coverFixes(fwd)
        val sFB = idx.coverFixes(fwd + back)
        assertTrue(sF.covered("seg-000001").size in 1 until idx.tickCount("seg-000001"))
        assertEquals(sF, sFB)
        assertEquals(sF, sF.union(sFB))
        assertEquals(sFB, sFB.union(sF))
    }

    @Test fun encodeDecodeRoundTrip() {
        val idx = SegmentIndex(
            partialSeg + seg("seg-000002", Xy(0.0, 100.0), Xy(300.0, 100.0)) + seg("seg-000003", Xy(0.0, 500.0), Xy(300.0, 500.0)),
            params, proj,
        )
        val st = idx.coverFixes(
            walk(Xy(0.0, 0.0), Xy(150.0, 0.0)) + walk(Xy(0.0, 100.0), Xy(290.0, 100.0), t0 = 1_000_000),
        )
        val json = st.encode()
        println("encodeDecodeRoundTrip: $json")
        assertTrue(json.startsWith("{\"seg-000001\":\""), json)
        assertTrue("seg-000003" !in json, json)
        assertEquals(st, CoverageState.decode(json))
        assertEquals(st.hashCode(), CoverageState.decode(json).hashCode())
        assertEquals(CoverageState.EMPTY, CoverageState.decode(CoverageState.EMPTY.encode()))
        assertEquals("{}", CoverageState.EMPTY.encode())
    }

    @Test fun encodeBitOrderLittleEndian() {
        // ticks 0 and 9 -> bytes [0x01, 0x02] -> "AQI="
        val st = CoverageState.decode("{\"seg-000001\":\"AQI=\"}")
        assertEquals(setOf(0, 9), st.covered("seg-000001"))
        assertEquals("{\"seg-000001\":\"AQI=\"}", st.encode())
    }

    @Test fun unionAccumulatesAcrossSessions() {
        val idx = SegmentIndex(partialSeg, params, proj)
        val whole = walk(Xy(0.0, 0.0), Xy(150.0, 0.0))
        val half = whole.size / 2
        val s1 = idx.coverFixes(whole.subList(0, half))
        val s2 = idx.coverFixes(whole.subList(half, whole.size).map { it.copy(timeMs = it.timeMs + 3_600_000) })
        val sW = idx.coverFixes(whole)
        assertTrue(s1 != sW && s2 != sW)
        assertEquals(sW, s1.union(s2))
        assertEquals(sW, CoverageState.decode(s1.encode()).union(CoverageState.decode(s2.encode())))
    }
}
