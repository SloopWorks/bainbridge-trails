package com.bainbridgetrails.coverage

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HmmMatcherTest {
    private val proj = LocalProjection()
    private val hmm = CoverageParams(matcher = Matcher.HMM)
    private val competitive = CoverageParams(matcher = Matcher.COMPETITIVE)

    private fun seg(id: String, vararg pts: Xy, included: Boolean = true, category: String? = null): SegmentGeom {
        var len = 0.0
        for (i in 1 until pts.size) len += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        return SegmentGeom(id, len, included, emptyList(), pts.map { proj.toLonLat(it).let { (lo, la) -> listOf(lo, la) } }, category)
    }

    private fun fix(t: Long, p: Xy): Fix = proj.toLonLat(p).let { (lon, lat) -> Fix(t, lon, lat) }

    private fun gauss(r: Random): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * r.nextDouble())
    }

    /** Positions every [stepS] s at [speed] m/s along the polyline [path] (start included, end clamped). */
    private fun positions(path: List<Xy>, speed: Double = 1.3, stepS: Int = 1): List<Xy> {
        var total = 0.0
        for (i in 1 until path.size) total += hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y)
        val n = (total / (speed * stepS)).toInt()
        return (0..n).map { k -> along(path, minOf(total, k * speed * stepS)) }
    }

    private fun along(line: List<Xy>, d: Double): Xy {
        var acc = 0.0
        for (i in 0 until line.size - 1) {
            val l = hypot(line[i + 1].x - line[i].x, line[i + 1].y - line[i].y)
            if (acc + l >= d || i == line.size - 2) {
                val f = if (l == 0.0) 0.0 else ((d - acc) / l).coerceIn(0.0, 1.0)
                return Xy(line[i].x + f * (line[i + 1].x - line[i].x), line[i].y + f * (line[i + 1].y - line[i].y))
            }
            acc += l
        }
        return line.last()
    }

    private fun fixes(pts: List<Xy>, stepS: Int = 1, sigma: Double = 0.0, rnd: Random = Random(0), t0: Long = 0): List<Fix> =
        pts.mapIndexed { k, p ->
            val q = if (sigma > 0) Xy(p.x + sigma * gauss(rnd), p.y + sigma * gauss(rnd)) else p
            fix(t0 + k * stepS * 1000L, q)
        }

    private fun frac(idx: SegmentIndex, st: CoverageState, id: String) =
        st.covered(id).size.toDouble() / idx.tickCount(id)

    @Test fun straightWalkCovers() {
        val idx = SegmentIndex(listOf(seg("A", Xy(0.0, 0.0), Xy(200.0, 0.0))), hmm, proj)
        val f = frac(idx, idx.coverFixes(fixes(positions(listOf(Xy(0.0, 0.0), Xy(200.0, 0.0))))), "A")
        println("straightWalkCovers: A=$f")
        assertTrue(f >= 0.98, "A=$f")
    }

    private val ladder = listOf(
        seg("A", Xy(0.0, 0.0), Xy(200.0, 0.0)),
        seg("B", Xy(0.0, 12.0), Xy(200.0, 12.0)),
        seg("X1", Xy(0.0, 0.0), Xy(0.0, 12.0)),
        seg("X2", Xy(200.0, 0.0), Xy(200.0, 12.0)),
    )

    @Test fun driftTowardParallelNotCredited() {
        val walk = fixes(positions(listOf(Xy(0.0, 0.0), Xy(200.0, 0.0))), sigma = 3.0, rnd = Random(7))
        val mid = walk.size / 2
        val drifted = walk.mapIndexed { k, f ->
            if (k in mid - 15 until mid + 15) {
                val p = proj.toXy(f.lon, f.lat)
                fix(f.timeMs, Xy(p.x, p.y + 9.0))
            } else f
        }
        val idxH = SegmentIndex(ladder, hmm, proj)
        val stH = idxH.coverFixes(drifted)
        val idxC = SegmentIndex(ladder, competitive, proj)
        val stC = idxC.coverFixes(drifted)
        val a = frac(idxH, stH, "A")
        val bH = stH.covered("B").size
        val bC = stC.covered("B").size
        println("driftTowardParallelNotCredited: HMM A=$a B=$bH ticks; COMPETITIVE A=${frac(idxC, stC, "A")} B=$bC ticks")
        assertTrue(a >= 0.95, "A=$a")
        assertTrue(bH <= 1, "HMM B=$bH")
        assertTrue(bC > 5, "COMPETITIVE B=$bC (documents why HMM exists)")
    }

    @Test fun turnAtJunctionCredited() {
        // C meets A's interior at (100, 0): a T-junction
        val segs = listOf(seg("A", Xy(0.0, 0.0), Xy(200.0, 0.0)), seg("C", Xy(100.0, 100.0), Xy(100.0, 0.0)))
        val idx = SegmentIndex(segs, hmm, proj)
        val st = idx.coverFixes(
            fixes(positions(listOf(Xy(0.0, 0.0), Xy(100.0, 0.0), Xy(100.0, 100.0))), sigma = 3.0, rnd = Random(3)),
        )
        val aWalked = (0 until idx.tickCount("A")).filter { (it + 0.5) * idx.tickLengthM("A") <= 100.0 }
        val a = st.covered("A").count { it in aWalked }.toDouble() / aWalked.size
        val c = frac(idx, st, "C")
        println("turnAtJunctionCredited: A(walked part)=$a C=$c")
        assertTrue(a >= 0.9, "A=$a")
        assertTrue(c >= 0.9, "C=$c")
    }

    @Test fun competitorPathWinsOverTrail() {
        val segs = listOf(
            seg("T", Xy(0.0, 0.0), Xy(200.0, 0.0)),
            seg("P", Xy(0.0, 15.0), Xy(200.0, 15.0), included = false, category = "connector"),
        )
        val idx = SegmentIndex(segs, hmm, proj)
        val walk = fixes(positions(listOf(Xy(0.0, 15.0), Xy(200.0, 15.0))), sigma = 3.0, rnd = Random(5))
        // raw matcher: coverFixes drops competitor P (I1), so its credit is only visible here
        val st = HmmMatcher(idx, TickGraph(idx, hmm.junctionM), hmm).cover(filterFixes(walk, hmm, proj))
        assertEquals(CoverageState.EMPTY, idx.coverFixes(walk))
        val t = st.covered("T").size
        val p = frac(idx, st, "P")
        println("competitorPathWinsOverTrail: T=$t ticks P=$p")
        assertTrue(t <= 1, "T=$t")
        assertTrue(p >= 0.9, "P=$p")
    }

    @Test fun offNetworkCreditsNothing() {
        val segs = listOf(seg("A", Xy(0.0, 0.0), Xy(200.0, 0.0)), seg("B", Xy(0.0, 80.0), Xy(200.0, 80.0)))
        val idx = SegmentIndex(segs, hmm, proj)
        val st = idx.coverFixes(fixes(positions(listOf(Xy(0.0, 40.0), Xy(200.0, 40.0)))))
        println("offNetworkCreditsNothing: $st")
        assertEquals(CoverageState.EMPTY, st)
    }

    /**
     * Inside bufferM (30) but beyond offNetworkM (pinned at 25; the Spike 3b default 30 = bufferM leaves no such band):
     * candidates exist every observation, OFF must win.
     */
    @Test fun offNetworkBesideTrailCreditsNothing() {
        val idx = SegmentIndex(listOf(seg("A", Xy(0.0, 0.0), Xy(300.0, 0.0))), hmm.copy(offNetworkM = 25.0), proj)
        val st = idx.coverFixes(fixes(positions(listOf(Xy(0.0, 27.5), Xy(300.0, 27.5))), sigma = 3.0, rnd = Random(11)))
        println("offNetworkBesideTrailCreditsNothing: 27.5 m -> A=${st.covered("A").size} of ${idx.tickCount("A")} ticks")
        assertEquals(CoverageState.EMPTY, st)
    }

    /** Documents, asserts nothing: under the current params a walk closer than ~min(offNetworkM, bufferM) to a lone trail is credited. */
    @Test fun besideTrailThresholdDocumented() {
        val idx = SegmentIndex(listOf(seg("A", Xy(0.0, 0.0), Xy(300.0, 0.0))), hmm, proj)
        for (d in listOf(15.0, 25.0, 27.5, 30.0, 32.5, 35.0)) {
            val st = idx.coverFixes(fixes(positions(listOf(Xy(0.0, d), Xy(300.0, d))), sigma = 3.0, rnd = Random(11)))
            println("besideTrailThresholdDocumented: $d m -> A=${st.covered("A").size} of ${idx.tickCount("A")} ticks")
        }
    }

    @Test fun stationaryJitterBounded() {
        val o = Xy(0.0, 0.0)
        val segs = listOf(0.0, 120.0, 240.0).mapIndexed { i, b ->
            val r = b * PI / 180.0
            seg("S$i", o, Xy(100.0 * sin(r), 100.0 * cos(r)))
        }
        val idx = SegmentIndex(segs, hmm, proj)
        val rnd = Random(1)
        val st = idx.coverFixes((0 until 600).map { s -> fix(s * 1000L, Xy(8.0 * gauss(rnd), 8.0 * gauss(rnd))) })
        for (s in segs) {
            val n = st.covered(s.id).size
            println("stationaryJitterBounded: ${s.id} ticks=$n")
            assertTrue(n <= 2, "${s.id}=$n")
        }
    }

    @Test fun repeatAndReverseIdempotent() {
        val idx = SegmentIndex(listOf(seg("A", Xy(0.0, 0.0), Xy(300.0, 0.0))), hmm, proj)
        val fwd = fixes(positions(listOf(Xy(0.0, 0.0), Xy(150.0, 0.0))))
        val back = fwd.reversed().mapIndexed { k, f -> f.copy(timeMs = fwd.last().timeMs + (k + 1) * 1000L) }
        val sF = idx.coverFixes(fwd)
        val sB = idx.coverFixes(back)
        val sFB = idx.coverFixes(fwd + back)
        println(
            "repeatAndReverseIdempotent: fwd=${sF.covered("A").size} back=${sB.covered("A").size} " +
                "fwd+back=${sFB.covered("A").size} of ${idx.tickCount("A")}",
        )
        assertTrue(sF.covered("A").size in 1 until idx.tickCount("A"))
        // reversing the same path, alone or appended in the same piece, adds nothing to the forward credit
        assertEquals(sF, sB)
        assertEquals(sF, sF.union(sB))
        assertEquals(sF, sF.union(sFB))
    }

    @Test fun sparseFixes() {
        val idx = SegmentIndex(listOf(seg("A", Xy(0.0, 0.0), Xy(200.0, 0.0))), hmm, proj)
        val st = idx.coverFixes(fixes(positions(listOf(Xy(0.0, 0.0), Xy(210.0, 0.0)), stepS = 20), stepS = 20))
        val f = frac(idx, st, "A")
        println("sparseFixes: A=$f")
        assertTrue(f >= 0.95, "A=$f")
    }

    @Test fun directMatcherMatchesDispatch() {
        val idx = SegmentIndex(ladder, hmm, proj)
        val pieces = filterFixes(fixes(positions(listOf(Xy(0.0, 0.0), Xy(200.0, 0.0)))), hmm, proj)
        val direct = HmmMatcher(idx, TickGraph(idx, hmm.junctionM), hmm).cover(pieces)
        assertEquals(direct, idx.coverFixes(fixes(positions(listOf(Xy(0.0, 0.0), Xy(200.0, 0.0))))))
    }

    private fun junctionWalk(): Pair<SegmentIndex, List<Fix>> {
        val segs = ladder + seg("C", Xy(100.0, 100.0), Xy(100.0, 12.0))
        val idx = SegmentIndex(segs, hmm, proj)
        val path = listOf(Xy(0.0, 0.0), Xy(200.0, 0.0), Xy(200.0, 12.0), Xy(100.0, 12.0), Xy(100.0, 100.0))
        return idx to fixes(positions(path), sigma = 6.0, rnd = Random(21))
    }

    /**
     * I2: crediting recomputes each chosen route (no retained predecessor maps). Pinned to the states the retained-preds
     * implementation produced (HEAD 1797ac4), so the change is bit-identical. Both walks credit multi-segment routes.
     */
    @Test fun routeCreditPinned() {
        val (idx, f) = junctionWalk()
        assertEquals(
            CoverageState.decode("""{"A":"//////8=","B":"AADw//8=","C":"//8D","X1":"AQ==","X2":"Bw=="}"""),
            HmmMatcher(idx, TickGraph(idx, hmm.junctionM), hmm).cover(filterFixes(f, hmm, proj)),
        )
        val sparse = fixes(
            positions(listOf(Xy(0.0, 0.0), Xy(200.0, 0.0), Xy(200.0, 12.0), Xy(0.0, 12.0)), stepS = 15),
            stepS = 15, sigma = 8.0, rnd = Random(4),
        )
        val idx2 = SegmentIndex(ladder, hmm, proj)
        assertEquals(
            CoverageState.decode("""{"A":"//////8=","B":"//////8=","X1":"Aw==","X2":"Bw=="}"""),
            HmmMatcher(idx2, TickGraph(idx2, hmm.junctionM), hmm).cover(filterFixes(sparse, hmm, proj)),
        )
    }

    /** I1 (privacy): competitor credit stays inside matching; coverFixes returns scored segments only, both matchers. */
    @Test fun coverFixesDropsCompetitorSegments() {
        val segs = listOf(
            seg("P", Xy(0.0, 0.0), Xy(200.0, 0.0), included = false, category = "connector"),
            seg("T", Xy(0.0, 200.0), Xy(200.0, 200.0)),
        )
        val walk = fixes(positions(listOf(Xy(0.0, 0.0), Xy(200.0, 0.0))), sigma = 3.0, rnd = Random(9))
        for (params in listOf(hmm, competitive)) {
            val idx = SegmentIndex(segs, params, proj)
            val pieces = filterFixes(walk, params, proj)
            val raw = when (params.matcher) {
                Matcher.HMM -> HmmMatcher(idx, TickGraph(idx, params.junctionM), params).cover(pieces)
                Matcher.COMPETITIVE -> idx.cover(pieces)
            }
            assertTrue(raw.covered("P").isNotEmpty(), "${params.matcher}: raw cover credits the competitor")
            assertEquals(CoverageState.EMPTY, idx.coverFixes(walk), "${params.matcher}")
        }
    }

    @Test fun isScoredRule() {
        assertTrue(seg("a", Xy(0.0, 0.0), Xy(1.0, 0.0)).isScored)
        assertTrue(!seg("b", Xy(0.0, 0.0), Xy(1.0, 0.0), included = false).isScored)
        assertTrue(seg("c", Xy(0.0, 0.0), Xy(1.0, 0.0), included = false).copy(trailIds = listOf("t")).isScored)
    }
}
