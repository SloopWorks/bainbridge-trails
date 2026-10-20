package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Matcher
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.coverage.Xy
import com.bainbridgetrails.coverage.filterFixes
import java.util.Locale
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
        val lead = scoredCoveredM(ds, index, index.coverFixes(s.fixes.take(s.leadInFixes)))
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

    /**
     * Trail T straight 1000 m at y=0; competitor X (connector, not included, no trails) parallel at y=15; competitor Z
     * (informal) far away at y=300 (never within 30 m of a trail tick, so not an off-network chain).
     */
    private fun offNetDataset(withCompetitors: Boolean): Dataset {
        val proj = LocalProjection()
        fun seg(id: String, y: Double, included: Boolean, trails: List<String>, category: String) = SegmentGeom(
            id, 1000.0, included, trails,
            listOf(0.0, 500.0, 1000.0).map { x -> proj.toLonLat(Xy(x, y)).let { listOf(it.first, it.second) } }, category,
        )
        val t = seg("t1", 0.0, true, listOf("T"), "agency_trail")
        val comps = listOf(seg("x1", 15.0, false, emptyList(), "connector"), seg("z1", 300.0, false, emptyList(), "informal"))
        val segs = if (withCompetitors) listOf(t) + comps else listOf(t)
        return Dataset(listOf(TrailMeta("T", "T", listOf("t1"), 0.62)), 1000.0, segs)
    }

    @Test
    fun offNetScenarioMeasuresTrailCredit() {
        val withComp = offNetDataset(true)
        val chains = offNetworkChains(withComp)
        assertEquals(1, chains.size, "only x1 lies near the trail")
        assertTrue(chains.single().all { kotlin.math.abs(it.y - 15.0) < 1e-6 }, "${chains.single()}")
        val noise = NoiseModel(5.0, outlierRate = 0.0)
        fun ratio(ds: Dataset, matcher: Matcher): Double {
            val index = SegmentIndex(ds.segments, CoverageParams(matcher = matcher))
            val (walked, credit) = (0 until 3).map { s -> offNetCreditM(index, index::coverFixes, chains, noise, 5.0, seed = 20L + s) }
                .reduce { a, b -> (a.first + b.first) to (a.second + b.second) }
            return credit / walked
        }
        // Spike 3 export shape (trail only): the walker walks where the competitor would be; COMPETITIVE credits the trail
        val compTrailOnly = ratio(offNetDataset(false), Matcher.COMPETITIVE)
        val compWith = ratio(withComp, Matcher.COMPETITIVE)
        val hmmWith = ratio(withComp, Matcher.HMM)
        println(String.format(Locale.ROOT, "OffNet: COMPETITIVE trail-only=%.3f COMPETITIVE with competitor=%.3f HMM with competitor=%.3f",
            compTrailOnly, compWith, hmmWith))
        assertTrue(compTrailOnly > 0.5, "COMPETITIVE trail-only $compTrailOnly")
        assertTrue(hmmWith < 0.1, "HMM with competitor $hmmWith")
    }

    @Test
    fun matcherDimensionRowsAndCsv() {
        val rows = sweep(
            offNetDataset(true), buffers = listOf(20.0), sigmas = listOf(0.0), intervals = listOf(1.0), seeds = 1,
            headingTolerances = listOf(null), margins = listOf(1.0), noiseFor = exact,
            matchers = listOf(Matcher.COMPETITIVE, Matcher.HMM), hmmSigmas = listOf(8.0), hmmBetas = listOf(4.0), offNetworkMs = listOf(20.0),
        )
        assertEquals(listOf(Matcher.COMPETITIVE, Matcher.HMM), rows.map { it.matcher })
        val header = SweepRow.CSV_HEADER.split(",")
        val newCols = listOf("matcher", "emission_sigma_m", "transition_beta_m", "off_network_m", "off_net_credit_ratio")
        assertEquals(newCols, header.takeLast(5))
        assertEquals("buffer_m", header.first())
        assertEquals("drive_credit_m", header[header.size - 6])
        val comp = rows[0].csv().split(",")
        val hmm = rows[1].csv().split(",")
        assertEquals(header.size, comp.size)
        assertEquals(header.size, hmm.size)
        assertEquals(listOf("competitive", "", "", ""), comp.takeLast(5).take(4))
        assertEquals(listOf("hmm", "8.0000", "4.0000", "20.0000"), hmm.takeLast(5).take(4))
        assertEquals(null, rows[0].emissionSigmaM)
        // at sigma 0 the walker on x1 (15 m off) is matched to x1 by both matchers: no trail credit
        assertEquals(0.0, rows[0].offNetCreditRatio, "${rows[0]}")
        assertEquals(0.0, rows[1].offNetCreditRatio, "${rows[1]}")
    }

    @Test
    fun creditOnCompetitorSegmentsIsNotFalseCredit() {
        // margin 20 >= 15 m offset: walking T also credits competitor x1, which no user sees (R3)
        val ds = offNetDataset(true)
        val params = CoverageParams(matcher = Matcher.COMPETITIVE, bufferM = 20.0, matchMarginM = 20.0)
        val index = SegmentIndex(ds.segments, params)
        val fixes = Simulator(ds, 1).walkTrail("T", exact(0.0), 1.0)
        // raw matcher credits x1; coverFixes drops it (scored-only, I1)
        val state = index.cover(filterFixes(fixes, params, LocalProjection()))
        assertTrue(state.coveredM(index, "x1") > 500.0, "x1 is credited")
        assertEquals(0.0, index.coverFixes(fixes).coveredM(index, "x1"))
        val r = sweep(ds, buffers = listOf(20.0), sigmas = listOf(0.0), intervals = listOf(1.0), seeds = 1,
            headingTolerances = listOf(null), margins = listOf(20.0), noiseFor = exact).single()
        assertEquals(0.0, r.falseCreditRatio, "$r")
    }

    @Test
    fun sharedHmmMatchesPerParamsIndex() {
        // shared index is built with default bufferM 30; the config's own bufferM / sigma / beta must still apply
        val ds = syntheticDataset()
        val shared = SharedHmm(ds.segments)
        for (p in listOf(
            CoverageParams(matcher = Matcher.HMM, bufferM = 15.0, emissionSigmaM = 6.0, transitionBetaM = 3.0, offNetworkM = 12.0),
            CoverageParams(matcher = Matcher.HMM, smoothWindowS = 0.0),
        )) {
            val own = SegmentIndex(ds.segments, p)
            for (seed in 0L until 3L) for (t in ds.trails) {
                val fixes = Simulator(ds, seed).walkTrail(t.id, NoiseModel(12.0), 5.0)
                val a = shared.coverer(p)(fixes)
                val b = own.coverFixes(fixes)
                for (s in ds.segments) assertEquals(b.covered(s.id), a.covered(s.id), "$p seed $seed trail ${t.id} seg ${s.id}")
            }
        }
    }

    @Test
    fun vehicleCreditOnCompetitorSegmentsIsNotCounted() {
        // riding t1 with margin 20 >= 15 m also credits competitor x1; only the scored trail counts (R7)
        val ds = offNetDataset(true)
        val params = CoverageParams(matcher = Matcher.COMPETITIVE, bufferM = 20.0, matchMarginM = 20.0)
        val index = SegmentIndex(ds.segments, params)
        val noise = NoiseModel(0.0, outlierRate = 0.0)
        val s = Simulator(ds, 9).traverseSessions(listOf("t1"), noise, 1.0, 1.3, VEHICLE_LEAD_IN_WALK_S).single()
        assertTrue(index.cover(filterFixes(s.fixes, params, LocalProjection())).coveredM(index, "x1") > 500.0, "x1 is credited (raw)")
        val lead = scoredCoveredM(ds, index, index.coverFixes(s.fixes.take(s.leadInFixes)))
        val (_, credit) = vehicleCreditM(ds, index, listOf("t1"), noise, 1.0, 1.3, seed = 9)
        assertTrue(abs(lead + credit - 1000.0) <= CoverageParams().tickM, "lead $lead credit $credit")
    }

    @Test
    fun hmmRowsIgnoreHeadingAndMargin() {
        val rows = sweep(
            offNetDataset(true), buffers = listOf(20.0), sigmas = listOf(0.0), intervals = listOf(1.0), seeds = 1,
            headingTolerances = listOf(null, 30.0), margins = listOf(0.0, 1.0), noiseFor = exact,
            matchers = listOf(Matcher.COMPETITIVE, Matcher.HMM), hmmSigmas = listOf(8.0, 8.0, 10.0), hmmBetas = listOf(4.0), offNetworkMs = listOf(20.0),
        )
        val (comp, hmm) = rows.partition { it.matcher == Matcher.COMPETITIVE }
        assertEquals(4, comp.size) // 2 headings x 2 margins
        assertEquals(listOf(8.0, 10.0), hmm.map { it.emissionSigmaM }) // one row per HMM config, duplicates dropped
        val header = SweepRow.CSV_HEADER.split(",")
        for (r in hmm) {
            assertEquals(null, r.headingToleranceDeg)
            assertEquals(null, r.matchMarginM)
            val c = r.csv().split(",")
            assertEquals("", c[header.indexOf("heading_tolerance_deg")])
            assertEquals("", c[header.indexOf("match_margin_m")])
        }
        assertEquals(listOf(0.0, 1.0, 0.0, 1.0), comp.map { it.matchMarginM })
    }

    @Test
    fun offNetRatioIsNaNWithoutChains() {
        val r = row(straightDataset(), margin = 1.0).single()
        assertTrue(r.offNetCreditRatio.isNaN(), "$r")
        assertEquals("", r.csv().split(",").last())
    }
}
