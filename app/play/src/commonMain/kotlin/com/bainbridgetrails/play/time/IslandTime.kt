package com.bainbridgetrails.play.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Season = island-time quarter (PD-10, schedule.md §1.3); [id] is the config/DSL keyword. */
enum class Season(val id: String) { STORM("storm"), STRAWBERRY("strawberry"), MADRONA("madrona"), KRAKEN("kraken") }

/**
 * Island time (schedule.md §1, §1.2): every Play calendar computation runs in America/Los_Angeles, whatever the
 * phone's zone. Week = [Monday 00:00, next Monday 00:00) island time; week id = the Monday's date.
 */
object IslandTime {
    val zone: TimeZone = TimeZone.of("America/Los_Angeles")

    /**
     * The instant of island-local [date] [time]. A time in a spring-forward gap moves forward to the next valid
     * instant (02:30 → 03:00); a time in a fall-back overlap resolves to its first instance (the earlier offset).
     */
    fun instantOf(date: LocalDate, time: LocalTime, tz: TimeZone = zone): Instant {
        val ldt = LocalDateTime(date, time)
        firstInstanceOf(ldt, tz)?.let { return it }
        // Gap: walk forward minute by minute (gaps are whole minutes, ≤ 2 h in practice) to the first valid local time.
        var cand = LocalDateTime(date, LocalTime(time.hour, time.minute))
        repeat(GAP_SEARCH_MINUTES) {
            cand = (cand.toInstant(TimeZone.UTC) + 1.minutes).toLocalDateTime(TimeZone.UTC)
            firstInstanceOf(cand, tz)?.let { return it }
        }
        error("no valid instant within ${GAP_SEARCH_MINUTES / 60} h after $ldt in $tz")
    }

    /** Earliest instant whose local time in [tz] is [ldt], or null when [ldt] falls in a gap. */
    private fun firstInstanceOf(ldt: LocalDateTime, tz: TimeZone): Instant? {
        val guess = ldt.toInstant(tz)
        var best: Instant? = null
        for (probe in arrayOf(guess - 6.hours, guess, guess + 6.hours)) {
            val i = ldt.toInstant(tz.offsetAt(probe))
            if (i.toLocalDateTime(tz) == ldt && (best == null || i < best)) best = i
        }
        return best
    }

    fun dateOf(instant: Instant, tz: TimeZone = zone): LocalDate = instant.toLocalDateTime(tz).date

    /** Week id: the Monday of [date]'s week. */
    fun weekId(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    fun weekId(instant: Instant): LocalDate = weekId(dateOf(instant))

    fun season(date: LocalDate): Season = when (date.month.ordinal / 3) {
        0 -> Season.STORM
        1 -> Season.STRAWBERRY
        2 -> Season.MADRONA
        else -> Season.KRAKEN
    }

    /** A week's season is the quarter of its Thursday. */
    fun seasonOfWeek(weekId: LocalDate): Season = season(weekId(weekId).plus(3, DateTimeUnit.DAY))

    private const val GAP_SEARCH_MINUTES = 3 * 60
}
