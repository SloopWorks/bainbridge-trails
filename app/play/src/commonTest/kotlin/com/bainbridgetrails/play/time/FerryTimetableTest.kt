package com.bainbridgetrails.play.time

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FerryTimetableTest {
    private val json = """
        {
          "schema": 1, "route": "seattle-bainbridge", "crossingMin": 35, "source": "test", "validUntil": "2027-03-20",
          "timetables": [
            { "id": "fall", "from": "2026-09-20", "to": "2026-12-26",
              "weekday": { "fromSeattle": ["05:30", "12:05", "24:55"], "fromBainbridge": ["04:45", "06:10"] },
              "weekend": { "fromSeattle": ["06:10", "11:15"], "fromBainbridge": ["05:20"] },
              "holiday": { "fromSeattle": ["09:00"], "fromBainbridge": ["08:00"] },
              "holidays": ["2026-11-26"] },
            { "id": "winter", "from": "2026-12-27", "to": "2027-03-20",
              "weekday": { "fromSeattle": ["06:00"], "fromBainbridge": ["07:00"] },
              "weekend": { "fromSeattle": ["08:00"], "fromBainbridge": ["09:00"] },
              "holidays": ["2027-01-01"] }
          ]
        }
    """.trimIndent()

    private val ferry = FerryTimetable.parse(json)

    @Test
    fun `weekday weekend holiday column`() {
        assertEquals(FerryTimetable.Column.WEEKDAY, ferry.column(LocalDate(2026, 11, 25)))  // Wednesday
        assertEquals(FerryTimetable.Column.WEEKEND, ferry.column(LocalDate(2026, 11, 28)))  // Saturday
        assertEquals(FerryTimetable.Column.WEEKEND, ferry.column(LocalDate(2026, 11, 29)))  // Sunday
        assertEquals(FerryTimetable.Column.HOLIDAY, ferry.column(LocalDate(2026, 11, 26)))  // Thanksgiving (Thu)
        assertEquals(listOf("09:00"), ferry.departures(LocalDate(2026, 11, 26), FerryTimetable.Terminal.SEATTLE)!!.map { it.local })
        // A season without a holiday column runs the weekend column on its holidays.
        assertEquals(FerryTimetable.Column.HOLIDAY, ferry.column(LocalDate(2027, 1, 1)))
        assertEquals(listOf("08:00"), ferry.departures(LocalDate(2027, 1, 1), FerryTimetable.Terminal.SEATTLE)!!.map { it.local })
    }

    @Test
    fun `season boundaries`() {
        assertNull(ferry.departures(LocalDate(2026, 9, 19), FerryTimetable.Terminal.SEATTLE), "before the first season")
        assertEquals(listOf("05:30", "12:05", "24:55"), ferry.departures(LocalDate(2026, 12, 24), FerryTimetable.Terminal.SEATTLE)!!.map { it.local })
        assertEquals("fall", ferry.seasonOf(LocalDate(2026, 9, 21))!!.id)   // from is inclusive (Monday)
        assertEquals("fall", ferry.seasonOf(LocalDate(2026, 12, 26))!!.id)  // to is inclusive
        assertEquals("winter", ferry.seasonOf(LocalDate(2026, 12, 27))!!.id)
        assertEquals("winter", ferry.seasonOf(LocalDate(2027, 3, 20))!!.id)
    }

    @Test
    fun `arrival is departure plus crossingMin`() {
        val day = LocalDate(2026, 11, 25)
        val sailings = assertNotNull(ferry.departures(day, FerryTimetable.Terminal.SEATTLE))
        for (s in sailings) assertEquals(s.depart + 35.minutes, s.arrive)
        // 05:30 PST = 13:30Z; "24:55" is 00:55 the next morning, still this service day.
        assertEquals(Instant.parse("2026-11-25T13:30:00Z"), sailings[0].depart)
        assertEquals(Instant.parse("2026-11-26T08:55:00Z"), sailings[2].depart)
        assertTrue(sailings.zipWithNext { a, b -> a.depart < b.depart }.all { it })
    }

    @Test
    fun `pack weekendDates run the holiday column`() {
        val ferry = FerryTimetable.of(
            com.bainbridgetrails.play.config.Ferry(
                route = "seattle-bainbridge", crossingMin = 35,
                timetables = listOf(
                    kotlinx.serialization.json.Json.parseToJsonElement(
                        """{ "id": "fall", "from": "2026-09-20", "to": "2026-12-26",
                             "weekday": { "fromSeattle": ["05:30"], "fromBainbridge": ["04:45"] },
                             "weekend": { "fromSeattle": ["06:10"], "fromBainbridge": ["05:20"] },
                             "holiday": { "fromSeattle": ["06:10"], "fromBainbridge": ["05:20", "07:55"] },
                             "weekendDates": ["2026-11-26"] }""",
                    ) as kotlinx.serialization.json.JsonObject,
                ),
            ),
        )
        assertNotNull(ferry)
        assertEquals(LocalDate(2026, 12, 26), ferry.validUntil)
        assertEquals(FerryTimetable.Column.HOLIDAY, ferry.column(LocalDate(2026, 11, 26)))  // Thanksgiving (Thu)
        assertEquals(listOf("05:20", "07:55"), ferry.departures(LocalDate(2026, 11, 26), FerryTimetable.Terminal.BAINBRIDGE)!!.map { it.local })
        assertEquals(listOf("05:20"), ferry.departures(LocalDate(2026, 11, 29), FerryTimetable.Terminal.BAINBRIDGE)!!.map { it.local })
    }

    @Test
    fun `expired timetable returns null`() {
        assertNull(ferry.column(LocalDate(2027, 3, 21)))
        assertNull(ferry.departures(LocalDate(2027, 6, 1), FerryTimetable.Terminal.BAINBRIDGE))
        val expiredEarly = FerryTimetable.parse(json.replace("\"validUntil\": \"2027-03-20\"", "\"validUntil\": \"2026-12-31\""))
        assertNull(expiredEarly.departures(LocalDate(2027, 1, 4), FerryTimetable.Terminal.SEATTLE), "past validUntil")
        assertNotNull(expiredEarly.departures(LocalDate(2026, 12, 31), FerryTimetable.Terminal.SEATTLE))
    }
}
