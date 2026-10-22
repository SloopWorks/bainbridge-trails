package com.bainbridgetrails.play

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogCopy
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.Marks
import com.bainbridgetrails.play.rules.TrailRewardInput
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.rules.TrailStart
import com.bainbridgetrails.play.rules.TrailTables
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

/** The trails rules' input of a finished walk (plan D4, D6): the snapshot after it ([after]) over [tables]. */
class TrailWalk(val tables: TrailTables, val after: TrailRewardInput)

/** A quiet zone's outline (lon/lat ring from `derived-<goal>.json` `quietZones[].polygon`) and its visit wording. */
data class QuietZoneOutline(val id: String, val ring: List<GeoPoint>)

/**
 * The walk-derived Logbook entries of one finished walk (product § 3.2, § 3.8; plan L2-10): W-COV, W-DONE and W-SQUARE
 * as the difference between the coverage snapshot after the walk and [PlayState.derivedBase], plus remembrance visit
 * stamps (§ 4.6.3: no number). W-DAY is the claim pass's ([PlayClaims.record]). Entries are `pending` (provisional)
 * until the walk's coverage upload is answered with a `play` block (the server derives its own marks, PD-3); visit
 * stamps carry no marks and never sync, so they are `accepted` at once. Idempotent per walk (Logbook dedupe).
 */
object WalkMarks {
    /**
     * Kinds this object writes that settle with the walk's upload (v1, both goals; pinned by the G0 roads guard
     * `data/play/golden/roads-guard.json` `client.walkMarks.settledWithWalk`, so the trails kinds sit in
     * [TRAILS_SETTLED_WITH_WALK] and [settle] applies both).
     */
    val SETTLED_WITH_WALK: Set<LogKind> = setOf(LogKind.coverage, LogKind.complete, LogKind.square)

    /**
     * Play trails v1.1 (plan L2-2): the six trails rules' per-walk rows (climb, high_ground, milestone, park_first,
     * park_complete, island) settle with the walk's upload too: the server pays the same keys from the same coverage.
     */
    val TRAILS_SETTLED_WITH_WALK: Set<LogKind> = TrailRewards.KINDS

    private fun settlesWithWalk(kind: LogKind) = kind in SETTLED_WITH_WALK || kind in TRAILS_SETTLED_WITH_WALK

    /**
     * [PlayClaims.settle] plus the trails rows: a walk's pending [TRAILS_SETTLED_WITH_WALK] entries take the walk's
     * outcome ([PlaySettled.walk]) exactly as its W-COV / W-DONE / W-SQUARE rows do. Every production settle goes here.
     */
    fun settle(logbook: Logbook, settled: List<PlaySettled>): Logbook {
        val lb = PlayClaims.settle(logbook, settled)
        if (settled.isEmpty()) return lb
        val walkBySession = settled.mapNotNull { s -> s.walk?.let { s.sessionId to it } }.toMap()
        if (walkBySession.isEmpty() || lb.entries.none { it.kind in TRAILS_SETTLED_WITH_WALK }) return lb
        return Logbook(
            lb.entries.map { e ->
                val board = walkBySession[e.walkId]
                if (board != null && e.board == BoardState.pending && e.kind in TRAILS_SETTLED_WITH_WALK) e.copy(board = board) else e
            },
        )
    }

    /**
     * [state] after walk [sessionId] (island [date]) given the snapshot [after]: [completed] = the walk's newly completed
     * counted features minus quiet ones, [names] their display names, [visited] quiet zone ids its kept fixes entered.
     * No base yet (Play on from before this rule): the snapshot's total above what the Logbook already holds is owed
     * (as the start balance's catch-up does), split into the completions and one coverage line.
     *
     * Trails ([trails] non-null, goal trails; plan D1, D4): the keys [after][TrailWalk.after] qualifies for and not yet
     * paid become one `pending` row each (`contentId` = the key, [TrailRewards.entries]). While the trails start has
     * not run on this phone ([PlayState.trailsStarted] false, review P1-8) it runs first, here, on the same input and in
     * the same update: everything owed goes to its `start_trails` entry (and `island`), the flag is set, and the walk
     * adds no key twice. Null [trails] (roads, tables unavailable, or the start deferred until the account's paid keys
     * are known) writes no trails row.
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
        trails: TrailWalk? = null,
    ): PlayState {
        // Trails (P1-8): the trails start first, on this walk's own input, inside the same update.
        val trailsOn = trails != null && goal == Goal.trails
        val qualifying = if (trails != null && trailsOn) TrailRewards.qualifying(trails.after, trails.tables) else emptySet()
        var ledger = state.trailLedger()
        if (trailsOn && !ledger.trailsStarted) ledger = TrailStart.onEnable(goal, ledger, qualifying, date, newId)
        val started = ledger.logbook
        var lb = started
        val done = Marks.doneMarks(goal)
        for (id in completed.distinct()) {
            lb = lb.add(LogEntry(newId(), LogKind.complete, names(id), LogCopy.why(LogKind.complete), date, done, walkId = sessionId, contentId = id))
        }
        val base = state.derivedBase
        if (base == null) {
            val logged = state.logbook.entries.filter { it.kind.walkDerived }.sumOf { it.marks }
            val owed = after.total - logged - (lb.total - started.total)
            if (owed > 0) lb = lb.add(LogEntry(newId(), LogKind.coverage, LogCopy.what(LogKind.coverage), LogCopy.why(LogKind.coverage), date, owed, walkId = sessionId))
        } else {
            val cov = after.coverage - base.coverage
            if (cov > 0) lb = lb.add(LogEntry(newId(), LogKind.coverage, LogCopy.what(LogKind.coverage), LogCopy.why(LogKind.coverage), date, cov, walkId = sessionId))
            val sq = after.squares - base.squares
            if (sq > 0) lb = lb.add(LogEntry(newId(), LogKind.square, LogCopy.what(LogKind.square), LogCopy.why(LogKind.square), date, sq, walkId = sessionId))
        }
        if (trailsOn) {
            val owed = TrailRewards.owed(qualifying, TrailRewards.paid(ledger.trailPaid, lb))
            for (e in TrailRewards.entries(owed, { names(it) }, date, sessionId, newId)) lb = lb.add(e)
        }
        for (zone in visited.sorted()) {
            val what = LogCopy.visitWhat(zone) ?: continue
            lb = lb.add(LogEntry(newId(), LogKind.visit, what, LogCopy.why(LogKind.visit), date, 0, walkId = sessionId, contentId = "visit:$zone@$date", board = BoardState.accepted))
        }
        return state.copy(logbook = lb, derivedBase = DerivedBase.of(after), trailPaid = ledger.trailPaid, trailsStarted = ledger.trailsStarted)
    }

    /** True when [Logbook] holds walk-derived (or trails) entries of [sessionId] still waiting for the upload. */
    fun hasPending(logbook: Logbook, sessionId: String): Boolean =
        logbook.entries.any { it.walkId == sessionId && settlesWithWalk(it.kind) && it.board == BoardState.pending }

    /** Quiet zone ids any of [points] (lon/lat) lies inside. */
    fun visitedZones(points: List<GeoPoint>, zones: List<QuietZoneOutline>): Set<String> =
        zones.filterTo(LinkedHashSet()) { z -> points.any { inside(it, z.ring) } }.mapTo(LinkedHashSet()) { it.id }

    /** Ray casting over a lon/lat ring. */
    internal fun inside(p: GeoPoint, ring: List<GeoPoint>): Boolean {
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
