package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.HmmMatcher
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Matcher
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TickGraph
import com.bainbridgetrails.coverage.Xy
import com.bainbridgetrails.coverage.filterFixes
import com.bainbridgetrails.coverage.resample
import java.util.Locale
import java.util.stream.IntStream
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.streams.toList

/**
 * One parameter set x noise setting, aggregated over every trail x seed ("walk").
 *
 * - p5/meanTrueFraction: walked trail's fraction; p5 = sorted[floor(0.05 * (n - 1))] (lower empirical quantile, no
 *   interpolation).
 * - completeRate: share of walks with fraction >= threshold.
 * "Scored" segments ([isScored]: included, or on some trail) are the ones a user can see credit on; credit on
 * competitor segments (neither) is harmless and is not counted as false credit.
 * - falseCreditRatio: pooled sum(covered m on scored segments not in the walked trail) / sum(walked trail m).
 * - falseCompletes: total over walks of trails sharing no segment with the walked trail that reach COMPLETE (trails
 *   consist of scored segments only).
 * - standCreditM: mean per walk of covered m (scored segments) from walking the last 60 s of the trail's first chain
 *   then standing 10 min, minus the same walk without the stand.
 * - bikeCreditM / driveCreditM: mean per seed of total covered m (all segments) from walking 30 s then riding 6 / 13 m/s
 *   along every vehicle chain, minus the 30 s walk's own coverage; [vehicleChains] = chains (= transitions) per run.
 * - offNetCreditRatio: walking every [offNetworkChains] chain (each its own recording), pooled over chains and seeds:
 *   covered m on scored segments / walked chain m.
 * - emissionSigmaM / transitionBetaM / offNetworkM: the HMM parameters; null (empty in the CSV) on COMPETITIVE rows.
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
    val matcher: Matcher,
    val emissionSigmaM: Double?,
    val transitionBetaM: Double?,
    val offNetworkM: Double?,
    val offNetCreditRatio: Double,
) {
    fun csv(): String = listOf(
        f(bufferM), headingToleranceDeg?.let { f(it) } ?: "", f(matchMarginM), f(smoothWindowS), f(threshold), f(sigmaM), f(intervalS), walks.toString(),
        f(p5TrueFraction), f(meanTrueFraction), f(completeRate), f(falseCreditRatio), falseCompletes.toString(),
        f(standCreditM), vehicleChains.toString(), f(bikeCreditM), f(driveCreditM),
        matcher.name.lowercase(), opt(emissionSigmaM), opt(transitionBetaM), opt(offNetworkM), f(offNetCreditRatio),
    ).joinToString(",")

    companion object {
        const val CSV_HEADER = "buffer_m,heading_tolerance_deg,match_margin_m,smooth_window_s,threshold,sigma_m,interval_s,walks,p5_true_fraction," +
            "mean_true_fraction,complete_rate,false_credit_ratio,false_completes,stand_credit_m,vehicle_chains," +
            "bike_credit_m,drive_credit_m,matcher,emission_sigma_m,transition_beta_m,off_network_m,off_net_credit_ratio"
        private fun f(v: Double) = String.format(Locale.ROOT, "%.4f", v)
        private fun opt(v: Double?) = v?.let { f(it) } ?: ""
    }
}

const val VEHICLE_CATEGORY = "multi_use_path"
private const val BIKE_MPS = 6.0
private const val DRIVE_MPS = 13.0
internal const val VEHICLE_LEAD_IN_WALK_S = 30.0

/** A segment whose credit a user can see: included in the island total, or part of some trail. */
fun isScored(s: SegmentGeom): Boolean = s.included || s.trailIds.isNotEmpty()

/** Competitor categories walked by the off-network scenario. */
val OFF_NETWORK_CATEGORIES = setOf("connector", "informal")

/**
 * Off-network scenario chains: competitor segments (not included, no trails, category in [OFF_NETWORK_CATEGORIES]),
 * chained like a trail ([Simulator.chains]); a chain is kept if >= [nearShare] of its ticks (every [tickM]) lie within
 * [nearM] of some scored segment's tick. In local metres of [proj].
 */
fun offNetworkChains(
    dataset: Dataset, proj: LocalProjection = LocalProjection(), tickM: Double = CoverageParams().tickM,
    nearM: Double = 30.0, nearShare: Double = 0.5,
): List<List<Xy>> {
    val ids = dataset.segments
        .filter { !it.included && it.trailIds.isEmpty() && it.category in OFF_NETWORK_CATEGORIES }
        .map { it.id }
    if (ids.isEmpty()) return emptyList()
    fun cell(v: Double) = floor(v / nearM).toInt()
    fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
    val grid = HashMap<Long, MutableList<Xy>>()
    for (s in dataset.segments) {
        if (!isScored(s)) continue
        val line = s.coords.map { proj.toXy(it[0], it[1]) }
        if (line.size < 2) continue
        for (t in resample(line, tickM)) grid.getOrPut(key(cell(t.p.x), cell(t.p.y))) { ArrayList() } += t.p
    }
    fun near(p: Xy): Boolean {
        for (cx in cell(p.x) - 1..cell(p.x) + 1) for (cy in cell(p.y) - 1..cell(p.y) + 1) {
            for (q in grid[key(cx, cy)] ?: continue) if (hypot(q.x - p.x, q.y - p.y) <= nearM) return true
        }
        return false
    }
    return Simulator(dataset, 0L, proj).chains(ids).filter { chain ->
        val ticks = resample(chain, tickM)
        ticks.isNotEmpty() && ticks.count { near(it.p) } >= nearShare * ticks.size
    }
}

/**
 * Walk every chain with [noise] (each chain its own recording, seeded from [seed] and its index) and cover it with
 * [cover]. Returns (walked chain m, covered m on scored segments of [index]); chains run in parallel, summed in order.
 */
fun offNetCreditM(
    index: SegmentIndex, cover: (List<Fix>) -> CoverageState, chains: List<List<Xy>>, noise: NoiseModel,
    intervalS: Double, seed: Long, maxGapS: Double = CoverageParams().maxGapS, proj: LocalProjection = LocalProjection(),
): Pair<Double, Double> {
    val scored = index.segments.values.filter(::isScored).sortedBy { it.id }
    val empty = Dataset(emptyList(), 0.0, emptyList())
    val credits = IntStream.range(0, chains.size).parallel().mapToObj { c ->
        val fixes = Simulator(empty, mix(seed + c * GOLDEN), proj, maxGapS).walkChain(chains[c], noise, intervalS)
        val state = cover(fixes)
        scored.sumOf { coveredClampedM(index, state, it.id) }
    }.toList()
    val walked = chains.sumOf { ch -> ch.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum() }
    return walked to credits.sum()
}

/** Segments a bike / car could follow: those with category [VEHICLE_CATEGORY]. */
fun vehicleSegmentIds(dataset: Dataset): List<String> =
    dataset.segments.filter { it.category == VEHICLE_CATEGORY }.map { it.id }

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
    /** sigma -> noise model; override e.g. to switch outliers off for known-answer tests */
    noiseFor: (Double) -> NoiseModel = { NoiseModel(it) },
    log: (String) -> Unit = {},
    matchers: List<Matcher> = listOf(Matcher.COMPETITIVE),
    /** HMM rows only (COMPETITIVE rows use the defaults and report them empty) */
    hmmSigmas: List<Double> = listOf(CoverageParams().emissionSigmaM),
    hmmBetas: List<Double> = listOf(CoverageParams().transitionBetaM),
    offNetworkMs: List<Double> = listOf(CoverageParams().offNetworkM),
): List<SweepRow> {
    require(seeds >= 1) { "seeds must be >= 1" }
    val proj = LocalProjection()
    val defaults = CoverageParams()
    val vehicleIds = vehicleSegmentIds(dataset)
    val trails = dataset.trails
    val trailSegs = trails.map { it.segmentIds.toSet() }
    // trails sharing no segment with trail i
    val disjoint = trails.indices.map { i -> trails.indices.filter { j -> j != i && trailSegs[i].none { it in trailSegs[j] } } }
    val scored = dataset.segments.filter(::isScored)
    val offChains = offNetworkChains(dataset, proj, defaults.tickM)
    val hmmPerSet = hmmSigmas.size * hmmBetas.size * offNetworkMs.size
    val configsPerSet = matchers.distinct().sumOf { if (it == Matcher.HMM) hmmPerSet else 1 }
    val total = buffers.size * headingTolerances.size * margins.size * smoothWindows.size * configsPerSet * sigmas.size * intervals.size
    log("off-network chains: ${offChains.size}")
    val sharedHmm = SharedHmm(dataset.segments, proj)
    val rows = ArrayList<SweepRow>()
    val t0 = System.nanoTime()

    for (b in buffers) for (h in headingTolerances) for (m in margins) for (sw in smoothWindows) {
        val base = defaults.copy(
            bufferM = b,
            headingToleranceDeg = h,
            matchMarginM = m,
            smoothWindowS = sw,
            completionThreshold = threshold,
        )
        val configs = matchers.distinct().flatMap { mt ->
            when (mt) {
                Matcher.COMPETITIVE -> listOf(base)
                Matcher.HMM -> hmmSigmas.flatMap { es -> hmmBetas.flatMap { tb -> offNetworkMs.map { off ->
                    base.copy(matcher = Matcher.HMM, emissionSigmaM = es, transitionBetaM = tb, offNetworkM = off)
                } } }
            }
        }
        for (params in configs) {
            val index: SegmentIndex
            val cover: (List<Fix>) -> CoverageState
            when (params.matcher) {
                Matcher.COMPETITIVE -> {
                    index = SegmentIndex(dataset.segments, params, proj) // built once per param set
                    cover = index::coverFixes
                }
                Matcher.HMM -> {
                    index = sharedHmm.index
                    cover = sharedHmm.coverer(params)
                }
            }
            val isHmm = params.matcher == Matcher.HMM
            val progress = Progress(dataset, index, params)
            val trailLenM = trails.map { t -> t.segmentIds.distinct().sumOf { index.segments[it]?.lengthM ?: 0.0 } }
            fun coveredM(state: CoverageState, id: String): Double = coveredClampedM(index, state, id)
            fun scoredCoveredM(state: CoverageState): Double = scored.sumOf { coveredM(state, it.id) }

            for (sigma in sigmas) for (interval in intervals) {
                val noise = noiseFor(sigma)
                val walks = IntStream.range(0, trails.size * seeds).parallel().mapToObj { w ->
                    val ti = w / seeds
                    val si = w % seeds
                    val trail = trails[ti]
                    val fixes = Simulator(dataset, seedOf(ti, si, 0), proj, params.maxGapS).walkTrail(trail.id, noise, interval)
                    val state = cover(fixes)
                    val frac = progress.trailFraction(state, trail.id)
                    val falseM = scored.sumOf { if (it.id in trailSegs[ti]) 0.0 else coveredM(state, it.id) }
                    val falseCompletes = disjoint[ti].count { j -> progress.trailFraction(state, trails[j].id) >= threshold }
                    val stand = Simulator(dataset, seedOf(ti, si, 1), proj, params.maxGapS).standScenario(trail.id, noise, interval)
                    val standCredit = scoredCoveredM(cover(stand.fixes)) -
                        scoredCoveredM(cover(stand.fixes.take(stand.leadInFixes)))
                    Walk(frac, falseM, trailLenM[ti], falseCompletes, standCredit)
                }.toList()

                fun vehicleCredit(speed: Double, salt: Int): Pair<Int, Double> {
                    val perSeed = (0 until seeds).map { si ->
                        vehicleCreditM(dataset, index, vehicleIds, noise, interval, speed, seedOf(-1, si, salt), params.maxGapS, cover)
                    }
                    return perSeed.first().first to perSeed.map { it.second }.average()
                }
                val (chains, bike) = vehicleCredit(BIKE_MPS, 2)
                val (_, drive) = vehicleCredit(DRIVE_MPS, 3)
                val offNet = (0 until seeds).map { si -> offNetCreditM(index, cover, offChains, noise, interval, seedOf(-1, si, 4), params.maxGapS, proj) }
                val offWalkedM = offNet.sumOf { it.first }

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
                    matcher = params.matcher,
                    emissionSigmaM = params.emissionSigmaM.takeIf { isHmm },
                    transitionBetaM = params.transitionBetaM.takeIf { isHmm },
                    offNetworkM = params.offNetworkM.takeIf { isHmm },
                    offNetCreditRatio = if (offWalkedM > 0.0) offNet.sumOf { it.second } / offWalkedM else 0.0,
                )
                rows += row
                log(String.format(Locale.ROOT, "[%d/%d] %.1fs %s", rows.size, total, (System.nanoTime() - t0) / 1e9, row.csv()))
            }
        }
    }
    return rows
}

/**
 * One [SegmentIndex] + [TickGraph] shared by every HMM config. Index geometry depends only on tickM / headingBaselineM,
 * graph topology on that plus junctionM; none of them is swept, so each config only gets its own (cheap) [HmmMatcher].
 * Equivalent to `SegmentIndex(segments, params).coverFixes` with matcher HMM (HmmMatcher reads bufferM etc. from its
 * own params, never the index's).
 */
internal class SharedHmm(segments: List<SegmentGeom>, private val proj: LocalProjection = LocalProjection()) {
    private val base = CoverageParams()
    val index: SegmentIndex by lazy { SegmentIndex(segments, base, proj) }
    private val graph: TickGraph by lazy { TickGraph(index, base.junctionM) }

    fun coverer(params: CoverageParams): (List<Fix>) -> CoverageState {
        require(params.matcher == Matcher.HMM) { "HMM params expected" }
        require(params.tickM == base.tickM && params.headingBaselineM == base.headingBaselineM && params.junctionM == base.junctionM) {
            "shared index/graph built for default tickM, headingBaselineM, junctionM"
        }
        val hmm = HmmMatcher(index, graph, params)
        return { fixes -> hmm.cover(filterFixes(fixes, params, proj)) }
    }
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
    cover: (List<Fix>) -> CoverageState = index::coverFixes,
): Pair<Int, Double> {
    val sessions = Simulator(dataset, seed, maxGapS = maxGapS)
        .traverseSessions(segmentIds, noise, intervalS, speedMps, VEHICLE_LEAD_IN_WALK_S)
    val credits = sessions.parallelStream().map { s ->
        totalCoveredM(dataset, index, cover(s.fixes)) -
            totalCoveredM(dataset, index, cover(s.fixes.take(s.leadInFixes)))
    }.toList()
    return sessions.size to credits.sum()
}

private class Walk(val frac: Double, val falseM: Double, val walkedM: Double, val falseCompletes: Int, val standCreditM: Double)

/** Seed from (trail index, seed index, scenario); trail -1 = whole-network scenarios. Same across noise settings. */
private fun seedOf(trail: Int, seed: Int, scenario: Int): Long {
    // splitmix64 finalizer: java.util.Random streams from adjacent raw seeds are correlated
    return mix((trail.toLong() + 1) * 1_000_003L + seed.toLong() * 7_919L + scenario.toLong() * 104_729L)
}

private const val GOLDEN = -0x61c8864680b583ebL

private fun mix(z0: Long): Long {
    var z = z0
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}
