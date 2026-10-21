package com.bainbridgetrails.play.rules

import kotlinx.datetime.LocalDate

/**
 * The opt-in starting balance (product §3.6.4, PF1-1, PF1-2, PF14-2), applied every time Play is turned on:
 * - first time: one "Your walks so far" entry with the snapshot's walk-derived marks ([WalkDerived]);
 * - later: never a second start balance; walk-derived marks earned while Play was off (the snapshot's total above what
 *   the Logbook already holds for coverage, completions, squares and the start balance) are one `coverage` entry;
 * - a Logbook restored from the account (§3.6.5) already carries the server total: nothing is added.
 * [newId] makes the entry id (a uuid in the app).
 */
object StartBalance {
    const val WHAT = "Your walks so far"
    const val WHY = "Coverage, completions and squares from before Play"
    const val CATCH_UP_WHAT = "Walks while Play was off"
    const val CATCH_UP_WHY = "New coverage, completions and squares"

    fun onEnable(logbook: Logbook, derived: WalkDerived, date: LocalDate, newId: () -> String): Logbook {
        if (logbook.hasRestore) return logbook
        if (!logbook.hasStartBalance) {
            return logbook.add(LogEntry(newId(), LogKind.start_balance, WHAT, WHY, date, derived.total))
        }
        val logged = logbook.entries.filter { it.kind.walkDerived }.sumOf { it.marks }
        val owed = derived.total - logged
        return if (owed > 0) logbook.add(LogEntry(newId(), LogKind.coverage, CATCH_UP_WHAT, CATCH_UP_WHY, date, owed)) else logbook
    }
}
