package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Xy
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS error model, correlated per unit time (ruling R10). On each axis, between fixes dt apart,
 * e = rho * e_prev + sqrt(1 - rho^2) * sigma * N(0,1) with rho = exp(-dt / tauS) (~0.951 at 1 s); started from the
 * stationary distribution at each session start. With probability [outlierRate] a fix is additionally displaced by
 * [outlierM] in a uniformly random direction. Reported accuracyM = 1.5 * sigma.
 */
data class NoiseModel(val sigmaM: Double, val tauS: Double = 20.0, val outlierRate: Double = 0.01, val outlierM: Double = 50.0)

/** One recording; [leadInFixes] = how many leading fixes were sampled in the first phase (the walk; see [Simulator.traverseSessions]). */
data class Session(val fixes: List<Fix>, val leadInFixes: Int)

/**
 * Seeded track simulator over a [Dataset]. Not thread-safe (one [Random]); create one per worker.
 *
 * A trail's segments are chained by shared endpoints (<= [CHAIN_JOIN_M] apart, greedy, each segment oriented so the chain
 * is continuous). Each chain is one session; sessions are separated by a time gap > maxGapS, so the filter breaks the
 * track there. The gap is also >= the straight-line jump / 1 m/s, so the speed check (whose reference spans the whole
 * stream) does not drop the next session's first fixes: the walker is assumed to have walked off-network in between.
 */
class Simulator(
    dataset: Dataset,
    seed: Long,
    private val proj: LocalProjection = LocalProjection(),
    private val maxGapS: Double = CoverageParams().maxGapS,
) {
    private val rng = Random(seed)
    private val segments = dataset.segments.associateBy { it.id }
    private val trails = dataset.trails.associateBy { it.id }
    private var clockMs = START_MS
    private var lastEnd: Xy? = null

    private sealed interface Phase { val durationS: Double }
    private class Move(val line: Polyline, val speedMps: Double) : Phase { override val durationS = line.lengthM / speedMps }
    private class Stand(val p: Xy, override val durationS: Double) : Phase

    /** Walk every chain of the trail, one session per chain, each in a seeded random direction at speedMps +- 0.2 (uniform). */
    fun walkTrail(trailId: String, noise: NoiseModel, intervalS: Double, speedMps: Double = 1.3): List<Fix> {
        val trail = requireNotNull(trails[trailId]) { "unknown trail $trailId" }
        val out = ArrayList<Fix>()
        for (chain in chains(trail.segmentIds)) {
            val line = Polyline(if (rng.nextBoolean()) chain.reversed() else chain)
            val v = speedMps + (rng.nextDouble() * 0.4 - 0.2)
            out += session(listOf(Move(line, v)), noise, intervalS).first
        }
        return out
    }

    /** Walk one polyline (local metres) as its own session, in a seeded random direction at speedMps +- 0.2 (uniform). */
    fun walkChain(chain: List<Xy>, noise: NoiseModel, intervalS: Double, speedMps: Double = 1.3): List<Fix> {
        val line = Polyline(if (rng.nextBoolean()) chain.reversed() else chain)
        val v = speedMps + (rng.nextDouble() * 0.4 - 0.2)
        return session(listOf(Move(line, v)), noise, intervalS).first
    }

    /** All chains of [segmentIds] at [speedMps] (no lead-in), one continuous fix stream with session gaps. */
    fun traverse(segmentIds: List<String>, noise: NoiseModel, intervalS: Double, speedMps: Double): List<Fix> =
        traverseSessions(segmentIds, noise, intervalS, speedMps, leadInWalkS = 0.0).flatMap { it.fixes }

    /**
     * One session per chain of [segmentIds], seeded random direction: walk [leadInWalkS] at [walkMps] along the chain
     * start, then [speedMps] for the rest (a walker who mounts a bike / gets in a car mid-recording). Sessions share
     * one clock, so concatenating them is a valid stream; each can also be covered as its own recording.
     */
    fun traverseSessions(
        segmentIds: List<String>, noise: NoiseModel, intervalS: Double, speedMps: Double,
        leadInWalkS: Double, walkMps: Double = 1.3,
    ): List<Session> = chains(segmentIds).map { chain ->
        val line = Polyline(if (rng.nextBoolean()) chain.reversed() else chain)
        val walkM = minOf(line.lengthM, leadInWalkS * walkMps)
        val phases = ArrayList<Phase>()
        if (walkM > 0.0) phases += Move(line.sub(0.0, walkM), walkMps)
        if (line.lengthM > walkM) phases += Move(line.sub(walkM, line.lengthM), speedMps)
        val (fixes, firstPhase) = session(phases, noise, intervalS)
        Session(fixes, if (walkM > 0.0) firstPhase else 0)
    }

    /**
     * The trail's first chain in a seeded random direction: walk the last [walkS] up to its end, then stand at the end for
     * [standS] with the noise model's jitter. [Session.leadInFixes] = the walk's fixes (the same session without the stand).
     */
    fun standScenario(trailId: String, noise: NoiseModel, intervalS: Double, walkS: Double = 60.0, standS: Double = 600.0,
                      speedMps: Double = 1.3): Session {
        val trail = requireNotNull(trails[trailId]) { "unknown trail $trailId" }
        val chain = chains(trail.segmentIds).first()
        val line = Polyline(if (rng.nextBoolean()) chain.reversed() else chain)
        val walk = line.sub(max(0.0, line.lengthM - walkS * speedMps), line.lengthM)
        val (fixes, walkFixes) = session(listOf(Move(walk, speedMps), Stand(walk.end, standS)), noise, intervalS)
        return Session(fixes, walkFixes)
    }

    /** Greedy endpoint chaining (ruling R8): each chain is a continuous polyline in local metres. */
    fun chains(segmentIds: List<String>): List<List<Xy>> {
        val unused = segmentIds.distinct().mapNotNull { segments[it] }
            .map { s -> s.coords.map { proj.toXy(it[0], it[1]) } }
            .filter { it.size >= 2 }
            .toMutableList()
        val out = ArrayList<List<Xy>>()
        while (unused.isNotEmpty()) {
            val chain = ArrayDeque(unused.removeAt(0))
            var grew = true
            while (grew) {
                grew = false
                for (i in unused.indices) {
                    val l = unused[i]
                    val joined = when {
                        near(chain.last(), l.first()) -> { chain.addAll(l.drop(1)); true }
                        near(chain.last(), l.last()) -> { chain.addAll(l.reversed().drop(1)); true }
                        near(chain.first(), l.last()) -> { l.dropLast(1).asReversed().forEach { chain.addFirst(it) }; true }
                        near(chain.first(), l.first()) -> { l.drop(1).forEach { chain.addFirst(it) }; true }
                        else -> false
                    }
                    if (joined) { unused.removeAt(i); grew = true; break }
                }
            }
            out += chain.toList()
        }
        return out
    }

    private fun near(a: Xy, b: Xy) = hypot(a.x - b.x, a.y - b.y) <= CHAIN_JOIN_M

    /**
     * Samples the phases every intervalS (plus the final instant) with correlated noise; advances the shared clock.
     * Returns the fixes and how many of them were sampled in phases[0] (a phase owns the samples with t <= its end).
     */
    private fun session(phases: List<Phase>, noise: NoiseModel, intervalS: Double): Pair<List<Fix>, Int> {
        require(intervalS > 0.0) { "intervalS must be > 0" }
        val start = when (val p = phases.first()) { is Move -> p.line.start; is Stand -> p.p }
        lastEnd?.let { prev ->
            val gapS = max(maxGapS + SESSION_GAP_EXTRA_S, hypot(start.x - prev.x, start.y - prev.y) / OFF_NETWORK_MPS)
            clockMs += (gapS * 1000).toLong()
        }
        val total = phases.sumOf { it.durationS }
        val firstEnd = phases.first().durationS
        val sigma = noise.sigmaM
        var firstPhase = 0
        var prevT = 0.0
        var ex = sigma * rng.nextGaussian()
        var ey = sigma * rng.nextGaussian()
        val out = ArrayList<Fix>()
        var k = 0
        while (true) {
            val t = minOf(k * intervalS, total)
            val p = trueAt(phases, t)
            if (k > 0) {
                val rho = exp(-(t - prevT) / noise.tauS)
                val innov = sqrt(1 - rho * rho) * sigma
                ex = rho * ex + innov * rng.nextGaussian()
                ey = rho * ey + innov * rng.nextGaussian()
            }
            if (t <= firstEnd) firstPhase++
            prevT = t
            var x = p.x + ex
            var y = p.y + ey
            if (rng.nextDouble() < noise.outlierRate) {
                val a = rng.nextDouble() * 2 * PI
                x += noise.outlierM * cos(a)
                y += noise.outlierM * sin(a)
            }
            val (lon, lat) = proj.toLonLat(Xy(x, y))
            out += Fix(clockMs + (t * 1000).toLong(), lon, lat, accuracyM = 1.5 * sigma)
            if (t >= total) break
            k++
        }
        clockMs += (total * 1000).toLong()
        lastEnd = trueAt(phases, total)
        return out to firstPhase
    }

    private fun trueAt(phases: List<Phase>, t: Double): Xy {
        var rem = t
        for ((i, ph) in phases.withIndex()) {
            if (rem <= ph.durationS || i == phases.lastIndex) {
                val r = minOf(rem, ph.durationS)
                return when (ph) {
                    is Move -> ph.line.at(r * ph.speedMps)
                    is Stand -> ph.p
                }
            }
            rem -= ph.durationS
        }
        error("no phases")
    }

    private companion object {
        const val START_MS = 1_760_000_000_000L
        const val CHAIN_JOIN_M = 1.0
        const val SESSION_GAP_EXTRA_S = 60.0
        const val OFF_NETWORK_MPS = 1.0
    }
}

/** Polyline with cumulative lengths; [at] / [sub] take distances along it. */
internal class Polyline(val pts: List<Xy>) {
    private val cum = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
    }
    val lengthM: Double get() = cum.last()
    val start: Xy get() = pts.first()
    val end: Xy get() = pts.last()

    fun at(d: Double): Xy {
        if (pts.size == 1 || d <= 0.0) return pts.first()
        if (d >= lengthM) return pts.last()
        var i = cum.binarySearch(d).let { if (it >= 0) it else -it - 2 }
        i = i.coerceIn(0, pts.size - 2)
        val seg = cum[i + 1] - cum[i]
        val f = if (seg == 0.0) 0.0 else (d - cum[i]) / seg
        val a = pts[i]
        val b = pts[i + 1]
        return Xy(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y))
    }

    fun sub(from: Double, to: Double): Polyline {
        val inner = pts.indices.filter { cum[it] > from && cum[it] < to }.map { pts[it] }
        return Polyline(listOf(at(from)) + inner + at(to))
    }
}
