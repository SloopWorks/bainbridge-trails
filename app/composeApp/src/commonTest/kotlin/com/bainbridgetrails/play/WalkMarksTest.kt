package com.bainbridgetrails.play

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogCopy
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.StartBalance
import com.bainbridgetrails.play.rules.WalkDerived
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Walk-derived Logbook entries per finished walk (product § 3.2, § 3.8; plan L2-10): provisional until the upload answers. */
class WalkMarksTest {
    private val date = LocalDate(2026, 10, 29)
    private var n = 0
    private val newId = { "id-${n++}" }
    private val names = mapOf("trail-a" to "Trail A")

    private fun apply(st: PlayState, after: WalkDerived, completed: List<String> = emptyList(), visited: Set<String> = emptySet(), session: String = "s1") =
        WalkMarks.apply(st, Goal.trails, session, date, after, completed, { names[it] ?: it }, visited, newId)

    @Test
    fun coverageCompletionAndSquaresFromTheSnapshotDifferencePending() {
        val start = PlayState(logbook = StartBalance.onEnable(Logbook(), WalkDerived(500, 100, 50), date, newId), derivedBase = DerivedBase(500, 100, 50))
        val st = apply(start, WalkDerived(coverage = 560, complete = 200, squares = 100), completed = listOf("trail-a"))
        val walk = st.logbook.entries.filter { it.walkId == "s1" }
        assertEquals(listOf(LogKind.complete, LogKind.coverage, LogKind.square), walk.map { it.kind })
        assertEquals(listOf(100, 60, 50), walk.map { it.marks })
        assertEquals("Trail A", walk.first().what)
        assertTrue(walk.all { it.board == BoardState.pending }, "provisional until the coverage response's play block")
        assertTrue(WalkMarks.hasPending(st.logbook, "s1"))
        assertEquals(DerivedBase(560, 200, 100), st.derivedBase)
        // Same walk again (a re-run hook): nothing more.
        assertEquals(st.logbook.total, apply(st, WalkDerived(560, 200, 100), completed = listOf("trail-a")).logbook.total)
        // The next turn-on catch-up owes nothing: the snapshot and the Logbook agree.
        assertEquals(st.logbook, StartBalance.onEnable(st.logbook, WalkDerived(560, 200, 100), date, newId))
    }

    @Test
    fun noBaseYetOwesTheDifferenceOnce() {
        val lb = Logbook(listOf(LogEntry("sb", LogKind.start_balance, LogCopy.what(LogKind.start_balance), LogCopy.why(LogKind.start_balance), date, 600)))
        val st = apply(PlayState(logbook = lb), WalkDerived(700, 0, 25))
        assertEquals(725, st.logbook.total)
        assertEquals(DerivedBase(700, 0, 25), st.derivedBase)
    }

    @Test
    fun settlesWithTheWalkAndVisitStampsHaveNoNumber() {
        val st = apply(PlayState(derivedBase = DerivedBase()), WalkDerived(10, 0, 0), visited = setOf("exclusion-memorial"))
        val visit = st.logbook.entries.single { it.kind == LogKind.visit }
        assertEquals(0, visit.marks)
        assertEquals("Visited the Exclusion Memorial", visit.what)
        assertEquals(BoardState.accepted, visit.board)
        val accepted = PlayClaims.settle(st.logbook, listOf(PlaySettled("s1", walk = BoardState.accepted)))
        assertTrue(accepted.entries.filter { it.kind == LogKind.coverage }.all { it.board == BoardState.accepted })
        assertFalse(WalkMarks.hasPending(accepted, "s1"))
        val refused = PlayClaims.settle(st.logbook, listOf(PlaySettled("s1", walk = BoardState.local_only)))
        assertEquals(BoardState.local_only, refused.entries.single { it.kind == LogKind.coverage }.board)
    }

    @Test
    fun visitedZonesByPolygon() {
        val ring = listOf(GeoPoint(lat = 47.61, lon = -122.52), GeoPoint(lat = 47.61, lon = -122.50), GeoPoint(lat = 47.62, lon = -122.50), GeoPoint(lat = 47.62, lon = -122.52), GeoPoint(lat = 47.61, lon = -122.52))
        val zones = listOf(QuietZoneOutline("exclusion-memorial", ring))
        assertEquals(setOf("exclusion-memorial"), WalkMarks.visitedZones(listOf(GeoPoint(lat = 47.615, lon = -122.51)), zones))
        assertEquals(emptySet(), WalkMarks.visitedZones(listOf(GeoPoint(lat = 47.615, lon = -122.53)), zones))
    }
}
