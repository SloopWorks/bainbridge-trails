package com.bainbridgetrails.ui.play.screens

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Rank
import com.bainbridgetrails.play.rules.Ranks
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_log_climb_what
import com.bainbridgetrails.resources.play_log_mile_what
import com.bainbridgetrails.ui.Format
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.play.components.TrailRewardFormat
import com.bainbridgetrails.ui.play.components.TrailValue
import com.bainbridgetrails.ui.play.record.ResultsPlayLogic
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource

/** What a Logbook line shows at its end (ux.md § 2.6). */
sealed interface LogTrailing {
    /** `+n` (`play_log_line`); [muted] = `onSurfaceVariant` (local only, daily cap), else `play.seaGlass`. */
    data class Marks(val marks: Int, val muted: Boolean) : LogTrailing

    /** Armchair stamp: the stamp glyph + `play_log_stamp`, no number. */
    data object Stamp : LogTrailing

    /** Remembrance visit: nothing at all (product § 3.8). */
    data object None : LogTrailing
}

/** The line under a Logbook line: [Cap] replaces `why` with `play_log_cap`; [LocalOnly] adds `play_log_local_only`. */
enum class LogNote { Cap, LocalOnly }

/**
 * One Logbook line. [what] is the persisted text; [value] (trails `climb` / `milestone` only, trails ux § 2.2) is the
 * step re-rendered from the entry's `contentId` in the Settings unit, which the row shows through
 * [LogbookLogic.whatRes] instead of [what] (null: show [what]).
 */
data class LogRowModel(
    val id: String,
    val kind: LogKind,
    val what: String,
    val why: String,
    val trailing: LogTrailing,
    val note: LogNote?,
    val value: TrailValue? = null,
)

/** One group under a day header (product § 3.8 display rules). */
sealed interface LogbookGroup {
    val rows: List<LogRowModel>

    /** A walk's entries under "Walk · 2.0 km" › WalkDetail. */
    data class Walk(val walkId: String, override val rows: List<LogRowModel>) : LogbookGroup

    /** Week close entries (`week`, `retreat`, island challenges) on the Sunday: `play_log_week_close`, no chevron. */
    data class WeekClose(override val rows: List<LogRowModel>) : LogbookGroup

    /** Entries of no walk: the start balance, a restore, armchair stamps. */
    data class Loose(override val rows: List<LogRowModel>) : LogbookGroup
}

data class LogbookDay(val date: LocalDate, val groups: List<LogbookGroup>)

/** `LogbookTotals` (ux.md § 3.13): all-time, this week, rank and progress to the next. */
data class LogbookTotals(val total: Int, val week: Int, val rank: Rank, val next: Rank?, val toNext: Int?) {
    /** 0..1 progress from [rank]'s floor to [next]'s (1 at the top). */
    val fraction: Float get() = next?.let { n -> ((total - rank.from).toFloat() / (n.from - rank.from)).coerceIn(0f, 1f) } ?: 1f

    companion object {
        fun of(total: Int, week: Int) = LogbookTotals(total, week, Ranks.of(total), Ranks.next(total), Ranks.toNext(total))
    }
}

data class LogbookModel(val totals: LogbookTotals, val days: List<LogbookDay>, val more: Boolean)

/**
 * The Logbook screen (ux.md § 3.13, product § 3.8, PF11): days newest first; within a day, a walk's entries grouped under
 * that walk, the later walk first; week-close entries their own group; dates only, never a time of day (the model
 * carries island dates; nothing here reads a clock). Entries are append-only, so a later index is a newer entry.
 * Totals always sum every entry (PF11-4), paging only limits what is listed ([limit] newest entries). Inside a walk
 * group the walk's rule lines follow the results order ([ResultsPlayLogic.WALK_ORDER], trails ux § 2.2); finds keep
 * their place. [units] re-renders the trails climb / trail-mile lines ([row]).
 */
object LogbookLogic {
    const val PAGE = 50

    private val WEEK_CLOSE = setOf(LogKind.week, LogKind.retreat, LogKind.island_challenge)

    fun model(entries: List<LogEntry>, today: LocalDate, limit: Int = Int.MAX_VALUE, units: Units? = null): LogbookModel {
        val thisWeek = IslandTime.weekId(today)
        val totals = LogbookTotals.of(entries.sumOf { it.marks }, entries.filter { it.weekId == thisWeek }.sumOf { it.marks })
        val shown = entries.withIndex().toList().takeLast(limit.coerceAtLeast(0))
        val days = shown.groupBy { it.value.date }.entries
            .sortedByDescending { it.key }
            .map { (date, dayEntries) -> LogbookDay(date, groups(dayEntries, units)) }
        return LogbookModel(totals, days, more = shown.size < entries.size)
    }

    private fun groups(dayEntries: List<IndexedValue<LogEntry>>, units: Units?): List<LogbookGroup> {
        val keyed = dayEntries.groupBy { (_, e) ->
            when {
                e.kind in WEEK_CLOSE && e.walkId == null -> "week"
                e.walkId != null -> "walk:${e.walkId}"
                else -> "loose"
            }
        }
        return keyed.entries
            .sortedByDescending { (_, es) -> es.maxOf { it.index } }
            .map { (key, es) ->
                val logged = es.sortedBy { it.index }.map { it.value }
                when {
                    key == "week" -> LogbookGroup.WeekClose(logged.map { row(it, units) })
                    key.startsWith("walk:") -> LogbookGroup.Walk(key.removePrefix("walk:"), inResultsOrder(logged).map { row(it, units) })
                    else -> LogbookGroup.Loose(logged.map { row(it, units) })
                }
            }
    }

    /**
     * A walk's [logged] entries with its rule lines ([ResultsPlayLogic.WALK_ORDER] kinds) in results order, each in a
     * slot one of them held; every other line (finds, challenges, visits) keeps its slot.
     */
    private fun inResultsOrder(logged: List<LogEntry>): List<LogEntry> {
        val ruled = logged.filter { it.kind in ResultsPlayLogic.WALK_ORDER }.sortedBy { ResultsPlayLogic.rank(it.kind) }.iterator()
        return logged.map { if (it.kind in ResultsPlayLogic.WALK_ORDER) ruled.next() else it }
    }

    /**
     * [e]'s line. With [units], a trails `climb` (`climb:<m>`) or `milestone` (`mile:<mi>`) entry carries its step
     * re-rendered from the `contentId` through [TrailRewardFormat] (trails ux § 2.2: "820 ft" for `climb:250` in
     * miles); an unparseable key, a step not on the rules' ladder ([TrailRewards.CLIMB_STEPS_M] /
     * [TrailRewards.MILE_STEPS]), another kind, or no [units] keeps the persisted what. Roads never writes these keys.
     */
    fun row(e: LogEntry, units: Units? = null): LogRowModel {
        val localOnly = e.board == BoardState.local_only
        val trailing = when (e.kind) {
            LogKind.visit -> LogTrailing.None
            LogKind.stamp -> LogTrailing.Stamp
            else -> LogTrailing.Marks(e.marks, muted = localOnly || e.capped)
        }
        val note = when {
            e.kind == LogKind.visit -> null
            e.capped -> LogNote.Cap
            localOnly -> LogNote.LocalOnly
            else -> null
        }
        return LogRowModel(e.id, e.kind, e.what, e.why, trailing, note, units?.let { trailValue(e, it) })
    }

    /** The template a re-rendered what fills with its value: `play_log_climb_what` / `play_log_mile_what`; null otherwise. */
    fun whatRes(kind: LogKind): StringResource? = when (kind) {
        LogKind.climb -> Res.string.play_log_climb_what
        LogKind.milestone -> Res.string.play_log_mile_what
        else -> null
    }

    private fun trailValue(e: LogEntry, units: Units): TrailValue? {
        val key = e.contentId ?: return null
        val (prefix, ladder) = when (e.kind) {
            LogKind.climb -> "climb:" to TrailRewards.CLIMB_STEPS_M
            LogKind.milestone -> "mile:" to TrailRewards.MILE_STEPS
            else -> return null
        }
        if (!key.startsWith(prefix)) return null
        // Only a step of the rules' own ladder re-renders (final review P2-3: tampered / corrupt state keeps its what).
        val step = key.removePrefix(prefix).toIntOrNull()?.takeIf { it in ladder } ?: return null
        return if (e.kind == LogKind.climb) TrailRewardFormat.climbStep(step.toDouble(), units) else TrailRewardFormat.mileStep(step, units)
    }

    /** Day header: "Sun, Nov 1" (date only, PF11-3). */
    fun dayLabel(date: LocalDate, today: LocalDate): String = PlayFormat.date(date, today)

    /** Walk subheader: `play_log_walk_group` ([template]) with the walk's distance, or "Walk" when it is unknown. */
    fun walkLabel(template: String, distanceM: Double?, units: Units): String =
        if (distanceM == null) template.substringBefore(" ·").substringBefore("%1\$s").trim() else PlayFormat.fill(template, Format.distance(distanceM, units))
}
