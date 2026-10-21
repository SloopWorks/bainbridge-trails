package com.bainbridgetrails.play

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.Marks
import com.bainbridgetrails.play.rules.WalkDerived
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * The walk-derived snapshot the last Logbook write was based on (W-COV, W-DONE, W-SQUARE of the coverage snapshot): set
 * by the opt-in starting balance and after every finished walk, so the next walk's marks are the difference.
 */
@Serializable
data class DerivedBase(val coverage: Int = 0, val complete: Int = 0, val squares: Int = 0) {
    companion object {
        fun of(d: WalkDerived) = DerivedBase(d.coverage, d.complete, d.squares)
    }
}

/** A quiet zone's outline (lon/lat ring from `derived-<goal>.json` `quietZones[].polygon`) and its visit wording. */
data class QuietZoneOutline(val id: String, val ring: List<com.bainbridgetrails.play.fx.GeoPoint>)

/**
 * The walk-derived Logbook entries of one finished walk (product § 3.2, § 3.8; plan L2-10): W-COV, W-DONE and W-SQUARE
 * as the difference between the coverage snapshot after the walk and [PlayState.derivedBase], plus remembrance visit
 * stamps (§ 4.6.3: no number). W-DAY is the claim pass's ([PlayClaims.record]). Entries are `pending` (provisional)
 * until the walk's coverage upload is answered with a `play` block (the server derives its own marks, PD-3); visit
 * stamps carry no marks and never sync, so they are `accepted` at once. Idempotent per walk (Logbook dedupe).
 */
object WalkMarks {
    const val COV_WHAT = "New ground"
    const val COV_WHY = "New coverage on this walk"
    const val SQUARE_WHAT = "Fog lifted"
    const val SQUARE_WHY = "First coverage in a chart square"
    const val DONE_WHY = "Completed on this walk"

    /** Kinds this object writes that settle with the walk's upload. */
    val SETTLED_WITH_WALK: Set<LogKind> = setOf(LogKind.coverage, LogKind.complete, LogKind.square)

    /** Remembrance visit wording per quiet zone id (product § 4.6.3 "Visited the Exclusion Memorial"). */
    private val VISIT_WHAT = mapOf(
        "exclusion-memorial" to "Visited the Exclusion Memorial",
        "blakely-cemetery" to "Visited Port Blakely Cemetery",
    )
    const val VISIT_WHY = "A quiet place. No marks here."

    /**
     * [state] after walk [sessionId] (island [date]) given the snapshot [after]: [completed] = the walk's newly completed
     * counted features minus quiet ones, [names] their display names, [visited] quiet zone ids its kept fixes entered.
     * No base yet (Play on from before this rule): the snapshot's total above what the Logbook already holds is owed
     * (as the start balance's catch-up does), split into the completions and one coverage line.
     */
    fun apply(
        state: PlayState,
        goal: Goal,
        sessionId: String,
        date: LocalDate,
        after: WalkDerived,
        completed: List<String>,
        names: (String) -> String,
        visited: Set<String>,
        newId: () -> String,
    ): PlayState {
        var lb = state.logbook
        val done = Marks.doneMarks(goal)
        for (id in completed.distinct()) {
            lb = lb.add(LogEntry(newId(), LogKind.complete, names(id), DONE_WHY, date, done, walkId = sessionId, contentId = id))
        }
        val base = state.derivedBase
        if (base == null) {
            val logged = state.logbook.entries.filter { it.kind.walkDerived }.sumOf { it.marks }
            val owed = after.total - logged - (lb.total - state.logbook.total)
            if (owed > 0) lb = lb.add(LogEntry(newId(), LogKind.coverage, COV_WHAT, COV_WHY, date, owed, walkId = sessionId))
        } else {
            val cov = after.coverage - base.coverage
            if (cov > 0) lb = lb.add(LogEntry(newId(), LogKind.coverage, COV_WHAT, COV_WHY, date, cov, walkId = sessionId))
            val sq = after.squares - base.squares
            if (sq > 0) lb = lb.add(LogEntry(newId(), LogKind.square, SQUARE_WHAT, SQUARE_WHY, date, sq, walkId = sessionId))
        }
        for (zone in visited.sorted()) {
            val what = VISIT_WHAT[zone] ?: continue
            lb = lb.add(LogEntry(newId(), LogKind.visit, what, VISIT_WHY, date, 0, walkId = sessionId, contentId = "visit:$zone@$date", board = BoardState.accepted))
        }
        return state.copy(logbook = lb, derivedBase = DerivedBase.of(after))
    }

    /** True when [Logbook] holds walk-derived entries of [sessionId] still waiting for the upload. */
    fun hasPending(logbook: Logbook, sessionId: String): Boolean =
        logbook.entries.any { it.walkId == sessionId && it.kind in SETTLED_WITH_WALK && it.board == BoardState.pending }

    /** Quiet zone ids any of [points] (lon/lat) lies inside. */
    fun visitedZones(points: List<com.bainbridgetrails.play.fx.GeoPoint>, zones: List<QuietZoneOutline>): Set<String> =
        zones.filterTo(LinkedHashSet()) { z -> points.any { inside(it, z.ring) } }.mapTo(LinkedHashSet()) { it.id }

    /** Ray casting over a lon/lat ring. */
    internal fun inside(p: com.bainbridgetrails.play.fx.GeoPoint, ring: List<com.bainbridgetrails.play.fx.GeoPoint>): Boolean {
        var c = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) c = !c
            j = i
        }
        return c
    }
}
