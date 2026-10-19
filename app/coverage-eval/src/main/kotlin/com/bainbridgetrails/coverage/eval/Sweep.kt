package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentIndex
import java.util.Locale
import java.util.stream.IntStream
import kotlin.streams.toList

/**
 * One parameter set x noise setting, aggregated over every trail x seed ("walk").
 *
 * - p5/meanTrueFraction: walked trail's fraction; p5 = sorted[floor(0.05 * (n - 1))] (lower empirical quantile, no
 *   interpolation).
 * - completeRate: share of walks with fraction >= threshold.
 * - falseCreditRatio: pooled sum(covered m on segments not in the walked trail) / sum(walked trail m).
 * - falseCompletes: total over walks of trails sharing no segment with the walked trail that reach COMPLETE.
 * - standCreditM: mean per walk of covered m (all segments) from walking the last 60 s of the trail's first chain then
 *   standing 10 min, minus the same walk without the stand.
 * - bikeCreditM / driveCreditM: mean per seed of total covered m (all segments) from walking 30 s then riding 6 / 13 m/s
 *   along every vehicle chain, minus the 30 s walk's own coverage; [vehicleChains] = chains (= transitions) per run.
 */
data class SweepRow(
    val bufferM: Double,
    /** null = heading check off */
    val headingToleranceDeg: Double?,
    val matchMarginM: Double,
    /** filterFixes rule 6 window; 0 = smoothing off */
    val smoothWindowS: Double,
    val threshold: Double,
    val sigmaM: Double,
    val intervalS: Double,
    val walks: Int,
    val p5TrueFraction: Double,
    val meanTrueFraction: Double,
    val completeRate: Double,
    val falseCreditRatio: Double,
    val falseCompletes: Int,
    val standCreditM: Double,
    val vehicleChains: Int,
    val bikeCreditM: Double,
    val driveCreditM: Double,
) {
    fun csv(): String = listOf(
        f(bufferM), headingToleranceDeg?.let { f(it) } ?: "", f(matchMarginM), f(smoothWindowS), f(threshold), f(sigmaM), f(intervalS), walks.toString(),
        f(p5TrueFraction), f(meanTrueFraction), f(completeRate), f(falseCreditRatio), falseCompletes.toString(),
        f(standCreditM), vehicleChains.toString(), f(bikeCreditM), f(driveCreditM),
    ).joinToString(",")

    companion object {
        const val CSV_HEADER = "buffer_m,heading_tolerance_deg,match_margin_m,smooth_window_s,threshold,sigma_m,interval_s,walks,p5_true_fraction," +
            "mean_true_fraction,complete_rate,false_credit_ratio,false_completes,stand_credit_m,vehicle_chains," +
            "bike_credit_m,drive_credit_m"
        private fun f(v: Double) = String.format(Locale.ROOT, "%.4f", v)
    }
}

const val VEHICLE_CATEGORY = "multi_use_path"
private const val PROXY_TRAILS = 10
private const val BIKE_MPS = 6.0
private const val DRIVE_MPS = 13.0
internal const val VEHICLE_LEAD_IN_WALK_S = 30.0

/**
 * Segments a bike / car could follow: those with category [VEHICLE_CATEGORY] in [categories] (id -> category, e.g. from
 * segments.tiles.geojson). SegmentGeom has no category, so with no categories the proxy is the segments of the
 * [PROXY_TRAILS] longest trails.
 */
fun vehicleSegmentIds(dataset: Dataset, categories: Map<String, String>): List<String> =
    if (categories.isNotEmpty()) {
        dataset.segments.filter { categories[it.id] == VEHICLE_CATEGORY }.map { it.id }
    } else {
        dataset.trails.sortedByDescending { it.computedLengthMi }.take(PROXY_TRAILS).flatMap { it.segmentIds }.distinct()
    }

fun sweep(
    dataset: Dataset,
    buffers: List<Double>,
    sigmas: List<Double>,
    intervals: List<Double>,
    seeds: Int,
    /** heading tolerances to sweep; null = heading check off */
    headingTolerances: List<Double?> = listOf(null, 30.0, 45.0, 60.0),
    margins: List<Double> = listOf(CoverageParams().matchMarginM),
    /** track smoothing windows (s) to sweep; 0 = off */
    smoothWindows: List<Double> = listOf(CoverageParams().smoothWindowS),
    threshold: Double = 0.90,
    categories: Map<String, String> = emptyMap(),
    /** sigma -> noise model; override e.g. to switch outliers off for known-answer tests */
    noiseFor: (Double) -> NoiseModel = { NoiseModel(it) },
    log: (String) -> Unit = {},
): List<SweepRow> {
    require(seeds >= 1) { "seeds must be >= 1" }
    val proj = LocalProjection()
    val defaults = CoverageParams()
    val vehicleIds = vehicleSegmentIds(dataset, categories)
    val trails = dataset.trails
    val trailSegs = trails.map { it.segmentIds.toSet() }
    // trails sharing no segment with trail i
    val disjoint = trails.indices.map { i -> trails.indices.filter { j -> j != i && trailSegs[i].none { it in trailSegs[j] } } }
    val total = buffers.size * headingTolerances.size * margins.size * smoothWindows.size * sigmas.size * intervals.size
    val rows = ArrayList<SweepRow>()
    val t0 = System.nanoTime()

    for (b in buffers) for (h in headingTolerances) for (m in margins) for (sw in smoothWindows) {
        val params = defaults.copy(
            bufferM = b,
            headingToleranceDeg = h,
            matchMarginM = m,
            smoothWindowS = sw,
            completionThreshold = threshold,
        )
        val index = SegmentIndex(dataset.segments, params, proj) // built once per param set
        val progress = Progress(dataset, index, params)
        val trailLenM = trails.map { t -> t.segmentIds.distinct().sumOf { index.segments[it]?.lengthM ?: 0.0 } }
        fun coveredM(state: CoverageState, id: String): Double = coveredClampedM(index, state, id)
        fun totalCoveredM(state: CoverageState): Double = totalCoveredM(dataset, index, state)

        for (sigma in sigmas) for (interval in intervals) {
            val noise = noiseFor(sigma)
            val walks = IntStream.range(0, trails.size * seeds).parallel().mapToObj { w ->
                val ti = w / seeds
                val si = w % seeds
                val trail = trails[ti]
                val fixes = Simulator(dataset, seedOf(ti, si, 0), proj, params.maxGapS).walkTrail(trail.id, noise, interval)
                val state = index.coverFixes(fixes)
                val frac = progress.trailFraction(state, trail.id)
                val falseM = dataset.segments.sumOf { if (it.id in trailSegs[ti]) 0.0 else coveredM(state, it.id) }
                val falseCompletes = disjoint[ti].count { j -> progress.trailFraction(state, trails[j].id) >= threshold }
                val stand = Simulator(dataset, seedOf(ti, si, 1), proj, params.maxGapS).standScenario(trail.id, noise, interval)
                val standCredit = totalCoveredM(index.coverFixes(stand.fixes)) -
                    totalCoveredM(index.coverFixes(stand.fixes.take(stand.leadInFixes)))
                Walk(frac, falseM, trailLenM[ti], falseCompletes, standCredit)
            }.toList()

            fun vehicleCredit(speed: Double, salt: Int): Pair<Int, Double> {
                val perSeed = (0 until seeds).map { si ->
                    vehicleCreditM(dataset, index, vehicleIds, noise, interval, speed, seedOf(-1, si, salt), params.maxGapS)
                }
                return perSeed.first().first to perSeed.map { it.second }.average()
            }
            val (chains, bike) = vehicleCredit(BIKE_MPS, 2)
            val (_, drive) = vehicleCredit(DRIVE_MPS, 3)

            val fr = walks.map { it.frac }.sorted()
            val row = SweepRow(
                bufferM = b, headingToleranceDeg = h, matchMarginM = m, smoothWindowS = sw, threshold = threshold, sigmaM = sigma, intervalS = interval,
                walks = walks.size,
                p5TrueFraction = fr[(0.05 * (fr.size - 1)).toInt()],
                meanTrueFraction = fr.average(),
                completeRate = walks.count { it.frac >= threshold }.toDouble() / walks.size,
                falseCreditRatio = walks.sumOf { it.falseM } / walks.sumOf { it.walkedM },
                falseCompletes = walks.sumOf { it.falseCompletes },
                standCreditM = walks.map { it.standCreditM }.average(),
                vehicleChains = chains,
                bikeCreditM = bike,
                driveCreditM = drive,
            )
            rows += row
            log(String.format(Locale.ROOT, "[%d/%d] %.1fs %s", rows.size, total, (System.nanoTime() - t0) / 1e9, row.csv()))
        }
    }
    return rows
}

internal fun coveredClampedM(index: SegmentIndex, state: CoverageState, id: String): Double =
    state.coveredM(index, id).coerceAtMost(index.segments[id]?.lengthM ?: 0.0)

/** Covered metres over all segments, summed in dataset order. */
internal fun totalCoveredM(dataset: Dataset, index: SegmentIndex, state: CoverageState): Double =
    dataset.segments.sumOf { coveredClampedM(index, state, it.id) }

/**
 * Walk [VEHICLE_LEAD_IN_WALK_S] then ride [speedMps] along every chain of [segmentIds], each chain its own recording.
 * Returns (chains, total covered m minus each lead-in walk's own coverage). Chains are covered in parallel and summed
 * sequentially in chain order, so the result does not depend on scheduling.
 */
internal fun vehicleCreditM(
    dataset: Dataset, index: SegmentIndex, segmentIds: List<String>, noise: NoiseModel, intervalS: Double,
    speedMps: Double, seed: Long, maxGapS: Double = CoverageParams().maxGapS,
): Pair<Int, Double> {
    val sessions = Simulator(dataset, seed, maxGapS = maxGapS)
        .traverseSessions(segmentIds, noise, intervalS, speedMps, VEHICLE_LEAD_IN_WALK_S)
    val credits = sessions.parallelStream().map { s ->
        totalCoveredM(dataset, index, index.coverFixes(s.fixes)) -
            totalCoveredM(dataset, index, index.coverFixes(s.fixes.take(s.leadInFixes)))
    }.toList()
    return sessions.size to credits.sum()
}

private class Walk(val frac: Double, val falseM: Double, val walkedM: Double, val falseCompletes: Int, val standCreditM: Double)

/** Seed from (trail index, seed index, scenario); trail -1 = whole-network scenarios. Same across noise settings. */
private fun seedOf(trail: Int, seed: Int, scenario: Int): Long {
    // splitmix64 finalizer: java.util.Random streams from adjacent raw seeds are correlated
    var z = (trail.toLong() + 1) * 1_000_003L + seed.toLong() * 7_919L + scenario.toLong() * 104_729L
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}
