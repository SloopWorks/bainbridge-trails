package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * schedule.md §5.3 ceilings on the JVM, after warm-up, over the v1 pack and a synthetic 1,600-segment index (runs
 * without the datasets). Budget ≤ 1 ms per evaluation; asserted generously (< 5 ms per day plan, < 1 ms per agenda)
 * so CI noise never flakes it, with the measured numbers printed.
 */
class PlaySchedulerPerfTest {
    private val index: FakePlaceIndex
    private val derived: Map<Goal, DerivedGoal>

    init {
        val feats = ArrayList<FakePlaceIndex.Feature>()
        for (i in 0 until 160) {
            val lat = 47.57 + (i / 16) * 0.012
            val lon = -122.58 + (i % 16) * 0.004
            for (goal in Goal.entries) {
                val segs = (0 until 5).map { k ->
                    SegRef.fromLine(
                        "${goal.name}-seg-$i-$k",
                        FakePlaceIndex.line(lat + k * 0.0008, lon, lat + (k + 1) * 0.0008, lon + 0.0004),
                    )
                }
                val info = if (goal == Goal.trails) FeatureInfo("trail-perf-$i", goal) else
                    FeatureInfo("road-perf-$i", goal, true, "residential", true, "public_city", 25)
                feats += FakePlaceIndex.Feature(info, segs)
            }
        }
        index = FakePlaceIndex(feats)
        derived = Goal.entries.associateWith { g ->
            DerivedGoal(g, feats.filter { it.info.goal == g }.map { it.info.id }, null)
        }
    }

    private fun scheduler() = PlayScheduler(RealPack.config, index, RealPack.tides, RealPack.ferry, derived)

    @Test
    fun `day plans and agenda within budget`() {
        val start = LocalDate(2027, 1, 1)
        repeat(2) { scheduler().let { s -> for (i in 0 until 120) for (g in Goal.entries) s.dayPlan(start.plus(i, DateTimeUnit.DAY), g) } }

        val s = scheduler()
        val mark = TimeSource.Monotonic.markNow()
        var n = 0
        for (i in 0 until 365) for (g in Goal.entries) {
            s.dayPlan(start.plus(i, DateTimeUnit.DAY), g)
            n++
        }
        val perPlanMs = mark.elapsedNow().inWholeMicroseconds / 1000.0 / n
        val placements = s.dayPlan(LocalDate(2027, 3, 1), Goal.roads).occurrences.sumOf { it.placements.size }

        val t0 = Instant.parse("2027-03-04T18:00:00Z")
        val ctx = PlayContext(Goal.roads)
        repeat(2_000) { s.agenda(t0 + (it % 600).minutes, ctx) }
        val mark2 = TimeSource.Monotonic.markNow()
        repeat(10_000) { s.agenda(t0 + (it % 600).minutes, ctx) }
        val perAgendaMs = mark2.elapsedNow().inWholeMicroseconds / 1000.0 / 10_000
        println("PlaySchedulerPerfTest: dayPlan %.3f ms avg over %d plans (%d placements on 2027-03-01 roads), agenda %.4f ms avg".format(perPlanMs, n, placements, perAgendaMs))
        assertTrue(placements >= 20, "the synthetic index feeds the treasure draw ($placements)")
        assertTrue(perPlanMs < 5.0, "dayPlan %.3f ms".format(perPlanMs))
        assertTrue(perAgendaMs < 1.0, "agenda %.4f ms".format(perAgendaMs))
    }
}
