package com.bainbridgetrails.ui.play.record

import com.bainbridgetrails.data.Units
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

    /**
     * `play_results_more` (plural on [count]): "+3 more in your Logbook" after the first [ResultsPlayLogic.MAX_ROWS] − 1
     * rows (trails ux § 3); non-interactive, adds nothing to the header announcement.
     */
    data class More(val count: Int) : ResultsPlayItem

    /** `play_results_none`: a walk with no Play lines. */
    data object None : ResultsPlayItem

    /** RankUpLine: a line in the section, never a modal (PF6-3). */
    data class RankUp(val rank: Rank) : ResultsPlayItem

    data object UnclaimedHeader : ResultsPlayItem

    data class Unclaimed(val row: UnclaimedRow) : ResultsPlayItem
}

/**
 * One walk's Play section. [rows] are all of the walk's lines in results order and [total] counts them all; [items]
 * collapses them (trails ux § 3): at most [ResultsPlayLogic.MAX_ROWS] lines, so more than that shows the first
 * MAX_ROWS − 1 and one [ResultsPlayItem.More] line.
 */
data class ResultsPlaySection(val rows: List<LogRowModel>, val total: Int, val rankUp: Rank?, val unclaimed: List<UnclaimedRow>) {
    val items: List<ResultsPlayItem> = buildList {
        add(ResultsPlayItem.Header(total))
        val max = ResultsPlayLogic.MAX_ROWS
        when {
            rows.isEmpty() -> add(ResultsPlayItem.None)
            rows.size <= max -> rows.forEach { add(ResultsPlayItem.Row(it)) }
            else -> {
                rows.take(max - 1).forEach { add(ResultsPlayItem.Row(it)) }
                add(ResultsPlayItem.More(rows.size - (max - 1)))
            }
        }
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

    /** The section's most lines under the header (= the first-show stagger cap, ux.md § 1.4; trails ux § 3). */
    const val MAX_ROWS = 8

    /**
     * The walk's own rule lines in results order (ux.md §3.9 + trails ux § 3): coverage, completions, the trails rules
     * (high ground after complete, climb before milestone), squares, day, week. The Logbook orders a walk group's
     * lines of these kinds the same way ([LogbookLogic]).
     */
    val WALK_ORDER: List<LogKind> = listOf(
        LogKind.coverage, LogKind.complete, LogKind.high_ground, LogKind.park_first, LogKind.park_complete, LogKind.climb,
        LogKind.milestone, LogKind.island, LogKind.start_trails, LogKind.square, LogKind.day, LogKind.week,
    )

    /** Rule order of the lines: [WALK_ORDER], then finds and challenges. */
    private val RULE_ORDER: List<LogKind> = WALK_ORDER + listOf(
        LogKind.treasure, LogKind.egg, LogKind.riddle, LogKind.ink, LogKind.lore, LogKind.horn, LogKind.bramble, LogKind.stamp, LogKind.visit,
        LogKind.retreat, LogKind.challenge, LogKind.island_challenge, LogKind.wave,
    )

    /** [kind]'s position in the results order (unknown kinds last). */
    fun rank(kind: LogKind): Int = RULE_ORDER.indexOf(kind).let { if (it < 0) RULE_ORDER.size else it }

    /**
     * [sessionId]'s section, or null when this summary was already shown ([summaryShown], PF6-4). Lines are the
     * Logbook entries of the walk (their marks are the client's display values; the server derives the real ones on
     * sync, PD-3); [rankUp] when the walk's marks crossed a rank; [result]'s unclaimed items once each, first reason kept.
     * [units] re-renders the climb / trail-mile lines (trails ux § 2.2); null keeps their persisted what.
     */
    fun section(
        sessionId: String,
        logbook: Logbook,
        result: ClaimResult?,
        titleOf: (itemId: String) -> String,
        summaryShown: Boolean,
        units: Units? = null,
    ): ResultsPlaySection? {
        if (summaryShown) return null
        val entries = logbook.entries.filter { it.walkId == sessionId }
            .sortedBy { rank(it.kind) }
        val total = entries.sumOf { it.marks }
        val after = logbook.total
        val rankUp = Ranks.rankUp(after - total, after)
        val claimed = entries.mapNotNullTo(HashSet()) { it.contentId }
        val unclaimed = result?.unclaimed.orEmpty()
            .filter { it.key !in claimed }
            .distinctBy { it.key }
            .map { UnclaimedRow(it.key, titleOf(it.itemId), it.reason) }
        return ResultsPlaySection(entries.map { LogbookLogic.row(it, units) }, total, rankUp, unclaimed)
    }
}
