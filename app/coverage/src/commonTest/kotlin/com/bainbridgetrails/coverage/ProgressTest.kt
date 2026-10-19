package com.bainbridgetrails.coverage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProgressTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()

    private fun lonLat(p: Xy): List<Double> = proj.toLonLat(p).let { listOf(it.first, it.second) }

    /** 100 m east-west segment at northing [y]. */
    private fun seg(id: String, y: Double, trails: List<String>, included: Boolean = true) =
        SegmentGeom(id, 100.0, included, trails, listOf(lonLat(Xy(0.0, y)), lonLat(Xy(100.0, y))))

    private fun trail(id: String, vararg segs: String, official: Double? = null) =
        TrailMeta(id, id, segs.toList(), 0.1, official)

    private fun walkOf(y: Double) = listOf(listOf(Xy(0.0, y), Xy(100.0, y)))

    private val segs = listOf(
        seg("s1", 0.0, listOf("T1")),
        seg("s2", 1000.0, listOf("T1", "T2")),
        seg("s3", 2000.0, listOf("T2")),
        seg("sx", 3000.0, listOf("T3"), included = false),
        seg("s4", 4000.0, listOf("T3")),
    )
    private val trails = listOf(
        trail("T1", "s1", "s2"), trail("T2", "s2", "s3"), trail("T3", "sx", "s4"),
    )
    private val ds = Dataset(trails, 300.0, segs)
    private val index = SegmentIndex(segs, params, proj)
    private val progress = Progress(ds, index, params)

    @Test
    fun sharedSegmentCountsForBothTrails() {
        val st = index.cover(walkOf(1000.0))
        assertTrue(progress.trailFraction(st, "T1") > 0.4)
        assertTrue(progress.trailFraction(st, "T2") > 0.4)
        assertEquals(0.0, progress.trailFraction(st, "T3"))
        assertEquals(listOf("T1", "T2"), progress.trailsOn(st, "s2"))
    }

    @Test
    fun islandCountsSharedSegmentOnce() {
        val st = index.cover(walkOf(1000.0))
        val m = st.coveredM(index, "s2")
        assertTrue(m > 90.0)
        assertEquals(m / 300.0, progress.islandFraction(st), 1e-9)
    }

    @Test
    fun excludedSegmentNotInIsland() {
        val st = index.cover(walkOf(3000.0))
        assertTrue(progress.trailFraction(st, "T3") > 0.4)
        assertEquals(0.0, progress.islandFraction(st))
    }

    @Test
    fun statusThresholds() {
        val expected = listOf(
            0.0 to TrailStatus.NOT_STARTED, 0.01 to TrailStatus.PARTWAY, 0.74 to TrailStatus.PARTWAY,
            0.75 to TrailStatus.NEARLY_DONE, 0.849 to TrailStatus.NEARLY_DONE, 0.85 to TrailStatus.COMPLETE,
        )
        for ((f, s) in expected) assertEquals(s, progress.status(f), "fraction $f")
    }

    @Test
    fun trailsCompletedCountsOnlyCompleteTrails() {
        val st = index.cover(walkOf(1000.0) + walkOf(0.0))
        assertEquals(1, progress.trailsCompleted(st)) // T1 = s1 + s2 fully covered
    }

    @Test
    fun displayLengthPrefersOfficial() {
        assertEquals(0.1, trail("a").displayLengthMi)
        assertEquals(2.5, trail("a", official = 2.5).displayLengthMi)
    }

    @Test
    fun parseRejectsVersionMismatch() {
        val t = """{"version":"A","island_total_shippable_m":10.0,"trails":[]}"""
        val s = """{"version":"B","segments":[]}"""
        assertFailsWith<IllegalArgumentException> { Dataset.parse(t, s) }
    }
}
