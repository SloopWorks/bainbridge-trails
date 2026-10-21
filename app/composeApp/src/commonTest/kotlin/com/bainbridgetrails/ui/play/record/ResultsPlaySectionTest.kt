package com.bainbridgetrails.ui.play.record

import com.bainbridgetrails.play.claim.ClaimResult
import com.bainbridgetrails.play.claim.Unclaimed
import com.bainbridgetrails.play.claim.UnclaimedReason
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.Ranks
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The results sheet's Play section (ux.md §3.9; product PF6-1 … PF6-4). */
class ResultsPlaySectionTest {
    private val day = LocalDate(2026, 10, 29)
    private val session = "s1792000000000-a"
    private fun e(id: String, kind: LogKind, marks: Int, walk: String? = session) = LogEntry(id, kind, "what $id", "why", day, marks, walkId = walk, contentId = "c-$id")

    private val earlier = listOf(e("start", LogKind.start_balance, 1_480, walk = null))
    private val walk = listOf(e("t", LogKind.treasure, 25), e("d", LogKind.day, 15))
    private val titles = mapOf("treasure-weekly-trails" to "Agate")

    @Test
    fun `pure content first then Play`() {
        val order = ResultsPlayLogic.order(play = true)
        assertEquals(ResultsPart.PLAY, order.last())
        assertEquals(ResultsPlayLogic.order(play = false), order.dropLast(1), "Play adds one block after all pure content, changing nothing before it")
        assertTrue(ResultsPart.PLAY !in ResultsPlayLogic.order(play = false))
        val section = assertNotNull(ResultsPlayLogic.section(session, Logbook(earlier + walk), result = null, titleOf = { titles[it] ?: it }, summaryShown = false))
        assertEquals(40, section.total)
        // Rule order: walk-derived lines (day) before finds.
        assertEquals(listOf(LogKind.day, LogKind.treasure), section.rows.map { it.kind })
        assertTrue(section.items.first() is ResultsPlayItem.Header)
        // A walk with no Play lines still shows the section (play_results_none).
        val empty = assertNotNull(ResultsPlayLogic.section(session, Logbook(earlier), null, { it }, summaryShown = false))
        assertEquals(listOf(ResultsPlayItem.Header(0), ResultsPlayItem.None), empty.items)
    }

    @Test
    fun `unclaimed reasons`() {
        val result = ClaimResult(
            claims = emptyList(),
            unclaimed = listOf(
                Unclaimed("treasure-weekly-trails@2026-10-26#03", "treasure-weekly-trails", UnclaimedReason.DARK),
                Unclaimed("treasure-weekly-trails@2026-10-26#03", "treasure-weekly-trails", UnclaimedReason.VEHICLE),
                Unclaimed("kraken-trails@2026-10-29#0", "kraken-trails", UnclaimedReason.TIDE),
            ),
            visits = emptyList(),
        )
        val section = assertNotNull(ResultsPlayLogic.section(session, Logbook(earlier + walk), result, { titles[it] ?: "The kraken" }, summaryShown = false))
        assertEquals(
            listOf(UnclaimedRow("treasure-weekly-trails@2026-10-26#03", "Agate", UnclaimedReason.DARK), UnclaimedRow("kraken-trails@2026-10-29#0", "The kraken", UnclaimedReason.TIDE)),
            section.unclaimed,
            "one line per item, first reason kept",
        )
        assertEquals("play_unclaimed_dark", section.unclaimed[0].reason.copyKey)
        val items = section.items
        val header = items.indexOf(ResultsPlayItem.UnclaimedHeader)
        assertTrue(header > items.indexOfLast { it is ResultsPlayItem.Row }, "Not claimed after the claimed lines")
        assertEquals(2, items.count { it is ResultsPlayItem.Unclaimed })
    }

    @Test
    fun `rank-up is a line not a modal`() {
        // 1,480 + 40 crosses Salal Scout (1,500).
        val section = assertNotNull(ResultsPlayLogic.section(session, Logbook(earlier + walk), null, { it }, summaryShown = false))
        assertEquals(Ranks.LADDER.single { it.title == "Salal Scout" }, section.rankUp)
        val ranks = section.items.filterIsInstance<ResultsPlayItem.RankUp>()
        assertEquals(1, ranks.size, "one RankUpLine inside the section, nowhere else")
        assertTrue(section.items.indexOf(ranks.single()) > section.items.indexOfLast { it is ResultsPlayItem.Row })
        // No rank-up: no line.
        val small = assertNotNull(ResultsPlayLogic.section(session, Logbook(listOf(e("t", LogKind.treasure, 25))), null, { it }, summaryShown = false))
        assertNull(small.rankUp)
        assertTrue(small.items.none { it is ResultsPlayItem.RankUp })
    }

    @Test
    fun `not shown again after summary_shown`() {
        assertNull(ResultsPlayLogic.section(session, Logbook(earlier + walk), null, { it }, summaryShown = true))
    }
}
