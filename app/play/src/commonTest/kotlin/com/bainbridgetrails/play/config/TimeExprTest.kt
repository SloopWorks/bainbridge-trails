package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.play.time.Sun
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** schedule.md §4.4 TimeExpr grammar. */
class TimeExprTest {
    private val day = LocalDate(2027, 6, 15)
    private val sun = Sun.sunEvents(day)

    @Test
    fun hhMm() {
        val e = TimeExpr.parse("06:30")
        assertEquals(TimeExpr.Clock(6 * 60 + 30), e)
        assertEquals(IslandTime.instantOf(day, LocalTime(6, 30)), e.resolve(day, sun))
        assertEquals(TimeExpr.Clock(0), TimeExpr.parse("00:00"))
    }

    @Test
    fun sunsetPlus30m() {
        val e = TimeExpr.parse("sunset+30m")
        assertEquals(TimeExpr.SunRelative(SunEvent.SUNSET, 30), e)
        assertEquals(sun.sunset!! + 30.minutes, e.resolve(day, sun))
    }

    @Test
    fun dawnMinus15m() {
        val e = TimeExpr.parse("dawn-15m")
        assertEquals(TimeExpr.SunRelative(SunEvent.DAWN, -15), e)
        assertEquals(sun.dawn!! - 15.minutes, e.resolve(day, sun))
        assertEquals(TimeExpr.SunRelative(SunEvent.NOON, 0), TimeExpr.parse("noon"))
        assertEquals(TimeExpr.SunRelative(SunEvent.DUSK, 120), TimeExpr.parse("dusk+2h"))
    }

    @Test
    fun plus6d() {
        val e = TimeExpr.parse("+6d")
        assertEquals(TimeExpr.AfterFrom(6 * 24 * 60), e)
        val from = IslandTime.instantOf(day, LocalTime(5, 0))
        assertEquals(from + 6.days, e.resolve(day, sun, from = from))
        assertNull(e.resolve(day, sun), "relative `to` needs a resolved `from`")
        assertEquals(false, e.validAsFrom)
    }

    @Test
    fun endOfDay2400() {
        val e = TimeExpr.parse("24:00")
        assertEquals(TimeExpr.Clock(1440), e)
        assertEquals(IslandTime.instantOf(LocalDate(2027, 6, 16), LocalTime(0, 0)), e.resolve(day, sun))
    }

    @Test
    fun invalidRejected() {
        for (bad in listOf(
            "", "6:00", "24:01", "25:00", "12:60", "12:5", "sunset+", "sunset+30", "sunset30m", "sundown",
            "+", "+6", "-6d", "+6w", "dawn+-5m", "noon +5m", "06:00 ", "+1.5h",
        )) {
            assertNull(TimeExpr.parseOrNull(bad), "'$bad' must be rejected")
            assertFailsWith<IllegalArgumentException>("'$bad'") { TimeExpr.parse(bad) }
        }
    }

    @Test
    fun durations() {
        assertEquals(0, Dur.minutesOrNull("0m"))
        assertEquals(120, Dur.minutesOrNull("2h"))
        assertEquals(1440, Dur.minutesOrNull("1d"))
        assertNull(Dur.minutesOrNull("-5m"))
        assertNull(Dur.minutesOrNull("5"))
    }
}
