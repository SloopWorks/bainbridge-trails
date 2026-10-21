package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.play.time.Sun
import com.bainbridgetrails.play.time.SunDay
import com.bainbridgetrails.play.time.TideTable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** One evaluated window `[start, end)`; [sub] is the anchored sub-window index (`#n`), null otherwise. */
data class OccurrenceWindow(val start: Instant, val end: Instant, val sub: Int? = null)

/** A server-subset occurrence (backend/src/play/recurrence.ts `Occurrence`); [end] null = open-ended "always". */
data class DateRuleOccurrence(val key: String, val itemId: String, val date: LocalDate, val start: Instant, val end: Instant?)

/**
 * The recurrence DSL (schedule.md §4.4): which island dates an occurrence starts on, and its window on that date.
 *
 * Date level ([startsOn]) is shared, rule for rule, with the Worker's subset (backend/src/play/recurrence.ts
 * `startsOn`) and the build script; `data/play/golden/date-rules-2027.json` pins the agreement ([dateRulesGolden]).
 * [windowsOn] is the device evaluation: sun-relative times, `+Dur`, `days`, and tide / ferry anchors. Neither applies
 * `rules.playHours`, daylight, quiet days, `enabled` or `kill`: the scheduler does (schedule.md §3.4).
 *
 * Pure over its arguments; reads no wall clock.
 */
object Recurrences {
    /** `except` and `fallback` nest at most this deep (a guard; the validator forbids window-in-window). */
    private const val MAX_DEPTH = 3

    /** Island quarters (PD-10, [com.bainbridgetrails.play.time.Season]), by month. */
    private val QUARTERS = listOf("storm", "strawberry", "madrona", "kraken")

    /** Does an occurrence of [rec] start on island [date]? (`every`, `on`, `in`, `dates`, `fallback`, `season`, `except`.) */
    fun startsOn(config: PlayConfig, rec: Recurrence, date: LocalDate): Boolean = startsOn(config, rec, date, 0)

    private fun startsOn(config: PlayConfig, rec: Recurrence, date: LocalDate, depth: Int): Boolean {
        val rules = rec.dayRules()
        val ok = when (rec.every) {
            null, Every.always -> return false
            Every.day -> true
            Every.week -> if (rec.on.isEmpty()) date.dayOfWeek == DayOfWeek.MONDAY
            else rules.any { it is DayRule.Weekday && it.day == date.dayOfWeek }
            Every.month, Every.year -> if (rec.on.isEmpty()) date.day == 1 else rules.any { matchesMonthRule(it, date) }
            Every.dates -> date in rec.dates || (
                rec.fallback != null && rec.dates.none { it.year == date.year } && startsOn(config, rec.fallback, date, depth + 1)
                )
        }
        if (!ok) return false
        if (rec.inMonths.isNotEmpty() && rec.inMonths.none { it.number == date.month.number }) return false
        if (rec.season.isNotEmpty()) {
            val quarter = QUARTERS[(date.month.number - 1) / 3]
            val climate = climateOf(config, date)
            if (rec.season.none { it == quarter || it == climate }) return false
        }
        if (rec.except.isNotEmpty() && depth < MAX_DEPTH) {
            for (w in rec.except) {
                val wr = config.windows[w] ?: continue
                for (k in 0 until (wr.days ?: 1)) if (startsOn(config, wr, date.minus(k, DateTimeUnit.DAY), depth + 1)) return false
            }
        }
        return true
    }

    /** The climate flavour of [date]: the first `climate` span containing its `MM-DD`, else `shoulder`. */
    fun climateOf(config: PlayConfig, date: LocalDate): String {
        val md = "${two(date.month.number)}-${two(date.day)}"
        for (c in config.climate) {
            val inSpan = if (c.from <= c.to) md >= c.from && md <= c.to else md >= c.from || md <= c.to
            if (inSpan) return c.id
        }
        return "shoulder"
    }

    fun matchesMonthRule(rule: DayRule, d: LocalDate): Boolean {
        val len = monthLength(d)
        return when (rule) {
            is DayRule.MonthDay -> if (rule.day == -1) d.day == len else d.day == rule.day
            is DayRule.NthWeekday -> d.dayOfWeek == rule.day && (if (rule.n == -1) d.day + 7 > len else (d.day - 1) / 7 + 1 == rule.n)
            is DayRule.Weekday -> false
        }
    }

    fun monthLength(d: LocalDate): Int {
        val first = LocalDate(d.year, d.month, 1)
        return first.daysUntil(first.plus(1, DateTimeUnit.MONTH))
    }

    /** Days an occurrence of [rec] can reach past its start date (date level): `days − 1`, or the `+Dur` span. */
    fun spanDays(rec: Recurrence): Int {
        val plus = (rec.to?.let(TimeExpr::parseOrNull) as? TimeExpr.AfterFrom)?.minutes ?: 0
        return maxOf((rec.days ?: 1) - 1, (plus + TimeExpr.MINUTES_PER_DAY - 1) / TimeExpr.MINUTES_PER_DAY)
    }

    /** True when an occurrence of [rec] started on [date] or on one of the previous `days − 1` dates (date level). */
    fun covers(config: PlayConfig, rec: Recurrence, date: LocalDate): Boolean =
        (0..((rec.days ?: 1) - 1)).any { startsOn(config, rec, date.minus(it, DateTimeUnit.DAY)) }

    /** A `quietDays` window (v1: `exclusion-remembrance`, Mar 30) covers [date]. */
    fun isQuietDay(config: PlayConfig, date: LocalDate): Boolean =
        config.quietDays.any { id -> config.windows[id]?.let { covers(config, it, date) } == true }

    // --- server subset ------------------------------------------------------------------------------------------

    /** True when the Worker can evaluate [rec]: no anchor, `HH:MM` from, `HH:MM` or `+Dur` to (recurrence.ts). */
    fun serverEvaluable(config: PlayConfig, rec: Recurrence): Boolean = serverEvaluable(config, rec, 0)

    private fun serverEvaluable(config: PlayConfig, rec: Recurrence, depth: Int): Boolean {
        if (rec.every == Every.always) return true
        if (rec.every == null) return false
        if (rec.anchor != null) return false
        if (clockMin(rec.from) == null || (clockMin(rec.to) == null && plusMin(rec.to) == null)) return false
        if (rec.fallback != null && !serverEvaluable(config, rec.fallback, depth + 1)) return false
        for (w in rec.except) {
            val wr = config.windows[w] ?: return false
            if (depth > 2 || !serverEvaluable(config, wr, depth + 1)) return false
        }
        return true
    }

    private fun clockMin(s: String?): Int? = (s?.let(TimeExpr::parseOrNull) as? TimeExpr.Clock)?.minuteOfDay
    private fun plusMin(s: String?): Int? = (s?.let(TimeExpr::parseOrNull) as? TimeExpr.AfterFrom)?.minutes

    /** "always" items: one occurrence keyed at `notBefore`, else the pack epoch (schedule.md §4.4). */
    fun alwaysDate(config: PlayConfig, item: Item): LocalDate? = item.notBefore ?: config.epoch

    /**
     * The server-subset occurrence of [item] starting on [date], or null (no start that day, outside
     * `notBefore..expires`, or not server-evaluable). Window = the authored `[from, to)`, `to` on the last of `days`.
     */
    fun occurrenceOn(config: PlayConfig, item: Item, date: LocalDate): DateRuleOccurrence? {
        val rec = config.effectiveWhen(item) ?: return null
        if (!serverEvaluable(config, rec)) return null
        val key = "${item.id}@$date"
        if (rec.every == Every.always) {
            if (date != alwaysDate(config, item)) return null
            return DateRuleOccurrence(key, item.id, date, wallInstant(date, 0), item.expires?.let { wallInstant(it, TimeExpr.MINUTES_PER_DAY) })
        }
        if (item.notBefore != null && date < item.notBefore) return null
        if (item.expires != null && date > item.expires) return null
        if (!startsOn(config, rec, date)) return null
        val from = clockMin(rec.from)!!
        val start = wallInstant(date, from)
        val plus = plusMin(rec.to)
        val end = if (plus != null) wallInstant(date, from + plus)
        else wallInstant(date.plus((rec.days ?: 1) - 1, DateTimeUnit.DAY), clockMin(rec.to)!!)
        if (end <= start) return null
        return DateRuleOccurrence(key, item.id, date, start, end)
    }

    // --- device evaluation --------------------------------------------------------------------------------------

    /**
     * The window(s) of the occurrence of [rec] starting on [date] (empty when none starts): the authored
     * `[from, to)` across `days`, then, with an anchor, one sub-window per qualifying event (`#n`) or the anchor's
     * fallback. A sun event that does not occur, or `from ≥ to`, gives no window. `every: always` has no windows
     * here (its window is the item's `notBefore`/`expires` life, see the scheduler).
     */
    fun windowsOn(
        config: PlayConfig,
        rec: Recurrence,
        date: LocalDate,
        sun: SunDay = Sun.sunEvents(date),
        tides: TideTable? = null,
        ferry: FerryTimetable? = null,
    ): List<OccurrenceWindow> {
        if (rec.every == null || rec.every == Every.always) return emptyList()
        if (!startsOn(config, rec, date)) return emptyList()
        val f = rec.from?.let(TimeExpr::parseOrNull)?.takeIf { it.validAsFrom } ?: return emptyList()
        val t = rec.to?.let(TimeExpr::parseOrNull) ?: return emptyList()
        val last = date.plus((rec.days ?: 1) - 1, DateTimeUnit.DAY)
        val start = resolve(f, date, sun) ?: return emptyList()
        val end = when (t) {
            is TimeExpr.AfterFrom -> if (f is TimeExpr.Clock) wallInstant(date, f.minuteOfDay + t.minutes) else start + t.minutes.minutes
            else -> resolve(t, last, if (last == date) sun else Sun.sunEvents(last)) ?: return emptyList()
        }
        if (end <= start) return emptyList()
        val anchor = rec.anchor ?: return listOf(OccurrenceWindow(start, end))
        val events = anchorEvents(anchor, date, start, end, tides, ferry)
        if (events.isNotEmpty()) {
            val before = Dur.minutesOrNull(anchor.before) ?: 0
            val after = Dur.minutesOrNull(anchor.after) ?: 0
            val out = ArrayList<OccurrenceWindow>(events.size)
            for (e in events) {
                val s = maxOf(start, e - before.minutes)
                val x = minOf(end, e + after.minutes)
                if (x > s) out += OccurrenceWindow(s, x, out.size)
            }
            if (out.isNotEmpty()) return out
        }
        val fb = anchor.fallback ?: return emptyList()
        val fs = TimeExpr.parseOrNull(fb.from)?.takeIf { it.validAsFrom }?.let { resolve(it, date, sun) } ?: return emptyList()
        val fe = when (val te = TimeExpr.parseOrNull(fb.to)) {
            null -> return emptyList()
            is TimeExpr.AfterFrom -> fs + te.minutes.minutes
            else -> resolve(te, date, sun) ?: return emptyList()
        }
        return if (fe > fs) listOf(OccurrenceWindow(fs, fe)) else emptyList()
    }

    private fun resolve(e: TimeExpr, date: LocalDate, sun: SunDay): Instant? = when (e) {
        is TimeExpr.Clock -> wallInstant(date, e.minuteOfDay)
        else -> e.resolve(date, sun)
    }

    /** Qualifying anchor events of [date] inside `[start, end]`, picked and capped, in time order. */
    private fun anchorEvents(a: Anchor, date: LocalDate, start: Instant, end: Instant, tides: TideTable?, ferry: FerryTimetable?): List<Instant> {
        val inWindow = { t: Instant -> t >= start && t <= end }
        val picked: List<Instant> = if (a.at.isTide) {
            val evs = tides?.events(date) ?: return emptyList()
            val wantHigh = a.at == AnchorAt.tide_high
            val ok = evs.filter { it.high == wantHigh && inWindow(it.at) }
                .filter { (a.minHeightM == null || it.heightM >= a.minHeightM) && (a.maxHeightM == null || it.heightM <= a.maxHeightM) }
            when (a.pick) {
                AnchorPick.highest -> listOfNotNull(ok.maxByOrNull { it.heightM }?.at)
                AnchorPick.lowest -> listOfNotNull(ok.minByOrNull { it.heightM }?.at)
                AnchorPick.all, null -> ok.map { it.at }
            }
        } else {
            val tt = ferry ?: return emptyList()
            val times = when (a.at) {
                AnchorAt.ferry_depart_bainbridge -> tt.departures(date, FerryTimetable.Terminal.BAINBRIDGE)?.map { it.depart }
                else -> tt.departures(date, FerryTimetable.Terminal.SEATTLE)?.map { it.arrive }
            } ?: return emptyList()
            val ok = times.filter(inWindow)
            when (a.pick) {
                AnchorPick.highest, AnchorPick.lowest -> ok.take(1)
                AnchorPick.all, null -> ok
            }
        }
        val sorted = picked.sorted()
        return a.max?.let { sorted.take(it) } ?: sorted
    }

    // --- island wall time ---------------------------------------------------------------------------------------

    /**
     * The instant of island-local [date] plus [minutes] of wall clock (may exceed a day: `05:00 + 6d`; 1440 = the next
     * day 00:00). A gap time moves forward to the next valid instant, an overlap takes its first instance
     * ([IslandTime.instantOf]); recurrence.ts `islandInstant`.
     */
    fun wallInstant(date: LocalDate, minutes: Int): Instant {
        val d = date.plus(minutes.floorDiv(TimeExpr.MINUTES_PER_DAY), DateTimeUnit.DAY)
        val m = minutes.mod(TimeExpr.MINUTES_PER_DAY)
        return IslandTime.instantOf(d, LocalTime(m / 60, m % 60))
    }

    /** `2027-03-14T20:00-07:00`: island wall clock to the minute with its UTC offset (recurrence.ts `islandIso`). */
    fun islandIso(instant: Instant): String {
        val l = instant.toLocalDateTime(IslandTime.zone)
        val off = IslandTime.zone.offsetAt(instant).totalSeconds / 60
        val sign = if (off < 0) "-" else "+"
        val a = abs(off)
        return "${l.date}T${two(l.hour)}:${two(l.minute)}$sign${two(a / 60)}:${two(a % 60)}"
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    // --- golden -------------------------------------------------------------------------------------------------

    /**
     * `data/play/golden/date-rules-2027.json` (recurrence.ts `dateRulesGolden`): every server-evaluable item of
     * [config] (enabled or not) × every island date of [year], the occurrence starting that day as `{occ, start, end}`
     * (`end` null when open), sorted by `occ` in code-unit order, `JSON.stringify(…, null, 2)` + a trailing newline.
     */
    fun dateRulesGolden(config: PlayConfig, year: Int): String {
        val rows = ArrayList<Triple<String, String, String?>>()
        val first = LocalDate(year, 1, 1)
        val days = first.daysUntil(LocalDate(year + 1, 1, 1))
        for (item in config.items) for (i in 0 until days) {
            val o = occurrenceOn(config, item, first.plus(i, DateTimeUnit.DAY)) ?: continue
            rows += Triple(o.key, islandIso(o.start), o.end?.let(::islandIso))
        }
        rows.sortWith { a, b -> a.first.compareTo(b.first) } // String.compareTo = UTF-16 code-unit order, like JS `<`
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"pack\": ").append(jsonString(config.version)).append(",\n")
        sb.append("  \"tz\": ").append(jsonString(config.tz)).append(",\n")
        sb.append("  \"year\": ").append(year).append(",\n")
        if (rows.isEmpty()) {
            sb.append("  \"occurrences\": []\n")
        } else {
            sb.append("  \"occurrences\": [\n")
            rows.forEachIndexed { i, (occ, start, end) ->
                sb.append("    {\n")
                sb.append("      \"occ\": ").append(jsonString(occ)).append(",\n")
                sb.append("      \"start\": ").append(jsonString(start)).append(",\n")
                sb.append("      \"end\": ").append(end?.let(::jsonString) ?: "null").append('\n')
                sb.append("    }").append(if (i < rows.lastIndex) ",\n" else "\n")
            }
            sb.append("  ]\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch == '\b' -> append("\\b")
            ch == '\u000C' -> append("\\f")
            ch < ' ' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> append(ch)
        }
        append('"')
    }
}
