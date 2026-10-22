package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.Goal
import kotlinx.datetime.LocalDate

/**
 * The Play state's trails ledger (plan D1): the [logbook], the keys paid outside per-key Logbook rows ([trailPaid]:
 * those folded into a `start_trails` entry, or seeded by a restore) and whether the trails start ran ([trailsStarted]).
 * The app's `PlayState` holds the same three values.
 */
data class TrailLedger(val logbook: Logbook, val trailPaid: Set<String> = emptySet(), val trailsStarted: Boolean = false)

/**
 * The trails rules' share of the start balance (plan D4, ux § 6), run after [StartBalance] on every turn-on / start,
 * trails only. The owed keys (`qualifying − paid`, [TrailRewards.paid]) except the island become **one**
 * `start_trails` entry ("Trail rewards so far" the first time, "Trail rewards caught up" later); an owed island is its
 * own `island` entry (its frozen why names it, the sibling's does not). Nothing owed → nothing written. Every owed key
 * joins [TrailLedger.trailPaid], so it is never paid again; [TrailLedger.trailsStarted] is set. Roads: unchanged.
 * Order on the opt-in day: `start_balance`, `start_trails`, `island`. [newId] makes entry ids.
 */
object TrailStart {
    fun onEnable(goal: Goal, ledger: TrailLedger, qualifying: Set<String>, date: LocalDate, newId: () -> String): TrailLedger {
        if (goal != Goal.trails) return ledger
        val owed = TrailRewards.owed(qualifying, TrailRewards.paid(ledger.trailPaid, ledger.logbook))
        var lb = ledger.logbook
        val folded = owed.filter { it != TrailRewards.ISLAND }
        if (folded.isNotEmpty()) {
            val line = if (ledger.trailsStarted) LogCopy.trailCatchUp else LogCopy[LogKind.start_trails]
            lb = lb.add(LogEntry(newId(), LogKind.start_trails, line.what!!, line.why, date, folded.sumOf { TrailRewards.marksOf(it) }))
        }
        if (TrailRewards.ISLAND in owed) {
            lb = lb.add(
                LogEntry(
                    newId(), LogKind.island, LogCopy.what(LogKind.island), LogCopy.why(LogKind.island), date,
                    TrailRewards.marksOf(TrailRewards.ISLAND), contentId = TrailRewards.ISLAND,
                ),
            )
        }
        return TrailLedger(lb, ledger.trailPaid + owed, trailsStarted = true)
    }
}
