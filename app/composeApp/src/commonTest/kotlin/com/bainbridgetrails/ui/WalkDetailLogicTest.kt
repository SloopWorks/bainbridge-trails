package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.tracking.WalkRecord
import com.bainbridgetrails.tracking.WalkTrail
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalkDetailLogicTest {
    private fun walk(trails: List<WalkTrail>, completed: List<String> = emptyList()) = WalkRecord(
        sessionId = "s1", startedAt = 0, endedAt = 60_000, distanceM = 100.0, matchedM = 80.0, verified = true,
        trails = trails, newlyCompleted = completed,
    )

    @Test
    fun sectionsSplitCompletedFromTheRestAndKeepRecordOrder() {
        val w = walk(
            listOf(WalkTrail("a", 0.5, 0.9), WalkTrail("b", 0.2, 0.6), WalkTrail("gone", 0.0, 0.3), WalkTrail("c", 0.0, 0.1)),
            completed = listOf("a"),
        )
        val names = mapOf("a" to "Alpha", "b" to "Bravo", "c" to "Charlie")
        val s = WalkDetailLogic.sections(w) { names[it] }
        assertEquals(listOf(WalkTrailRow("a", "Alpha", 0.5, 0.9)), s.completed)
        // A trail no longer in the dataset is skipped; order (highest resulting fraction first) is kept.
        assertEquals(listOf("b", "c"), s.rest.map { it.trailId })
        assertTrue(WalkDetailLogic.sections(walk(emptyList())) { it }.isEmpty)
    }

    @Test
    fun trackPiecesDropInaccurateFixesAndSplitAtGapsAndJumps() {
        val lat = 47.62
        fun fix(t: Long, dLonM: Double, acc: Double? = 5.0) = Fix(t * 1000, -122.52 + dLonM / 75_000.0, lat, acc)
        val fixes = listOf(
            fix(0, 0.0), fix(5, 10.0), fix(10, 20.0, acc = 120.0), fix(15, 30.0), // inaccurate one dropped
            fix(500, 40.0), fix(505, 50.0), // > 2 min gap: new piece
            fix(510, 2_000.0), // > 250 m jump: alone, dropped (one point)
        )
        val pieces = WalkDetailLogic.trackPieces(fixes)
        assertEquals(listOf(3, 2), pieces.map { it.size })
        assertEquals(listOf(-122.52, lat), pieces[0][0])
        assertTrue(WalkDetailLogic.trackPieces(emptyList()).isEmpty())
        assertTrue(WalkDetailLogic.trackPieces(listOf(fix(0, 0.0))).isEmpty())
    }

    @Test
    fun startTimeIsTwelveHour() {
        val utc = TimeZone.UTC
        assertEquals("12:05 AM", WalkDetailLogic.startTime(5 * 60_000L, utc))
        assertEquals("8:30 AM", WalkDetailLogic.startTime((8 * 60 + 30) * 60_000L, utc))
        assertEquals("12:00 PM", WalkDetailLogic.startTime(12 * 3_600_000L, utc))
        assertEquals("11:59 PM", WalkDetailLogic.startTime((23 * 60 + 59) * 60_000L, utc))
    }
}
