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
    fun onEnable(logbook: Logbook, derived: WalkDerived, date: LocalDate, newId: () -> String): Logbook {
        if (logbook.hasRestore) return logbook
        if (!logbook.hasStartBalance) {
            return logbook.add(LogEntry(newId(), LogKind.start_balance, LogCopy.what(LogKind.start_balance), LogCopy.why(LogKind.start_balance), date, derived.total))
        }
        val logged = logbook.entries.filter { it.kind.walkDerived }.sumOf { it.marks }
        val owed = derived.total - logged
        return if (owed > 0) logbook.add(LogEntry(newId(), LogKind.coverage, LogCopy.catchUp.what!!, LogCopy.catchUp.why, date, owed)) else logbook
    }
}
