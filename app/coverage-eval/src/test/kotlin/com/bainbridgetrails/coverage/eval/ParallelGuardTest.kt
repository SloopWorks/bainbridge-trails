package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Matcher
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.coverage.Xy
import java.util.Locale
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Correlated-noise parallel guard: trails A (straight, 1000 m) and B (12 m to its side, joined to A at both ends).
 * Walking A with 10 m correlated noise (tau 20 s) drifts the track toward B for tens of seconds at a time. COMPETITIVE
 * matching credits B whenever the drifted fix is nearer B; HMM must stay on A (B is only reachable via the ends).
 */
class ParallelGuardTest {
    private fun dataset(): Dataset {
        val proj = LocalProjection()
        fun seg(id: String, trail: String, vararg pts: Pair<Double, Double>): SegmentGeom {
            val line = pts.map { Xy(it.first, it.second) }
            val len = line.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()
            return SegmentGeom(id, len, true, listOf(trail), line.map { val (lon, lat) = proj.toLonLat(it); listOf(lon, lat) })
        }
        val segs = listOf(
            seg("a", "A", 0.0 to 0.0, 1000.0 to 0.0),
            seg("b", "B", 0.0 to 0.0, 30.0 to 12.0, 970.0 to 12.0, 1000.0 to 0.0),
        )
        val trails = listOf(TrailMeta("A", "A", listOf("a"), 0.62), TrailMeta("B", "B", listOf("b"), 0.62))
        return Dataset(trails, segs.sumOf { it.lengthM }, segs)
    }

    /** Mean (A fraction, B fraction) over 5 seeds of walking A at a 5 s interval. */
    private fun meanAB(params: CoverageParams): Pair<Double, Double> {
        val ds = dataset()
        val index = SegmentIndex(ds.segments, params)
        val progress = Progress(ds, index, params)
        val noise = NoiseModel(sigmaM = 10.0, tauS = 20.0, outlierRate = 0.0)
        val fr = (0L until 5L).map { seed ->
            val state = index.coverFixes(Simulator(ds, 1000 + seed).walkTrail("A", noise, 5.0))
            progress.trailFraction(state, "A") to progress.trailFraction(state, "B")
        }
        return fr.map { it.first }.average() to fr.map { it.second }.average()
    }

    @Test
    fun hmmCreditsLessOfTheParallelTrailThanCompetitive() {
        val (compA, compB) = meanAB(CoverageParams(matcher = Matcher.COMPETITIVE)) // option B
        val (hmmA, hmmB) = meanAB(CoverageParams(matcher = Matcher.HMM))
        println(String.format(Locale.ROOT, "ParallelGuard: COMPETITIVE A=%.3f B=%.3f  HMM A=%.3f B=%.3f", compA, compB, hmmA, hmmB))
        assertTrue(hmmB < compB, "HMM B $hmmB vs COMPETITIVE B $compB")
        assertTrue(hmmA >= 0.85, "HMM A $hmmA")
    }
}
