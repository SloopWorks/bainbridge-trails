package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.play.time.Sun
import com.bainbridgetrails.play.time.SunDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Sun events a [TimeExpr] may name (schedule.md §1.1); dawn/dusk are civil. */
enum class SunEvent(val id: String) { DAWN("dawn"), SUNRISE("sunrise"), NOON("noon"), SUNSET("sunset"), DUSK("dusk") }

/** `Dur := <int>("m"|"h"|"d")`, e.g. `30m`, `2h`, `1d`. */
object Dur {
    private val RE = Regex("^(\\d{1,4})([mhd])$")

    fun minutesOrNull(s: String): Int? {
        val m = RE.matchEntire(s) ?: return null
        val n = m.groupValues[1].toInt()
        return when (m.groupValues[2]) {
            "m" -> n
            "h" -> n * 60
            else -> n * 1440
        }
    }
}

/**
 * A time of day in island time (schedule.md §4.4):
 * `"HH:MM"` (00:00..24:00, 24:00 = the end of the day) · `dawn|sunrise|noon|sunset|dusk[(+|-)Dur]` ·
 * `"+Dur"` (a `to` relative to the resolved `from`).
 */
sealed interface TimeExpr {
    /** [minuteOfDay] 0..1440. */
    data class Clock(val minuteOfDay: Int) : TimeExpr

    data class SunRelative(val event: SunEvent, val offsetMin: Int) : TimeExpr

    /** `to` only. */
    data class AfterFrom(val minutes: Int) : TimeExpr

    /** False for [AfterFrom]: a `from` cannot be relative to itself. */
    val validAsFrom: Boolean get() = this !is AfterFrom

    /**
     * The instant this expression names on island [date]. [sun] defaults to the island's sun for [date]; [from] is
     * the resolved `from`, needed by [AfterFrom]. Null when the sun event does not occur or [from] is missing.
     */
    fun resolve(date: LocalDate, sun: SunDay? = null, from: Instant? = null, tz: TimeZone = IslandTime.zone): Instant? =
        when (this) {
            is Clock -> if (minuteOfDay == MINUTES_PER_DAY) {
                IslandTime.instantOf(date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0), tz)
            } else {
                IslandTime.instantOf(date, LocalTime(minuteOfDay / 60, minuteOfDay % 60), tz)
            }
            is SunRelative -> {
                val s = sun ?: Sun.sunEvents(date, tz = tz)
                val at = when (event) {
                    SunEvent.DAWN -> s.dawn
                    SunEvent.SUNRISE -> s.sunrise
                    SunEvent.NOON -> s.noon
                    SunEvent.SUNSET -> s.sunset
                    SunEvent.DUSK -> s.dusk
                }
                at?.plus(offsetMin.minutes)
            }
            is AfterFrom -> from?.plus(minutes.minutes)
        }

    companion object {
        const val MINUTES_PER_DAY = 1440
        private val CLOCK = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
        private val SUN = Regex("^(dawn|sunrise|noon|sunset|dusk)(?:([+-])(\\d{1,4}[mhd]))?$")
        private val RELATIVE = Regex("^\\+(\\d{1,4}[mhd])$")

        fun parseOrNull(s: String): TimeExpr? {
            if (s == "24:00") return Clock(MINUTES_PER_DAY)
            CLOCK.matchEntire(s)?.let { return Clock(it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()) }
            SUN.matchEntire(s)?.let { m ->
                val event = SunEvent.entries.first { it.id == m.groupValues[1] }
                val dur = m.groupValues[3]
                if (dur.isEmpty()) return SunRelative(event, 0)
                val mins = Dur.minutesOrNull(dur) ?: return null
                return SunRelative(event, if (m.groupValues[2] == "-") -mins else mins)
            }
            RELATIVE.matchEntire(s)?.let { m -> return Dur.minutesOrNull(m.groupValues[1])?.let { AfterFrom(it) } }
            return null
        }

        fun parse(s: String): TimeExpr = parseOrNull(s) ?: throw IllegalArgumentException("invalid TimeExpr '$s'")
    }
}
