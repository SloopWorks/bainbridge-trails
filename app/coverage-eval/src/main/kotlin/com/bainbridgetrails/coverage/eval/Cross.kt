package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.GoalRules
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.Xy
import com.bainbridgetrails.coverage.isScored
import java.io.File
import java.util.Locale

/**
 * Cross-dataset false credit: walk every trail of one dataset, score it against another.
 *
 * The roads prototype's open question (docs/roads/plan.md): the roads dataset's coverage_segments.json contains only
 * roads, so a walk on a trail that runs beside a road has no trail geometry to compete for it and the road can take
 * the credit. This measures that, and measures what changes when the walked dataset's segments are injected as
 * `included: false` competitors (what `pipeline/src/bitrails/roads/dist.py` would have to ship to fix it).
 *
 * Both runs use the app's own matcher and parameters; "credit" is covered metres on scored segments ([isScored]) of
 * the **scored** dataset, which is what a user sees painted.
 */
internal fun runCross(scoredData: Dataset, flags: Map<String, String>) {
    // The roads dataset (rseg- ids) is scored by the app's roads rules (RP-18: counted roads/segments only).
    val scored = if (scoredData.segments.any { it.id.startsWith("rseg-") }) scoredData.withRules(GoalRules.ROADS) else scoredData
    val walkDir = File(flags["walk-data"] ?: error("--walk-data is required"))
    val walked = readDataset(walkDir, flags["walk-meta"], flags["walk-segments"])
    val seeds = (flags["seeds"] ?: "3").toInt()
    val sigmas = (flags["sigmas"] ?: "5,10").split(",").map { it.trim().toDouble() }
    val intervals = (flags["intervals"] ?: "1,5").split(",").map { it.trim().toDouble() }
    val buffers = (flags["buffers"] ?: CoverageParams().bufferM.toString()).split(",").map { it.trim().toDouble() }
    val limit = (flags["limit"] ?: "0").toInt()

    val proj = LocalProjection()
    // Only the walked dataset's own counted trails: walking a competitor path is a different question (sweep's
    // off-network scenario).
    val trails = walked.trails.filter { t -> t.segmentIds.any { id -> walked.segments.firstOrNull { it.id == id }?.isScored == true } }
        .let { if (limit > 0) it.take(limit) else it }
    println("walking ${trails.size} ${walkDir.name} trails against ${scored.trails.size} scored features (${scored.version})")

    // Variant B: the walked dataset's segments as pure competitors — present for matching, never credited. A roads
    // dist built since RD-2b already ships them (same seg-* ids), so only the missing ones are added: variant B then
    // equals scored-only, which is the check that the shipped competitors are complete.
    val present = scored.segments.mapTo(HashSet()) { it.id }
    println("${walked.segments.count { it.id in present }} of ${walked.segments.size} walked segments already in the scored dataset")
    val competitors = walked.segments.filter { it.id !in present }.map { SegmentGeom(it.id, it.lengthM, included = false, trailIds = emptyList(), coords = it.coords, category = it.category) }
    val variants = listOf(
        "scored-only" to scored,
        "with-competitors" to Dataset(scored.trails, scored.islandTotalM, scored.segments + competitors, scored.version, scored.rules),
    )

    println()
    println("buffer_m,variant,sigma_m,interval_s,walks,walked_km,false_credit_km,false_credit_ratio,features_complete")
    val offenders = HashMap<String, Double>()
    for (bufferM in buffers) for ((label, ds) in variants) {
        val shared = SharedHmm(ds.segments, proj)
        val index = shared.index
        val progress = Progress(ds, index, CoverageParams(bufferM = bufferM))
        val cover = shared.coverer(CoverageParams(bufferM = bufferM, offNetworkM = minOf(30.0, bufferM)))
        for (sigmaM in sigmas) for (intervalS in intervals) {
            var walkedM = 0.0
            var falseM = 0.0
            var union = CoverageState.EMPTY
            var walks = 0
            for (seed in 0 until seeds) {
                val sim = Simulator(walked, seed.toLong() * 7919 + 13)
                for (t in trails) {
                    for (chain in sim.chains(t.segmentIds)) {
                        if (chain.size < 2) continue
                        walkedM += chainLengthM(chain)
                        val state = cover(sim.walkChain(chain, NoiseModel(sigmaM), intervalS))
                        falseM += scoredCoveredM(ds, index, state)
                        union = union.union(state)
                        walks++
                        if (label == variants.first().first && bufferM == buffers.first() && sigmaM == sigmas.first() &&
                            intervalS == intervals.first()
                        ) {
                            for (s in ds.segments) if (s.isScored) {
                                val m = coveredClampedM(index, state, s.id)
                                if (m > 0.0) offenders[s.id] = (offenders[s.id] ?: 0.0) + m
                            }
                        }
                    }
                }
            }
            // trailsCompleted only reads the scored dataset's own features, so competitor bits are ignored.
            val complete = progress.trailsCompleted(union)
            println(
                String.format(
                    Locale.ROOT, "%.0f,%s,%.0f,%.0f,%d,%.2f,%.2f,%.4f,%d",
                    bufferM, label, sigmaM, intervalS, walks, walkedM / 1000, falseM / 1000,
                    if (walkedM > 0) falseM / walkedM else 0.0, complete,
                ),
            )
        }
    }

    val names = scored.trails.flatMap { t -> t.segmentIds.map { it to t.name } }.toMap()
    println()
    println("worst falsely-painted features (scored-only, buffer ${buffers.first().toInt()} m, sigma ${sigmas.first().toInt()} m, interval ${intervals.first().toInt()} s, $seeds seeds):")
    offenders.entries.sortedByDescending { it.value }.take(15).forEach { (id, m) ->
        println(String.format(Locale.ROOT, "  %-14s %7.0f m  %s", id, m, names[id] ?: "?"))
    }
}

private fun chainLengthM(chain: List<Xy>): Double {
    var m = 0.0
    for (i in 1 until chain.size) m += kotlin.math.hypot(chain[i].x - chain[i - 1].x, chain[i].y - chain[i - 1].y)
    return m
}
