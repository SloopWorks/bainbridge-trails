package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.GoalRules
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.coverage.Xy
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.record.LiveCoverage
import com.bainbridgetrails.tracking.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Roads walk numbers (product.md §5): live rows, milestones from the walk summary, Me per-type miles. */
class RoadsWalkNumbersTest {
    private val proj = LocalProjection()
    private val params = CoverageParams()
    private fun lonLat(x: Double, y: Double) = proj.toLonLat(Xy(x, y)).let { listOf(it.first, it.second) }
    private fun seg(id: String, y: Double, roads: List<String>, included: Boolean = true) =
        SegmentGeom(id, 200.0, included, roads, listOf(lonLat(0.0, y), lonLat(200.0, y)))

    private val segs = listOf(
        seg("rseg-a", 0.0, listOf("A")),
        seg("rseg-a2", 1000.0, listOf("A"), included = false), // private stretch of a partly public road
        seg("rseg-p", 2000.0, listOf("P"), included = false),
    )
    private val roads = listOf(
        // computed (display) length 0.25 mi = whole road; counted part is 200 m = 0.124 mi.
        TrailMeta("A", "A Road", listOf("rseg-a", "rseg-a2"), 0.25, category = "residential"),
        TrailMeta("P", "Private Lane", listOf("rseg-p"), 0.124, counted = false, category = "residential", ownership = "private"),
    )
    private val data = Dataset(roads, 200.0, segs, rules = GoalRules.ROADS).let { ds ->
        TrailData(ds, SegmentIndex(segs, params, proj), params, 0, 0)
    }

    private fun walkAlong(y: Double) = (0..66).map { i -> lonLat(i * 3.0, y).let { Fix(i * 2000L, it[0], it[1], 5.0) } }

    @Test
    fun liveRowsListOnlyCountedRoads() {
        val live = LiveCoverage.forDataset(data, CoverageState.EMPTY, emptySet())
        val rows = live.update(walkAlong(0.0) + walkAlong(2000.0).map { it.copy(timeMs = it.timeMs + 200_000) })
        assertEquals(listOf("A"), rows.map { it.trailId }, "the private road is walked but never listed")
    }

    @Test
    fun milestonesComeFromTheWalkSummaryNotALaterSnapshot() {
        val summary = SessionSummary(
            "s1", islandBefore = 0.0, islandAfter = 0.0, trails = emptyList(), verified = true, droppedVehicle = 0,
            droppedSteps = 0, newlyCompleted = listOf("A"), completedBefore = 9, completedAfter = 10,
            completedByCategory = mapOf("residential" to 10), countedByCategory = mapOf("residential" to 300),
        )
        assertEquals(listOf<Milestone>(Milestone.Roads(10)), walkMilestones(data, summary, Units.MILES))
        // Sync pulled more completions in after the walk: a later snapshot would say 25, the walk still says 10.
        val noNew = summary.copy(newlyCompleted = emptyList(), completedBefore = 10, completedAfter = 10)
        assertTrue(walkMilestones(data, noNew, Units.MILES).isEmpty())
        assertEquals(Milestone.Island, walkMilestones(data, summary, Units.MILES, islandReached = true).first())
    }

    @Test
    fun walkedMilesByTypeUseTheCountedLength() {
        val state = data.index.coverFixes(walkAlong(0.0))
        val n = RoadsMeNumbers.of(data, data.progress.snapshot(state))
        assertEquals(200.0 / 1609.344, n.walkedMiByCategory.getValue("residential"), 0.01)
    }
}
