package com.bainbridgetrails.record

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.TrailStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TL-8 windowing/merge with a toy coverage model: a fix covers tick `lon` of trail "a" (lat 1) or "b" (lat 2);
 * each trail has 10 ticks.
 */
class LiveCoverageTest {
    private val min = 60_000L
    private fun fix(tMin: Double, trail: Int, tick: Int, mock: Boolean = false) =
        Fix((tMin * min).toLong(), tick.toDouble(), trail.toDouble(), 3.0, mock)

    private fun live(pre: Set<String> = emptySet(), sticky: Set<String> = emptySet(), log: MutableList<List<Fix>>? = null) = LiveCoverage(
        preWalk = pre,
        trails = listOf("a" to "Alpha", "b" to "Beta"),
        cover = { fixes ->
            log?.add(fixes)
            fixes.mapTo(HashSet()) { "${if (it.lat == 1.0) "a" else "b"}:${it.lon.toInt()}" }
        },
        union = { x, y -> x + y },
        fraction = { s, id -> s.count { it.startsWith("$id:") } / 10.0 },
        status = { f -> if (f >= 0.85) TrailStatus.COMPLETE else if (f >= 0.75) TrailStatus.NEARLY_DONE else if (f > 0) TrailStatus.PARTWAY else TrailStatus.NOT_STARTED },
        stickyCompleted = sticky,
    )

    @Test
    fun windowKeepsTrailingTenMinutesByFixTimeAndDropsMocks() {
        val track = listOf(fix(0.0, 1, 0), fix(4.0, 1, 1), fix(5.0, 1, 2, mock = true), fix(9.0, 1, 3), fix(14.0, 1, 4))
        val w = LiveCoverage.window(track, 10 * min)
        assertEquals(listOf(1, 3, 4), w.map { it.lon.toInt() }, "fixes at t >= 4 min, mock dropped")
        assertEquals(emptyList(), LiveCoverage.window(emptyList(), 10 * min))
    }

    @Test
    fun matchesOnlyTheWindowButAccumulatesTheWholeWalk() {
        val log = mutableListOf<List<Fix>>()
        val l = live(log = log)
        val track = ArrayList<Fix>()
        // Walk trail a, one tick a minute for 8 minutes, updating every "30 s" (here: every minute).
        for (i in 0 until 8) {
            track += fix(i * 2.0, 1, i) // 2 min per tick: 14 min in total
            l.update(track.toList())
        }
        // The last match saw only the trailing 10 minutes (t >= 4 min => ticks 2..7)...
        assertEquals((2..7).toList(), log.last().map { it.lon.toInt() })
        // ...but the rows keep everything earlier windows found.
        val row = l.update(track.toList()).single()
        assertEquals("a", row.trailId)
        assertEquals(0.0, row.before)
        assertEquals(0.8, row.after, 1e-9)
        assertEquals(TrailStatus.NEARLY_DONE, row.status)
    }

    @Test
    fun mergesOntoPreWalkStateAndKeepsBefore() {
        val l = live(pre = setOf("a:0", "a:1", "a:2"))
        val rows = l.update(listOf(fix(0.0, 1, 2), fix(0.5, 1, 3)))
        val a = rows.single()
        assertEquals(0.3, a.before, 1e-9)
        assertEquals(0.4, a.after, 1e-9, "a:2 was already covered; a:3 is new")
    }

    @Test
    fun rewalkingCoveredTrailStillListsIt() {
        val l = live(pre = setOf("b:0", "b:1"))
        val rows = l.update(listOf(fix(0.0, 2, 0), fix(0.5, 2, 1)))
        assertEquals(listOf("b"), rows.map { it.trailId })
        assertEquals(rows.single().before, rows.single().after)
    }

    @Test
    fun mostRecentlyTouchedFirst() {
        val l = live()
        val track = arrayListOf(fix(0.0, 1, 0), fix(0.5, 1, 1), fix(1.0, 1, 2))
        l.update(track.toList())
        track += fix(20.0, 2, 0) // trail a is now outside the window
        val rows = l.update(track.toList())
        assertEquals(listOf("b", "a"), rows.map { it.trailId })
        assertEquals(0.3, rows[1].after, 1e-9, "a keeps what an earlier window found")
    }

    @Test
    fun unchangedTrackDoesNotMatchAgain() {
        val l = live()
        val track = listOf(fix(0.0, 1, 0))
        l.update(track)
        l.update(track)
        l.update(track.toList())
        assertEquals(1, l.matchCount)
    }

    @Test
    fun stickyCompletionShowsComplete() {
        val l = live(pre = setOf("a:0"), sticky = setOf("a"))
        assertEquals(TrailStatus.COMPLETE, l.update(listOf(fix(0.0, 1, 1))).single().status)
    }

    @Test
    fun mockOnlyWindowChangesNothing() {
        val l = live()
        assertTrue(l.update(listOf(fix(0.0, 1, 0, mock = true))).isEmpty())
        assertEquals(0, l.matchCount)
    }
}
