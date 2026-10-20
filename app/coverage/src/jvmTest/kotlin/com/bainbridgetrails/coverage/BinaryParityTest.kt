package com.bainbridgetrails.coverage

import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * coverage_segments.bin ([SegmentsBinary]) vs coverage_segments.json on the real dataset: same segments (coords
 * bit-for-bit), same ticks, same [SegmentIndex.coverFixes] on fixture walks for every matcher. Also checks that the
 * app's bundled .bin (when synced) is current with its bundled JSON.
 */
class BinaryParityTest {
    private val dist = File("../../data/dist")
    private val bundled = File("../composeApp/src/commonMain/composeResources/files")

    @Test
    fun binaryDecodesToTheJsonDataset() {
        val (trails, segs) = sources(dist) ?: return println("BinaryParityTest: SKIPPED, ../../data/dist files missing")
        val json = Dataset.parse(trails, segs)
        val bytes = SegmentsBinary.encode(json.version, json.segments)
        val bin = Dataset.parse(trails, bytes)
        println("BinaryParityTest: ${json.segments.size} segments, json ${segs.length / 1024} KiB -> bin ${bytes.size / 1024} KiB")
        assertEquals(json.version, bin.version)
        assertEquals(json.trails, bin.trails)
        assertEquals(json.islandTotalM, bin.islandTotalM)
        assertEquals(json.segments, bin.segments) // data class equality; PackedCoords equals List<List<Double>>
        for ((a, b) in json.segments.zip(bin.segments)) for (i in a.coords.indices) {
            assertEquals(a.coords[i][0].toRawBits(), b.coords[i][0].toRawBits(), a.id)
            assertEquals(a.coords[i][1].toRawBits(), b.coords[i][1].toRawBits(), a.id)
        }
        for (params in matcherParams()) assertEquals(fingerprint(json, params), fingerprint(bin, params), "$params")
    }

    @Test
    fun bundledBinaryIsCurrent() {
        val (trails, segs) = sources(bundled) ?: return println("BinaryParityTest: SKIPPED, bundled JSON missing")
        val binFile = File(bundled, "coverage_segments.bin")
        if (!binFile.exists()) return println("BinaryParityTest: SKIPPED, bundled coverage_segments.bin missing")
        val json = Dataset.parse(trails, segs)
        val bin = Dataset.parse(trails, binFile.readBytes())
        assertEquals(json.segments, bin.segments, "bundled coverage_segments.bin is stale: run scripts/sync-app-assets.sh")
    }

    private fun sources(dir: File): Pair<String, String>? {
        val t = File(dir, "trails.json")
        val s = File(dir, "coverage_segments.json")
        return if (t.exists() && s.exists()) t.readText() to s.readText() else null
    }

    private fun matcherParams() = listOf(
        CoverageParams(),
        CoverageParams(matcher = Matcher.COMPETITIVE),
        CoverageParams(matcher = Matcher.COMPETITIVE, headingToleranceDeg = 45.0),
    )

    companion object {
        /** Ticks of every segment plus coverFixes on noisy walks along five trails, as one comparable string. */
        fun fingerprint(ds: Dataset, params: CoverageParams): String {
            val proj = LocalProjection()
            val index = SegmentIndex(ds.segments, params, proj)
            val sb = StringBuilder()
            for (s in ds.segments) {
                sb.append(s.id).append(':').append(index.tickCount(s.id)).append(':').append(index.tickLengthM(s.id).toRawBits())
                index.tickPoints[index.slotOf(s.id)!!].forEach { sb.append(',').append(it.x.toRawBits()).append(',').append(it.y.toRawBits()) }
                sb.append('\n')
            }
            for (trail in ds.trails.sortedByDescending { it.computedLengthMi }.take(5)) {
                sb.append(trail.id).append('=').append(index.coverFixes(walk(ds, index, trail, proj)).encode()).append('\n')
            }
            return sb.toString()
        }

        private fun walk(ds: Dataset, index: SegmentIndex, trail: TrailMeta, proj: LocalProjection): List<Fix> {
            val chain = ArrayList<Xy>()
            for (id in trail.segmentIds) {
                var line = index.segments.getValue(id).projectedLine(proj)
                if (chain.isNotEmpty()) {
                    val e = chain.last()
                    if (hypot(line.last().x - e.x, line.last().y - e.y) < hypot(line.first().x - e.x, line.first().y - e.y)) line = line.reversed()
                }
                chain += line
            }
            val rnd = java.util.Random(trail.id.hashCode().toLong())
            val fixes = ArrayList<Fix>()
            var tMs = 0L
            for (i in 0 until chain.size - 1) {
                val a = chain[i]
                val b = chain[i + 1]
                val n = maxOf(1, (hypot(b.x - a.x, b.y - a.y) / 1.4).toInt())
                for (k in 0 until n) {
                    val f = k.toDouble() / n
                    val (lon, lat) = proj.toLonLat(Xy(a.x + f * (b.x - a.x) + 4 * rnd.nextGaussian(), a.y + f * (b.y - a.y) + 4 * rnd.nextGaussian()))
                    fixes += Fix(tMs, lon, lat, accuracyM = 8.0)
                    tMs += 1000
                }
            }
            assertTrue(fixes.size > 10, "walk along ${trail.id}")
            return fixes
        }
    }
}
