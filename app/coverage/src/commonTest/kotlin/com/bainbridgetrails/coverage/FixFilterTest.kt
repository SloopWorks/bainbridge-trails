package com.bainbridgetrails.coverage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixFilterTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()

    private fun fix(t: Long, x: Double, y: Double, acc: Double? = null, mock: Boolean = false): Fix {
        val (lon, lat) = proj.toLonLat(Xy(x, y))
        return Fix(t, lon, lat, acc, mock)
    }

    /** Straight eastward path at [speed] m/s, one fix per [stepS] seconds. */
    private fun walk(speed: Double, seconds: Int, stepS: Int = 1, t0: Long = 0, x0: Double = 0.0): List<Fix> =
        (0..seconds step stepS).map { s -> fix(t0 + s * 1000L, x0 + speed * s, 0.0) }

    @Test fun walkKept() {
        val r = filterFixes(walk(1.3, 299), params, proj)
        assertEquals(1, r.size)
        assertEquals(300, r[0].size)
    }

    /** 30 s walk, then [speed] m/s for 120 s. Returns surviving fast-part points beyond the first speedWindowS (30 s) ramp, where the
     *  30 s reference window still straddles the walk and speed ramps up. */
    private fun fastSurvivors(speed: Double): Int {
        val w = walk(1.3, 29)
        val x0 = 1.3 * 29
        val fast = (1..120).map { s -> fix((29 + s) * 1000L, x0 + speed * s, 0.0) }
        return filterFixes(w + fast, params, proj).sumOf { pc -> pc.count { it.x > x0 + speed * params.speedWindowS + 0.5 } }
    }

    @Test fun driveDropped() = assertEquals(0, fastSurvivors(13.0))

    @Test fun bikeDropped() = assertEquals(0, fastSurvivors(6.0))

    @Test fun runKept() = assertEquals(1, filterFixes(walk(3.5, 120), params, proj).size)

    @Test fun jumpBreaks() {
        val a = walk(1.3, 100)
        val b = walk(1.3, 100, t0 = 101_000, x0 = 130.0 + 300.0)
        assertEquals(2, filterFixes(a + b, params, proj).size)
    }

    @Test fun gapBreaks() {
        val a = walk(1.3, 100)
        val b = walk(1.3, 100, t0 = 190_000, x0 = 130.0 + 117.0)
        assertEquals(2, filterFixes(a + b, params, proj).size)
    }

    @Test fun mockAndInaccurateDropped() {
        val base = walk(1.3, 99)
        val mocked = base.mapIndexed { i, f -> if (i % 2 == 0) f.copy(isMock = true) else f }
        val r1 = filterFixes(mocked, params, proj)
        assertEquals(50, r1.sumOf { it.size })
        val inacc = base.mapIndexed { i, f -> if (i == 40) f.copy(accuracyM = 50.0) else f }
        assertEquals(99, filterFixes(inacc, params, proj).sumOf { it.size })
    }

    @Test fun negativeAccuracyDropped() {
        val base = walk(1.3, 99)
        val neg = base.mapIndexed { i, f -> if (i == 40) f.copy(accuracyM = -1.0) else f }
        assertEquals(99, filterFixes(neg, params, proj).sumOf { it.size })
        assertEquals(100, filterFixes(base, params, proj).sumOf { it.size }) // null accuracy = unknown, kept
    }

    @Test fun unsortedAndDuplicateFixes() {
        val clean = walk(1.3, 199)
        val messy = (clean + clean.take(10)).shuffled(kotlin.random.Random(7))
        assertEquals(filterFixes(clean, params, proj), filterFixes(messy, params, proj))
    }

    @Test fun sparseFixesStayOnePiece() {
        val r = filterFixes(walk(1.3, 600, stepS = 20), params, proj)
        assertEquals(1, r.size)
    }

    @Test fun singleSpikeOnWalk() {
        val w = walk(1.3, 299).toMutableList()
        w[150] = fix(150_000, 1.3 * 150, 60.0)
        val r = filterFixes(w, params, proj)
        assertTrue(r.size <= 2, "pieces=${r.size}")
        assertTrue(r.sumOf { it.size } >= 295, "points=${r.sumOf { it.size }}")
    }

    @Test fun singleOutlierRemoved() {
        val w = walk(1.3, 299).toMutableList()
        val spike = Xy(1.3 * 150, 50.0)
        w[150] = fix(150_000, spike.x, spike.y)
        val r = filterFixes(w, params, proj)
        assertEquals(1, r.size)
        assertEquals(299, r[0].size)
        assertTrue(r[0].none { kotlin.math.hypot(it.x - spike.x, it.y - spike.y) < 1.0 }, "spike kept")
    }

    @Test fun realTurnNotRemoved() {
        // 100 s east then 100 s north at 1.3 m/s: a sharp 90 deg corner at (130, 0)
        val east = (0..100).map { s -> fix(s * 1000L, 1.3 * s, 0.0) }
        val north = (1..100).map { s -> fix((100 + s) * 1000L, 130.0, 1.3 * s) }
        val r = filterFixes(east + north, params, proj)
        assertEquals(1, r.size)
        assertEquals(201, r[0].size)
    }

    private fun gauss(r: kotlin.random.Random): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-12)
        val u2 = r.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)
    }

    /** Eastward 1.3 m/s walk along y = 0, one fix per [stepS] s, white noise sigma [sigma] m on x and y. */
    private fun noisyWalk(seconds: Int, sigma: Double, stepS: Int = 1, seed: Int = 3): List<Fix> {
        val rnd = kotlin.random.Random(seed)
        return (0..seconds step stepS).map { s -> fix(s * 1000L, 1.3 * s + sigma * gauss(rnd), sigma * gauss(rnd)) }
    }

    private fun rmsPerp(ys: List<Double>) = kotlin.math.sqrt(ys.sumOf { it * it } / ys.size)

    @Test fun smoothingReducesNoise() {
        val fixes = noisyWalk(299, sigma = 8.0)
        val before = rmsPerp(fixes.map { proj.toXy(it.lon, it.lat).y })
        val out = filterFixes(fixes, params, proj)
        val after = rmsPerp(out.flatten().map { it.y })
        println("smoothingReducesNoise: rms before=$before after=$after window=${params.smoothWindowS}s")
        assertTrue(out.sumOf { it.size } >= 290, "points=${out.sumOf { it.size }}")
        assertTrue(after < 0.6 * before, "before=$before after=$after")
    }

    @Test fun smoothingOffIsIdentity() {
        // sigma 3: no fix trips the spike or speed rules, so the unsmoothed output is every input fix in one piece
        val fixes = noisyWalk(299, sigma = 3.0)
        val expected = fixes.map { proj.toXy(it.lon, it.lat) }
        val out = filterFixes(fixes, params.copy(smoothWindowS = 0.0), proj)
        assertEquals(1, out.size)
        assertEquals(expected.size, out[0].size)
        for (i in expected.indices) {
            assertTrue(kotlin.math.hypot(out[0][i].x - expected[i].x, out[0][i].y - expected[i].y) < 1e-6, "fix $i moved")
        }
        assertTrue(filterFixes(fixes, params, proj)[0] != out[0], "default smoothing should move fixes")
    }

    @Test fun smoothingKeepsSparseFixes() {
        val fixes = noisyWalk(600, sigma = 8.0, stepS = 20)
        assertEquals(10.0, params.smoothWindowS)
        assertEquals(filterFixes(fixes, params.copy(smoothWindowS = 0.0), proj), filterFixes(fixes, params, proj))
    }

    /** Every rule at once: mock and inaccurate fixes, a spike, a drive, a gap, a lone fix between two gaps, unsorted input. */
    private fun everyRule(): List<Fix> {
        val a = walk(1.3, 100).mapIndexed { i, f ->
            when (i) {
                10, 11 -> f.copy(isMock = true)
                20 -> f.copy(accuracyM = 50.0)
                30 -> f.copy(accuracyM = -1.0)
                else -> f.copy(accuracyM = 8.0)
            }
        }.toMutableList()
        a[50] = fix(50_000, 1.3 * 50, 60.0) // single-fix spike
        val x1 = 1.3 * 100
        val drive = (1..60).map { s -> fix((100 + s) * 1000L, x1 + 13.0 * s, 0.0, 5.0) }
        val x2 = x1 + 13.0 * 60
        val after = walk(1.3, 60, t0 = 161_000, x0 = x2)
        val lone = listOf(fix(320_000, x2 + 200.0, 0.0, 5.0)) // > maxGapS on both sides: a one-fix piece
        val last = walk(1.3, 60, t0 = 420_000, x0 = x2 + 210.0)
        return (a + drive + after + lone + last).shuffled(kotlin.random.Random(11))
    }

    @Test
    fun `keptFixes equals the points filterFixes keeps`() {
        val raw = everyRule()
        val unsmoothed = params.copy(smoothWindowS = 0.0)
        val kept = keptFixes(raw, unsmoothed, proj)
        assertEquals(filterFixes(raw, unsmoothed, proj).flatten(), kept.map { proj.toXy(it.lon, it.lat) })
        assertTrue(kept.zipWithNext().all { (p, q) -> p.timeMs < q.timeMs }, "time order")
        assertTrue(kept.none { it.isMock || (it.accuracyM ?: 0.0) > params.maxAccuracyM || (it.accuracyM ?: 0.0) < 0.0 })
        assertTrue(kept.none { it.timeMs == 320_000L }, "a one-fix piece is not kept")
        assertTrue(kept.none { it.timeMs in 120_000L..160_000L }, "the drive is not kept")
        // Smoothing moves points, never membership: the same fixes, unmoved (Play reads the raw positions).
        assertEquals(kept, keptFixes(raw, params, proj))
        assertEquals(filterFixes(raw, params, proj).sumOf { it.size }, kept.size)
        assertEquals(filterFixes(walk(1.3, 299), params, proj).sumOf { it.size }, keptFixes(walk(1.3, 299), params, proj).size)
    }
}
