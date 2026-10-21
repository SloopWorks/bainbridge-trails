package com.bainbridgetrails.ui.play.components

import com.bainbridgetrails.play.time.IslandTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Play number and date words (product § 3.1; ux.md § 1.2). Pure, so host tests read exactly what screens show. Dates
 * and times are island time (schedule § 9); the Logbook uses dates only (PF11-3).
 */
object PlayFormat {
    /** Positional `%n$s` / `%n$d` substitution for `play_*` templates resolved outside composition (pure logic). */
    fun fill(template: String, vararg args: Any): String {
        var out = template
        args.forEachIndexed { i, a ->
            val n = i + 1
            out = out.replace("%$n\$s", a.toString()).replace("%$n\$d", a.toString())
        }
        return out
    }

    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "1,240": marks with a thousands separator, never abbreviated (the Play chip's `1.2k` is L2-8's). */
    fun marks(n: Int): String {
        val s = kotlin.math.abs(n).toString()
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return if (n < 0) "-$out" else out.toString()
    }

    /** "Thu" (island day of [at]). */
    fun day(at: Instant): String = day(IslandTime.dateOf(at))

    fun day(d: LocalDate): String = DAYS[d.dayOfWeek.ordinal]

    /** "Sun, Nov 1"; the year is added when it isn't [today]'s ("Sat, Dec 13, 2025"). */
    fun date(d: LocalDate, today: LocalDate): String {
        val base = "${DAYS[d.dayOfWeek.ordinal]}, ${MONTHS[d.month.ordinal]} ${d.day}"
        return if (d.year == today.year) base else "$base, ${d.year}"
    }

    /** "Oct 26" (What's On week line). */
    fun monthDay(d: LocalDate): String = "${MONTHS[d.month.ordinal]} ${d.day}"

    /** Island clock time: "6 am", "7:31 am", "12 pm". */
    fun time(at: Instant): String {
        val t = at.toLocalDateTime(IslandTime.zone)
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val ampm = if (t.hour < 12) "am" else "pm"
        return if (t.minute == 0) "$h12 $ampm" else "$h12:${t.minute.toString().padStart(2, '0')} $ampm"
    }

    /** True when the phone's zone shows a different clock from the island's at [at] (schedule § 9). */
    fun phoneDiffers(phone: TimeZone, at: Instant): Boolean = phone.offsetAt(at) != IslandTime.zone.offsetAt(at)

    /** [text] + `play_island_time` ([template], "%1$s island time") when the phone's zone differs at [at]. */
    fun islandSuffix(text: String, phone: TimeZone, at: Instant, template: String): String =
        if (phoneDiffers(phone, at)) fill(template, text) else text
}
