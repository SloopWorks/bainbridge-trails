package com.bainbridgetrails.ui.play.screens

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Rank
import com.bainbridgetrails.play.rules.Ranks
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.ui.Format
import com.bainbridgetrails.ui.play.components.PlayFormat
import kotlinx.datetime.LocalDate

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

data class LogRowModel(
    val id: String,
    val kind: LogKind,
    val what: String,
    val why: String,
    val trailing: LogTrailing,
    val note: LogNote?,
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
 * Totals always sum every entry (PF11-4), paging only limits what is listed ([limit] newest entries).
 */
object LogbookLogic {
    const val PAGE = 50

    private val WEEK_CLOSE = setOf(LogKind.week, LogKind.retreat, LogKind.island_challenge)

    fun model(entries: List<LogEntry>, today: LocalDate, limit: Int = Int.MAX_VALUE): LogbookModel {
        val thisWeek = IslandTime.weekId(today)
        val totals = LogbookTotals.of(entries.sumOf { it.marks }, entries.filter { it.weekId == thisWeek }.sumOf { it.marks })
        val shown = entries.withIndex().toList().takeLast(limit.coerceAtLeast(0))
        val days = shown.groupBy { it.value.date }.entries
            .sortedByDescending { it.key }
            .map { (date, dayEntries) -> LogbookDay(date, groups(dayEntries)) }
        return LogbookModel(totals, days, more = shown.size < entries.size)
    }

    private fun groups(dayEntries: List<IndexedValue<LogEntry>>): List<LogbookGroup> {
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
                val rows = es.sortedBy { it.index }.map { row(it.value) }
                when {
                    key == "week" -> LogbookGroup.WeekClose(rows)
                    key.startsWith("walk:") -> LogbookGroup.Walk(key.removePrefix("walk:"), rows)
                    else -> LogbookGroup.Loose(rows)
                }
            }
    }

    fun row(e: LogEntry): LogRowModel {
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
        return LogRowModel(e.id, e.kind, e.what, e.why, trailing, note)
    }

    /** Day header: "Sun, Nov 1" (date only, PF11-3). */
    fun dayLabel(date: LocalDate, today: LocalDate): String = PlayFormat.date(date, today)

    /** Walk subheader: `play_log_walk_group` ([template]) with the walk's distance, or "Walk" when it is unknown. */
    fun walkLabel(template: String, distanceM: Double?, units: Units): String =
        if (distanceM == null) template.substringBefore(" ·").substringBefore("%1\$s").trim() else PlayFormat.fill(template, Format.distance(distanceM, units))
}
