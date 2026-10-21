package com.bainbridgetrails.play.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The static WSF Seattle–Bainbridge timetable (schedule.md §1.7, SD-3): seasons with an inclusive date range, a
 * weekday and a weekend column per terminal, holiday dates that run the holiday column (or the weekend column when
 * the season has none), a crossing time, and a [validUntil] after which everything returns null (ferry-anchored items
 * fall back to their clock window; the ferry sprite hides). Times are island-local "HH:MM"; HH ≥ 24 is after midnight
 * on the same service day. Gameplay only needs ferries to within minutes; never used for "catch the ferry" copy.
 */
class FerryTimetable private constructor(private val doc: Doc) {
    enum class Terminal { SEATTLE, BAINBRIDGE }
    enum class Column { WEEKDAY, WEEKEND, HOLIDAY }

    /** [local] is the timetable string as published ("24:55" = 00:55 the next morning). */
    data class Sailing(val local: String, val depart: Instant, val arrive: Instant)
    data class Season(val id: String, val from: LocalDate, val to: LocalDate)

    val route: String get() = doc.route
    val crossingMin: Int get() = doc.crossingMin
    val validUntil: LocalDate = LocalDate.parse(doc.validUntil)
    /** True when the values are a placeholder, not copied from the WSF schedule. */
    val isPlaceholder: Boolean get() = doc.source == "placeholder"

    private val parsed: List<Pair<Season, Timetable>> = doc.timetables.map {
        Season(it.id, LocalDate.parse(it.from), LocalDate.parse(it.to)) to it
    }
    private val holidays: List<Set<LocalDate>> = doc.timetables.map { t -> (t.holidays + t.weekendDates).map(LocalDate::parse).toSet() }
    val seasons: List<Season> get() = parsed.map { it.first }

    /** The season covering [date], or null when none does or the timetable has expired. */
    fun seasonOf(date: LocalDate): Season? = indexOf(date)?.let { parsed[it].first }

    fun column(date: LocalDate): Column? {
        val i = indexOf(date) ?: return null
        return when {
            date in holidays[i] -> Column.HOLIDAY
            date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY -> Column.WEEKEND
            else -> Column.WEEKDAY
        }
    }

    /** Departures from [from] on service day [date], in time order; null when not covered or expired. */
    fun departures(date: LocalDate, from: Terminal): List<Sailing>? {
        val i = indexOf(date) ?: return null
        val t = parsed[i].second
        val col = when (column(date)!!) {
            Column.WEEKDAY -> t.weekday
            Column.WEEKEND -> t.weekend
            Column.HOLIDAY -> t.holiday ?: t.weekend
        }
        val times = if (from == Terminal.SEATTLE) col.fromSeattle else col.fromBainbridge
        return times.map { s ->
            val (h, m) = parseHhMm(s)
            val depart = IslandTime.instantOf(date.plus(h / 24, DateTimeUnit.DAY), LocalTime(h % 24, m))
            Sailing(s, depart, depart + crossingMin.minutes)
        }.sortedBy { it.depart }
    }

    private fun indexOf(date: LocalDate): Int? {
        if (date > validUntil) return null
        val i = parsed.indexOfFirst { (s, _) -> date >= s.from && date <= s.to }
        return if (i < 0) null else i
    }

    @Serializable
    private class Doc(
        val schema: Int = 1,
        val route: String,
        val crossingMin: Int,
        val source: String = "",
        val validUntil: String,
        val timetables: List<Timetable>,
    )

    @Serializable
    private class Timetable(
        val id: String,
        val from: String,
        val to: String,
        val weekday: Departures,
        val weekend: Departures,
        val holiday: Departures? = null,
        val holidays: List<String> = emptyList(),
        /** The pack's name for [holidays] (scripts/build-play-content.mjs `weekendDates`); both count. */
        val weekendDates: List<String> = emptyList(),
    )

    @Serializable
    private class Departures(val fromSeattle: List<String>, val fromBainbridge: List<String>)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): FerryTimetable = validated(json.decodeFromString(Doc.serializer(), text))

        /**
         * The timetable of a pack's [ferry] block (its raw `timetables`, valid until the last one's `to`); null when it
         * has none or it does not parse.
         */
        fun of(ferry: com.bainbridgetrails.play.config.Ferry?): FerryTimetable? = runCatching {
            if (ferry == null) return@runCatching null
            val tables = ferry.timetables.map { json.decodeFromJsonElement(Timetable.serializer(), it) }
            val validUntil = tables.maxOfOrNull { it.to } ?: return@runCatching null
            validated(Doc(route = ferry.route, crossingMin = ferry.crossingMin, validUntil = validUntil, timetables = tables))
        }.getOrNull()

        private fun validated(doc: Doc): FerryTimetable {
            require(doc.crossingMin in 1..180) { "crossingMin ${doc.crossingMin}" }
            for (t in doc.timetables) {
                require(LocalDate.parse(t.from) <= LocalDate.parse(t.to)) { "${t.id}: from > to" }
                for (s in t.weekday.fromSeattle + t.weekday.fromBainbridge + t.weekend.fromSeattle + t.weekend.fromBainbridge +
                    (t.holiday?.let { it.fromSeattle + it.fromBainbridge } ?: emptyList())) parseHhMm(s)
            }
            return FerryTimetable(doc)
        }

        private fun parseHhMm(s: String): Pair<Int, Int> {
            val parts = s.split(':')
            require(parts.size == 2 && parts[0].length == 2 && parts[1].length == 2) { "bad time $s" }
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            require(h in 0..29 && m in 0..59) { "bad time $s" }
            return h to m
        }
    }
}
