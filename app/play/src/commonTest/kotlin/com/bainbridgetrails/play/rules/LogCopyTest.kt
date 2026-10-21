package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.claim.ClaimKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Logbook text is stored in each entry, so [LogCopy] must yield exactly the literals earlier builds wrote (PlayClaims,
 * WalkMarks, StartBalance, the restore and server-credit entries, the riddle stamp): an old Logbook reads the same.
 */
class LogCopyTest {
    @Test
    fun yieldsThePreviouslyStoredLiterals() {
        val expected = mapOf(
            LogKind.coverage to ("New ground" to "New coverage on this walk"),
            LogKind.complete to (null to "Completed on this walk"),
            LogKind.square to ("Fog lifted" to "First coverage in a chart square"),
            LogKind.day to ("Walking day" to "First walk of the day, 10 minutes or more"),
            LogKind.treasure to (null to "Weekly treasure"),
            LogKind.egg to (null to "Hidden find"),
            LogKind.riddle to (null to "Riddle walked to"),
            LogKind.ink to (null to "Kraken stretch repelled"),
            LogKind.lore to (null to "Island story"),
            LogKind.challenge to (null to "Challenge complete"),
            LogKind.week to ("Kept week" to "Walking days on 2 or more days this week"),
            LogKind.retreat to ("The kraken retreats" to "Islanders sent it back"),
            LogKind.island_challenge to ("Kraken week" to "Island challenge complete"),
            LogKind.start_balance to ("Your walks so far" to "Coverage, completions and squares from before Play"),
            LogKind.restored to ("Restored from your account" to "Your marks so far; earlier detail is not on this phone"),
            LogKind.stamp to (null to "Riddle solved"),
            LogKind.visit to (null to "A quiet place. No marks here."),
        )
        assertEquals(expected.keys, LogCopy.kinds)
        for ((kind, line) in expected) assertEquals(LogLine(line.first, line.second), LogCopy[kind], kind.name)
        assertEquals(LogLine("Walks while Play was off", "New coverage, completions and squares"), LogCopy.catchUp)
        assertEquals("Visited the Exclusion Memorial", LogCopy.visitWhat("exclusion-memorial"))
        assertEquals("Visited Port Blakely Cemetery", LogCopy.visitWhat("blakely-cemetery"))
        assertNull(LogCopy.visitWhat("other"))
    }

    @Test
    fun everyClaimKindHasALine() {
        for (k in ClaimKind.entries) LogCopy.why(k.logKind)
    }

    @Test
    fun startBalanceWritesTheTableText() {
        val lb = StartBalance.onEnable(Logbook(emptyList()), WalkDerived(10, 0, 0), kotlinx.datetime.LocalDate(2027, 1, 4)) { "a" }
        assertEquals("Your walks so far", lb.entries.single().what)
    }
}
