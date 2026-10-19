package com.bainbridgetrails.coverage

import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HmmPerfTest {
    @Test
    fun sixtyMinuteWalkUnderTwoSeconds() {
        val segsFile = File("../../data/dist/coverage_segments.json")
        val trailsFile = File("../../data/dist/trails.json")
        if (!segsFile.exists() || !trailsFile.exists()) {
            println("HmmPerfTest: SKIPPED, ../../data/dist files missing")
            return
        }
        val ds = Dataset.parse(trailsFile.readText(), segsFile.readText())
        val params = CoverageParams(matcher = Matcher.HMM)
        val proj = LocalProjection()
        var t = System.nanoTime()
        val index = SegmentIndex(ds.segments, params, proj)
        val indexMs = (System.nanoTime() - t) / 1e6

        // longest trail's chain: its segments in order, each oriented to continue from the previous end
        val trail = ds.trails.maxBy { it.computedLengthMi }
        val chain = ArrayList<Xy>()
        for (id in trail.segmentIds) {
            var line = index.segments.getValue(id).coords.map { proj.toXy(it[0], it[1]) }
            if (chain.isNotEmpty()) {
                val e = chain.last()
                if (hypot(line.last().x - e.x, line.last().y - e.y) < hypot(line.first().x - e.x, line.first().y - e.y)) line = line.reversed()
            }
            chain += line
        }
        val cum = DoubleArray(chain.size)
        for (i in 1 until chain.size) cum[i] = cum[i - 1] + hypot(chain[i].x - chain[i - 1].x, chain[i].y - chain[i - 1].y)
        val total = cum.last()
        fun at(d0: Double): Xy {
            // ping-pong along the chain if the walk is longer than it
            val m = d0 % (2 * total)
            val d = if (m <= total) m else 2 * total - m
            val k = cum.indexOfFirst { it >= d }.coerceAtLeast(1)
            val f = (d - cum[k - 1]) / (cum[k] - cum[k - 1]).coerceAtLeast(1e-9)
            return Xy(chain[k - 1].x + f * (chain[k].x - chain[k - 1].x), chain[k - 1].y + f * (chain[k].y - chain[k - 1].y))
        }
        val rnd = java.util.Random(42)
        fun g(): Double = rnd.nextGaussian()
        val fixes = (0 until 3600).map { s ->
            val p = at(s * 1.3)
            val (lon, lat) = proj.toLonLat(Xy(p.x + 5.0 * g(), p.y + 5.0 * g()))
            Fix(s * 1000L, lon, lat)
        }
        val pieces = filterFixes(fixes, params, proj)

        t = System.nanoTime()
        val graph = TickGraph(index, params.junctionM)
        val graphMs = (System.nanoTime() - t) / 1e6
        val matcher = HmmMatcher(index, graph, params)

        t = System.nanoTime()
        val cold = matcher.cover(pieces)
        val coldMs = (System.nanoTime() - t) / 1e6
        t = System.nanoTime()
        val warm = matcher.cover(pieces)
        val warmMs = (System.nanoTime() - t) / 1e6

        t = System.nanoTime()
        index.coverFixes(fixes)
        val dispatchMs = (System.nanoTime() - t) / 1e6

        val trailIds = trail.segmentIds.toSet()
        val coveredTrailM = trailIds.sumOf { cold.coveredM(index, it) }
        val trailM = trailIds.sumOf { index.segments.getValue(it).lengthM }
        println(
            "HmmPerfTest: trail=${trail.id} chain=${"%.0f".format(total)}m pieces=${pieces.size} " +
                "index=${"%.0f".format(indexMs)}ms graph=${"%.0f".format(graphMs)}ms cover cold=${"%.0f".format(coldMs)}ms " +
                "warm=${"%.0f".format(warmMs)}ms coverFixes(incl. lazy graph)=${"%.0f".format(dispatchMs)}ms " +
                "trailCovered=${"%.3f".format(coveredTrailM / trailM)}",
        )
        assertEquals(cold, warm)
        assertTrue(coldMs < 2000.0, "cover cold ${coldMs}ms")
        assertTrue(dispatchMs < 2000.0, "coverFixes incl. lazy graph ${dispatchMs}ms")
    }
}
