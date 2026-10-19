package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Straight 1000 m trails: D at y=0, decoy E parallel at y=10 (inside a 20 m buffer), F far away at y=500. */
private fun straightDataset(): Dataset {
    val proj = LocalProjection()
    fun seg(id: String, trail: String, y: Double) = SegmentGeom(
        id, 1000.0, true, listOf(trail),
        listOf(0.0, 500.0, 1000.0).map { x -> proj.toLonLat(com.bainbridgetrails.coverage.Xy(x, y)).let { listOf(it.first, it.second) } },
    )
    val segs = listOf(seg("d1", "D", 0.0), seg("e1", "E", 10.0), seg("f1", "F", 500.0))
    val trails = listOf(
        TrailMeta("D", "D", listOf("d1"), 0.62),
        TrailMeta("E", "E", listOf("e1"), 0.62),
        TrailMeta("F", "F", listOf("f1"), 0.62),
    )
    return Dataset(trails, 3000.0, segs)
}

/**
 * Known-answer tests for the sweep metrics at sigma 0 with outliers off. Heading tolerance stays 30 deg throughout: every trail here
 * is straight (or gently bent, for the synthetic set), so the heading check never confounds them.
 */
class SweepMetricsTest {
    private val exact: (Double) -> NoiseModel = { NoiseModel(it, outlierRate = 0.0) }

    private fun row(ds: Dataset, margin: Double, intervals: List<Double> = listOf(1.0)) = sweep(
        ds, buffers = listOf(20.0), sigmas = listOf(0.0), intervals = intervals, seeds = 2,
        headingTolerances = listOf(30.0), margins = listOf(margin), noiseFor = exact,
    )

    @Test
    fun parallelDecoyInsideBufferGetsFalseCredit() {
        // margin 15 >= 10 m offset: walking D also credits all of E (and vice versa), F nothing.
        // falseCreditRatio = (1000 + 1000) / 3000 per seed; each of D, E completes the other.
        val r = row(straightDataset(), margin = 15.0).single()
        assertTrue(abs(r.falseCreditRatio - 2.0 / 3.0) <= 0.02, "$r")
        assertEquals(2 * 2, r.falseCompletes) // 2 trails x 2 seeds
    }

    @Test
    fun competitiveMatchingRejectsDecoyAtDefaultMargin() {
        val r = row(straightDataset(), margin = 1.0).single()
        assertEquals(0.0, r.falseCreditRatio, "$r")
        assertEquals(0, r.falseCompletes)
    }

    @Test
    fun zeroNoiseCompleteRateIsOne() {
        for (r in row(syntheticDataset(), margin = 1.0, intervals = listOf(1.0, 5.0))) {
            assertEquals(1.0, r.completeRate, "$r")
            assertTrue(r.p5TrueFraction >= 0.99, "$r")
        }
    }

    @Test
    fun standingStillAtZeroNoiseEarnsAtMostOneTick() {
        for (ds in listOf(straightDataset(), syntheticDataset())) {
            val r = row(ds, margin = 1.0).single()
            assertTrue(r.standCreditM in 0.0..CoverageParams().tickM, "$r")
        }
    }

    @Test
    fun walkingPaceVehicleEarnsNothingBeyondAWalk() {
        val ds = straightDataset()
        val index = SegmentIndex(ds.segments, CoverageParams())
        val noise = NoiseModel(0.0, outlierRate = 0.0)
        val s = Simulator(ds, 9).traverseSessions(listOf("d1"), noise, 1.0, 1.3, VEHICLE_LEAD_IN_WALK_S).single()
        assertEquals(31, s.leadInFixes) // t = 0..30 s at 1 s
        val lead = totalCoveredM(ds, index, index.coverFixes(s.fixes.take(s.leadInFixes)))
        val (chains, credit) = vehicleCreditM(ds, index, listOf("d1"), noise, 1.0, 1.3, seed = 9)
        assertEquals(1, chains)
        // lead-in + "vehicle" credit = exactly what walking the whole 1000 m covers (all of it), nothing more
        assertTrue(abs(lead + credit - 1000.0) <= CoverageParams().tickM, "lead $lead credit $credit")
    }

    @Test
    fun drivingLeaksOnlyTheTransition() {
        // 30 s walk then 13 m/s: the speed check (vs the fix 30 s earlier) trips ~7 s after the switch, so ~90 m leak unsmoothed
        val ds = straightDataset()
        fun credit(p: CoverageParams) =
            vehicleCreditM(ds, SegmentIndex(ds.segments, p), listOf("d1"), NoiseModel(0.0, outlierRate = 0.0), 1.0, 13.0, seed = 9).second
        val raw = credit(CoverageParams(smoothWindowS = 0.0))
        assertTrue(raw > 50.0 && raw < 200.0, "credit $raw") // 80 m at the time of writing
        // smoothing pulls the leaked transition fixes back toward the walk: never more leak (45 m at the time of writing)
        val smoothed = credit(CoverageParams())
        assertTrue(smoothed <= raw, "smoothed $smoothed raw $raw")
    }

    @Test
    fun smoothWindowDimensionGivesOneRowEachAndCsvColumn() {
        val rows = sweep(
            straightDataset(), buffers = listOf(20.0), sigmas = listOf(0.0), intervals = listOf(1.0), seeds = 1,
            headingTolerances = listOf(30.0), margins = listOf(1.0), smoothWindows = listOf(0.0, 10.0, 20.0), noiseFor = exact,
        )
        assertEquals(listOf(0.0, 10.0, 20.0), rows.map { it.smoothWindowS })
        val col = SweepRow.CSV_HEADER.split(",").indexOf("smooth_window_s")
        assertTrue(col >= 0, SweepRow.CSV_HEADER)
        assertEquals(listOf("0.0000", "10.0000", "20.0000"), rows.map { it.csv().split(",")[col] })
        assertEquals(SweepRow.CSV_HEADER.split(",").size, rows[0].csv().split(",").size)
    }

    @Test
    fun headingToleranceDimensionGivesOneRowEachAndCsvMarksOff() {
        val rows = sweep(
            straightDataset(), buffers = listOf(20.0), sigmas = listOf(0.0), intervals = listOf(1.0), seeds = 1,
            headingTolerances = listOf(null, 45.0), margins = listOf(1.0), noiseFor = exact,
        )
        assertEquals(listOf(null, 45.0), rows.map { it.headingToleranceDeg })
        assertEquals("", rows[0].csv().split(",")[1])
        assertEquals("45.0000", rows[1].csv().split(",")[1])
        assertEquals("heading_tolerance_deg", SweepRow.CSV_HEADER.split(",")[1])
    }
}
