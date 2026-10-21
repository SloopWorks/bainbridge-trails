package com.bainbridgetrails.play.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Sunrise / sunset / solar noon references: NOAA GML Solar Calculator (https://gml.noaa.gov/grad/solcalc/, main.js
 * fetched 2026-10-20, evaluated for 47.63 N, −122.52, the calculator's displayed local HH:MM). NOAA's calculator does
 * not give civil twilight; dawn/dusk references are USNO (https://aa.usno.navy.mil/api/rstt/oneday, 2026-10-20).
 */
class SunTest {
    private val zone = TimeZone.of("America/Los_Angeles")

    private fun localMinutes(t: Instant): Int = t.toLocalDateTime(zone).time.let { it.hour * 60 + it.minute }
    private fun minutes(hhmm: String): Int = LocalTime.parse(hhmm).let { it.hour * 60 + it.minute }

    private fun assertNear(want: String, got: Instant?, label: String, tolMin: Int = 2) {
        assertNotNull(got, label)
        val d = abs(localMinutes(got) - minutes(want))
        assertTrue(d <= tolMin, "$label: got ${got.toLocalDateTime(zone).time} want $want (Δ $d min)")
    }

    // date, sunrise, solar noon, sunset (local clock time, NOAA)
    private val noaa = listOf(
        Triple(LocalDate(2027, 1, 15), "07:54", "16:46") to "12:19",
        Triple(LocalDate(2027, 2, 15), "07:16", "17:33") to "12:24",
        Triple(LocalDate(2027, 3, 15), "07:23", "19:16") to "13:19",
        Triple(LocalDate(2027, 4, 15), "06:21", "20:00") to "13:10",
        Triple(LocalDate(2027, 5, 15), "05:33", "20:41") to "13:06",
        Triple(LocalDate(2027, 6, 15), "05:12", "21:10") to "13:10",
        Triple(LocalDate(2027, 7, 15), "05:28", "21:04") to "13:16",
        Triple(LocalDate(2027, 8, 15), "06:06", "20:22") to "13:14",
        Triple(LocalDate(2027, 9, 15), "06:47", "19:22") to "13:05",
        Triple(LocalDate(2027, 10, 15), "07:29", "18:22") to "12:55",
        Triple(LocalDate(2027, 11, 15), "07:15", "16:34") to "11:54",
        Triple(LocalDate(2027, 12, 15), "07:52", "16:19") to "12:05",
    )

    @Test
    fun `12 NOAA reference dates within 2 min`() {
        for ((d, noon) in noaa) {
            val (date, rise, set) = d
            val s = Sun.sunEvents(date)
            assertNear(rise, s.sunrise, "$date sunrise")
            assertNear(set, s.sunset, "$date sunset")
            assertNear(noon, s.noon, "$date noon")
        }
    }

    @Test
    fun `civil dawn and dusk present`() {
        var day = LocalDate(2027, 1, 1)
        while (day.year == 2027) {
            val s = Sun.sunEvents(day)
            val (dawn, rise, set, dusk) = listOf(s.dawn, s.sunrise, s.sunset, s.dusk).map { assertNotNull(it, "$day") }
            assertTrue(dawn < rise && rise < s.noon && s.noon < set && set < dusk, "$day order")
            assertTrue((rise - dawn).inWholeMinutes in 25..50, "$day dawn→sunrise ${(rise - dawn)}")
            assertEquals(day, IslandTime.dateOf(rise))
            assertEquals(day, IslandTime.dateOf(dusk))
            day = LocalDate.fromEpochDays(day.toEpochDays() + 1)
        }
        // Solstices: NOAA sunrise/sunset, USNO civil twilight.
        Sun.sunEvents(LocalDate(2027, 6, 21)).let {
            assertNear("04:31", it.dawn, "Jun 21 dawn"); assertNear("05:12", it.sunrise, "Jun 21 sunrise")
            assertNear("21:12", it.sunset, "Jun 21 sunset"); assertNear("21:52", it.dusk, "Jun 21 dusk")
        }
        Sun.sunEvents(LocalDate(2027, 12, 21)).let {
            assertNear("07:19", it.dawn, "Dec 21 dawn"); assertNear("07:56", it.sunrise, "Dec 21 sunrise")
            assertNear("16:21", it.sunset, "Dec 21 sunset"); assertNear("16:57", it.dusk, "Dec 21 dusk")
        }
        Sun.sunEvents(LocalDate(2027, 1, 15)).let {
            assertNear("07:19", it.dawn, "Jan 15 dawn"); assertNear("17:21", it.dusk, "Jan 15 dusk")
        }
    }

    @Test
    fun `DST days`() {
        // 2027-03-14 (spring forward at 02:00): events are in PDT.
        Sun.sunEvents(LocalDate(2027, 3, 14)).let {
            assertNear("07:25", it.sunrise, "Mar 14 sunrise"); assertNear("19:14", it.sunset, "Mar 14 sunset")
            assertNear("06:55", it.dawn, "Mar 14 dawn"); assertNear("19:45", it.dusk, "Mar 14 dusk")
            assertTrue(abs((it.sunrise!! - Instant.parse("2027-03-14T14:25:00Z")).inWholeMinutes) <= 2, "Mar 14 sunrise UTC")
        }
        // 2027-11-07 (fall back at 02:00): events are in PST.
        Sun.sunEvents(LocalDate(2027, 11, 7)).let {
            assertNear("07:03", it.sunrise, "Nov 7 sunrise"); assertNear("16:44", it.sunset, "Nov 7 sunset")
        }
    }
}
