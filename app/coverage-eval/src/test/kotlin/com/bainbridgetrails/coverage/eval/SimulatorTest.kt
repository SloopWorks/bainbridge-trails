package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.coverage.Xy
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 3 trails, > 100 m apart. A: L-shaped chain of 2 segments (one stored reversed). B: 1 bent segment. C: 2 disconnected segments. */
internal fun syntheticDataset(): Dataset {
    val proj = LocalProjection()
    fun seg(id: String, trail: String, vararg pts: Pair<Double, Double>, category: String? = null): SegmentGeom {
        val line = pts.map { Xy(it.first, it.second) }
        val len = line.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
        return SegmentGeom(id, len, true, listOf(trail), line.map { val (lon, lat) = proj.toLonLat(it); listOf(lon, lat) })
    }
    val segs = listOf(
        seg("a1", "A", 0.0 to 0.0, 100.0 to 5.0, 200.0 to 0.0),
        seg("a2", "A", 200.0 to 300.0, 210.0 to 150.0, 200.0 to 0.0), // reversed: ends where a1 ends
        seg("b1", "B", 0.0 to 500.0, 150.0 to 520.0, 300.0 to 600.0),
        seg("c2", "C", 600.0 to 300.0, 800.0 to 300.0),
        seg("c1", "C", 600.0 to 0.0, 700.0 to 10.0, 800.0 to 0.0),
    )
    val trails = listOf(
        TrailMeta("A", "Trail A", listOf("a1", "a2"), 0.31),
        TrailMeta("B", "Trail B", listOf("b1"), 0.2),
        TrailMeta("C", "Trail C", listOf("c1", "c2"), 0.25),
    )
    return Dataset(trails, segs.sumOf { it.lengthM }, segs)
}

class SimulatorTest {
    private val ds = syntheticDataset()
    private val noisy = NoiseModel(sigmaM = 8.0)
    private val exact = NoiseModel(sigmaM = 0.0, outlierRate = 0.0)

    @Test
    fun simulatorIsDeterministic() {
        val a = Simulator(ds, 42).walkTrail("A", noisy, 1.0)
        val b = Simulator(ds, 42).walkTrail("A", noisy, 1.0)
        val c = Simulator(ds, 43).walkTrail("A", noisy, 1.0)
        assertTrue(a.isNotEmpty())
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun zeroNoiseWalkCompletes() {
        val params = CoverageParams()
        val index = SegmentIndex(ds.segments, params)
        val progress = Progress(ds, index, params)
        for (interval in listOf(1.0, 5.0)) for (seed in 0L until 4L) for (t in ds.trails) {
            val fixes = Simulator(ds, seed).walkTrail(t.id, exact, interval)
            val f = progress.trailFraction(index.coverFixes(fixes), t.id)
            assertTrue(f >= 0.99, "trail ${t.id} interval $interval seed $seed fraction $f")
        }
    }

    @Test
    fun connectedChainIsOneSessionAndChainsAreSeparated() {
        val gapMs = (CoverageParams().maxGapS * 1000).toLong()
        fun bigGaps(trail: String) = Simulator(ds, 7).walkTrail(trail, exact, 1.0)
            .zipWithNext().count { (p, q) -> q.timeMs - p.timeMs > gapMs }
        assertEquals(0, bigGaps("A")) // a1 + reversed a2 chain into one continuous walk
        assertEquals(0, bigGaps("B"))
        assertEquals(1, bigGaps("C")) // two disconnected segments: two sessions
    }

    @Test
    fun walkSpeedIsAboutWalkingPace() {
        val fixes = Simulator(ds, 3).walkTrail("B", exact, 1.0)
        val proj = LocalProjection()
        val xy = fixes.map { proj.toXy(it.lon, it.lat) }
        val dist = xy.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
        val speed = dist / ((fixes.last().timeMs - fixes.first().timeMs) / 1000.0)
        assertTrue(speed in 1.05..1.55, "speed $speed")
    }

    @Test
    fun sweepOnSyntheticDatasetGivesOneCleanRow() {
        val rows = sweep(
            ds, buffers = listOf(20.0), sigmas = listOf(0.0), intervals = listOf(1.0), seeds = 1,
            headingTolerances = listOf(30.0), margins = listOf(1.0),
        )
        assertEquals(1, rows.size)
        val r = rows.single()
        // sigma 0 still has the default 1% 50 m outliers; single ones are now dropped by the spike filter (rule 2b)
        assertTrue(r.meanTrueFraction >= 0.85, "$r")
        assertEquals(3, r.walks)
        assertEquals(0, r.falseCompletes)
        assertEquals(0.0, r.falseCreditRatio)
    }
}
