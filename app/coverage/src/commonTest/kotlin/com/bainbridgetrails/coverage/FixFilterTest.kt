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
}
