package com.bainbridgetrails.coverage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RP-18 (docs/roads/product.md §5.1): roads progress counts only counted roads and their counted segments. */
class GoalRulesTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()

    private fun lonLat(p: Xy): List<Double> = proj.toLonLat(p).let { listOf(it.first, it.second) }

    /** 100 m east-west segment at northing [y]. */
    private fun seg(id: String, y: Double, roads: List<String>, included: Boolean = true) =
        SegmentGeom(id, 100.0, included, roads, listOf(lonLat(Xy(0.0, y)), lonLat(Xy(100.0, y))))

    private fun walkOf(vararg ys: Double) = ys.map { y -> listOf(Xy(0.0, y), Xy(100.0, y)) }

    private val segs = listOf(
        seg("rseg-1", 0.0, listOf("road-a")),
        seg("rseg-2", 1000.0, listOf("road-b")),
        seg("rseg-3", 2000.0, listOf("road-b"), included = false), // private stretch of a partly public road
        seg("rseg-4", 3000.0, listOf("road-p"), included = false), // private road
        seg("seg-9", 4000.0, emptyList(), included = false), // trail competitor (RD-2b)
    )
    private val roads = listOf(
        TrailMeta("road-a", "A", listOf("rseg-1"), 0.06),
        TrailMeta("road-b", "B", listOf("rseg-2", "rseg-3"), 0.12),
        TrailMeta("road-p", "P", listOf("rseg-4"), 0.06, counted = false, ownership = "private"),
    )
    private val index = SegmentIndex(segs, params, proj)
    private val roadsDs = Dataset(roads, 200.0, segs, rules = GoalRules.ROADS)
    private val trailsDs = Dataset(roads, 200.0, segs) // same data, trail rules
    private val pr = Progress(roadsDs, index, params)
    private val pt = Progress(trailsDs, index, params)

    @Test
    fun roadFractionIsOverCountedSegmentsOnly() {
        val st = index.cover(walkOf(1000.0))
        assertTrue(pr.trailFraction(st, "road-b") > 0.85, "public part walked = road complete")
        assertTrue(pt.trailFraction(st, "road-b") < 0.55, "trail rules unchanged: all segments")
    }

    @Test
    fun uncountedRoadKeepsAllSegmentFraction() {
        val st = index.cover(walkOf(3000.0))
        assertTrue(pr.trailFraction(st, "road-p") > 0.85)
    }

    @Test
    fun completedAndTotalCountOnlyCountedRoads() {
        val st = index.cover(walkOf(0.0, 1000.0, 3000.0))
        val snap = pr.snapshot(st, stickyCompleted = setOf("road-p"))
        assertEquals(2, snap.trailsCompleted)
        assertEquals(2, snap.trailCount)
        assertEquals(2, pr.trailsCompleted(st))
        assertEquals(TrailStatus.COMPLETE, snap.trail("road-p")?.status, "fraction still shown for uncounted roads")
        val trailSnap = pt.snapshot(st)
        assertEquals(3, trailSnap.trailCount, "trail rules: every row counts")
    }

    @Test
    fun roadsMapBucketsHoldOnlyCountedSegments() {
        val st = index.cover(walkOf(0.0, 2000.0, 3000.0, 4000.0))
        val roadsBucketed = pr.snapshot(st).segmentBuckets.values.flatten().toSet()
        assertEquals(setOf("rseg-1"), roadsBucketed, "private stretches, private roads and trail competitors show no progress")
        val trailBucketed = pt.snapshot(st).segmentBuckets.values.flatten().toSet()
        assertTrue("rseg-3" in trailBucketed, "trail rules unchanged")
    }

    @Test
    fun roadFieldsSurviveTheBinary() {
        val rich = roads + TrailMeta(
            "road-c", "C", listOf("rseg-1"), 0.06, counted = true, category = "arterial", ownership = "public_city",
            surface = "paved", speedMph = 35, maintainer = "City of Bainbridge Island", climbTotalM = 12.5,
        )
        val decoded = DatasetBinary.decode(DatasetBinary.encode(Dataset(rich, 200.0, segs), 7L))
        assertEquals(rich, decoded.trails)
    }

    @Test
    fun countedFlagAndPrefix() {
        assertTrue(pr.counts("road-a"))
        assertTrue(!pr.counts("road-p"))
        assertTrue(pt.counts("road-p"), "trail rules count every trail")
        assertEquals("rseg-", GoalRules.ROADS.matchedSegmentPrefix)
        assertEquals(null, GoalRules.TRAILS.matchedSegmentPrefix)
    }

    @Test
    fun matchedMetresCountOnlyGoalSegments() {
        val st = index.cover(walkOf(0.0, 3000.0))
        val all = st.totalCoveredM(index)
        assertEquals(all, GoalRules.TRAILS.matchedM(st, index), 1e-9)
        assertEquals(all, GoalRules.ROADS.matchedM(st, index), 1e-9) // rseg- counted or not
        val competitor = CoverageState.EMPTY.union(st) // sanity: a seg- credit is excluded under roads rules
        val withTrail = CoverageState(mapOf("seg-9" to longArrayOf(-1L)))
        assertTrue(GoalRules.TRAILS.matchedM(withTrail, index) > 0.0)
        assertEquals(0.0, GoalRules.ROADS.matchedM(withTrail, index))
        assertEquals(all, GoalRules.ROADS.matchedM(competitor.union(withTrail), index), 1e-9)
    }
}
