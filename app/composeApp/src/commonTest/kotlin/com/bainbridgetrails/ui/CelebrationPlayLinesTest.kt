package com.bainbridgetrails.ui

import com.bainbridgetrails.play.rules.Ranks
import com.bainbridgetrails.ui.play.record.ResultsPlaySection
import kotlin.test.Test
import kotlin.test.assertEquals

/** Celebration lines (ux.md §3.10): at most two, marks then rank-up; none with Play off or nothing earned. */
class CelebrationPlayLinesTest {
    private val rank = Ranks.of(1_000)

    @Test
    fun marksThenRankUp() {
        val s = ResultsPlaySection(emptyList(), total = 300, rankUp = rank, unclaimed = emptyList())
        assertEquals(listOf(CelebrationPlayLine.Marks(300), CelebrationPlayLine.RankUp(rank)), celebrationPlayLines(s))
    }

    @Test
    fun noLinesWithoutMarksOrPlay() {
        assertEquals(emptyList(), celebrationPlayLines(null))
        assertEquals(emptyList(), celebrationPlayLines(ResultsPlaySection(emptyList(), total = 0, rankUp = null, unclaimed = emptyList())))
        assertEquals(1, celebrationPlayLines(ResultsPlaySection(emptyList(), total = 40, rankUp = null, unclaimed = emptyList())).size)
    }
}
