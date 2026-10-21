package com.bainbridgetrails.ui.play.record

import com.bainbridgetrails.play.claim.ClaimResult
import com.bainbridgetrails.play.claim.UnclaimedReason
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.Rank
import com.bainbridgetrails.play.rules.Ranks
import com.bainbridgetrails.ui.play.screens.LogRowModel
import com.bainbridgetrails.ui.play.screens.LogbookLogic

/**
 * The results sheet's blocks in order (ux.md §3.5f, §3.9). Play is one block after all the pure content (PF6-1);
 * the pure order is unchanged by it.
 */
enum class ResultsPart { RECOVERED, MILESTONES, HEADLINE, NEW_MILES, ISLAND, VERIFICATION_BANNERS, TRAILS, DETAILS, VERIFIED, SYNC, PLAY }

/** An item the walk reached but did not claim, with its reason (`play_unclaimed_*`, PF6-2); never a number. */
data class UnclaimedRow(val key: String, val what: String, val reason: UnclaimedReason)

/** The Play section's lines, in display order (ux.md §3.9). */
sealed interface ResultsPlayItem {
    /** `play_results_header` + `play_results_total` (polite live region, once). */
    data class Header(val total: Int) : ResultsPlayItem

    data class Row(val row: LogRowModel) : ResultsPlayItem

    /** `play_results_none`: a walk with no Play lines. */
    data object None : ResultsPlayItem

    /** RankUpLine: a line in the section, never a modal (PF6-3). */
    data class RankUp(val rank: Rank) : ResultsPlayItem

    data object UnclaimedHeader : ResultsPlayItem

    data class Unclaimed(val row: UnclaimedRow) : ResultsPlayItem
}

data class ResultsPlaySection(val rows: List<LogRowModel>, val total: Int, val rankUp: Rank?, val unclaimed: List<UnclaimedRow>) {
    val items: List<ResultsPlayItem> = buildList {
        add(ResultsPlayItem.Header(total))
        if (rows.isEmpty()) add(ResultsPlayItem.None) else rows.forEach { add(ResultsPlayItem.Row(it)) }
        rankUp?.let { add(ResultsPlayItem.RankUp(it)) }
        if (unclaimed.isNotEmpty()) {
            add(ResultsPlayItem.UnclaimedHeader)
            unclaimed.forEach { add(ResultsPlayItem.Unclaimed(it)) }
        }
    }
}

/** The Play section of one walk's results (product PF6, ux.md §3.9): pure, from the Logbook and the claim pass. */
object ResultsPlayLogic {
    private val PURE = listOf(
        ResultsPart.RECOVERED, ResultsPart.MILESTONES, ResultsPart.HEADLINE, ResultsPart.NEW_MILES, ResultsPart.ISLAND,
        ResultsPart.VERIFICATION_BANNERS, ResultsPart.TRAILS, ResultsPart.DETAILS, ResultsPart.VERIFIED, ResultsPart.SYNC,
    )

    fun order(play: Boolean): List<ResultsPart> = if (play) PURE + ResultsPart.PLAY else PURE

    /** Rule order of the lines (ux.md §3.9): coverage, completions, squares, day, finds, challenges. */
    private val RULE_ORDER: List<LogKind> = listOf(
        LogKind.coverage, LogKind.complete, LogKind.park_first, LogKind.park_complete, LogKind.milestone, LogKind.island,
        LogKind.square, LogKind.day, LogKind.week,
        LogKind.treasure, LogKind.egg, LogKind.riddle, LogKind.ink, LogKind.lore, LogKind.horn, LogKind.bramble, LogKind.stamp, LogKind.visit,
        LogKind.retreat, LogKind.challenge, LogKind.island_challenge, LogKind.wave,
    )

    /**
     * [sessionId]'s section, or null when this summary was already shown ([summaryShown], PF6-4). Lines are the
     * Logbook entries of the walk (their marks are the client's display values; the server derives the real ones on
     * sync, PD-3); [rankUp] when the walk's marks crossed a rank; [result]'s unclaimed items once each, first reason kept.
     */
    fun section(
        sessionId: String,
        logbook: Logbook,
        result: ClaimResult?,
        titleOf: (itemId: String) -> String,
        summaryShown: Boolean,
    ): ResultsPlaySection? {
        if (summaryShown) return null
        val entries = logbook.entries.filter { it.walkId == sessionId }
            .sortedBy { RULE_ORDER.indexOf(it.kind).let { i -> if (i < 0) RULE_ORDER.size else i } }
        val total = entries.sumOf { it.marks }
        val after = logbook.total
        val rankUp = Ranks.rankUp(after - total, after)
        val claimed = entries.mapNotNullTo(HashSet()) { it.contentId }
        val unclaimed = result?.unclaimed.orEmpty()
            .filter { it.key !in claimed }
            .distinctBy { it.key }
            .map { UnclaimedRow(it.key, titleOf(it.itemId), it.reason) }
        return ResultsPlaySection(entries.map(LogbookLogic::row), total, rankUp, unclaimed)
    }
}
