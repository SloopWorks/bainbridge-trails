package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.config.TestPack.on
import com.bainbridgetrails.play.config.TestPack.plus
import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.play.time.TideTable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** schedule.md §4.4 semantics (date level, windows, anchors) and the server subset (backend/src/play/recurrence.ts). */
class RecurrenceTest {
    private val c = TestPack.base

    private fun starts(rec: Recurrence, from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = from
        while (d <= to) {
            if (Recurrences.startsOn(c, rec, d)) out += d
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return out
    }

    private fun d(s: String) = LocalDate.parse(s)
    private fun iso(w: OccurrenceWindow) = Recurrences.islandIso(w.start) + " " + Recurrences.islandIso(w.end)

    @Test
    fun `thu weekly`() {
        val kraken = TestPack.weekly("thu", "06:00", "20:00", 4)
        assertEquals(listOf(d("2027-03-04"), d("2027-03-11"), d("2027-03-18")), starts(kraken, d("2027-03-01"), d("2027-03-20")))
        // across the spring-forward night: Thu 06:00 PST → Sun 20:00 PDT
        val w = Recurrences.windowsOn(c, kraken, d("2027-03-11")).single()
        assertEquals("2027-03-11T06:00-08:00 2027-03-14T20:00-07:00", iso(w))
        assertNull(w.sub)
        assertTrue(Recurrences.covers(c, kraken, d("2027-03-14")))
        assertFalse(Recurrences.covers(c, kraken, d("2027-03-15")))
        // no `on` = Monday
        assertEquals(listOf(d("2027-01-04")), starts(Recurrence(every = Every.week, from = "05:00", to = "24:00"), d("2027-01-01"), d("2027-01-07")))
    }

    @Test
    fun `last-sun Feb 2027 is 28`() {
        val chilly = Recurrence(every = Every.year, inMonths = listOf(MonthId.feb), on = on("last-sun"), from = "06:00", to = "17:00")
        assertEquals(listOf(d("2027-02-28")), starts(chilly, d("2027-01-01"), d("2027-12-31")))
        assertEquals(listOf(d("2028-02-27")), starts(chilly, d("2028-01-01"), d("2028-12-31")))
    }

    @Test
    fun `1sat and 3sat with days 2`() {
        val r = Recurrence(every = Every.month, on = on("1sat", "3sat"), from = "09:00", to = "17:00", days = 2)
        assertEquals(listOf(d("2027-01-02"), d("2027-01-16")), starts(r, d("2027-01-01"), d("2027-01-31")))
        assertEquals("2027-01-02T09:00-08:00 2027-01-03T17:00-08:00", iso(Recurrences.windowsOn(c, r, d("2027-01-02")).single()))
        assertTrue(Recurrences.covers(c, r, d("2027-01-03")))
        assertFalse(Recurrences.covers(c, r, d("2027-01-04")))
    }

    @Test
    fun `5sat only when present`() {
        val r = Recurrence(every = Every.month, on = on("5sat"), from = "09:00", to = "17:00")
        assertEquals(listOf(d("2027-01-30"), d("2027-05-29"), d("2027-07-31"), d("2027-10-30")), starts(r, d("2027-01-01"), d("2027-12-31")))
    }

    @Test
    fun `minus 1 is the last day and 31 skips short months`() {
        val last = Recurrence(every = Every.month, on = listOf(JsonPrimitive(-1)), from = "09:00", to = "17:00")
        assertEquals(listOf(d("2027-02-28"), d("2027-03-31"), d("2027-04-30")), starts(last, d("2027-02-01"), d("2027-04-30")))
        val d31 = Recurrence(every = Every.month, on = listOf(JsonPrimitive(31)), from = "09:00", to = "17:00")
        assertEquals(listOf(d("2027-03-31")), starts(d31, d("2027-02-01"), d("2027-04-30")))
    }

    @Test
    fun `dates and fallback`() {
        val r = Recurrence(
            every = Every.dates, dates = listOf(d("2027-06-06")), from = "09:00", to = "17:00",
            fallback = Recurrence(every = Every.year, inMonths = listOf(MonthId.jun), on = listOf(JsonPrimitive(7)), from = "09:00", to = "17:00"),
        )
        assertEquals(listOf(d("2027-06-06")), starts(r, d("2027-01-01"), d("2027-12-31")))
        assertEquals(listOf(d("2028-06-07")), starts(r, d("2028-01-01"), d("2028-12-31")))
    }

    @Test
    fun except() {
        val r = Recurrence(every = Every.week, on = on("tue"), from = "09:00", to = "17:00", except = listOf("exclusion-remembrance"))
        assertEquals(listOf(d("2027-03-23"), d("2027-04-06")), starts(r, d("2027-03-20"), d("2027-04-10")))
        assertTrue(Recurrences.isQuietDay(c, d("2027-03-30")))
        assertFalse(Recurrences.isQuietDay(c, d("2027-03-31")))
    }

    @Test
    fun `season filter`() {
        val kraken = Recurrence(every = Every.day, season = listOf("kraken"), from = "09:00", to = "17:00")
        assertTrue(Recurrences.startsOn(c, kraken, d("2027-10-01")))
        assertFalse(Recurrences.startsOn(c, kraken, d("2027-09-30")))
        val wet = Recurrence(every = Every.day, season = listOf("wet"), from = "09:00", to = "17:00")
        assertTrue(Recurrences.startsOn(c, wet, d("2027-01-10")))
        assertTrue(Recurrences.startsOn(c, wet, d("2027-10-15")))
        assertFalse(Recurrences.startsOn(c, wet, d("2027-06-01")))
        val shoulder = Recurrence(every = Every.day, season = listOf("shoulder"), from = "09:00", to = "17:00")
        assertTrue(Recurrences.startsOn(c, shoulder, d("2027-05-15")))
        assertFalse(Recurrences.startsOn(c, shoulder, d("2027-08-01")))
    }

    // Three 2027-01-05 extrema (island time): H 09:00 3.0 m, L 15:00 1.0 m, H 19:00 3.5 m. Nothing on other days.
    private val tides = TideTable.parse(
        """{"station":"9445882","datum":"MLLW","units":"cm","year":2027,"t0":"2027-01-01T00:00Z","e":[[6780,300,"H"],[360,100,"L"],[240,350,"H"]]}""",
    )

    @Test
    fun `anchor highest high and fallback`() {
        val r = Recurrence(
            every = Every.day, from = "06:00", to = "20:00",
            anchor = Anchor(AnchorAt.tide_high, before = "60m", after = "60m", pick = AnchorPick.highest, fallback = ClockSpan("10:00", "12:00")),
        )
        val w = Recurrences.windowsOn(c, r, d("2027-01-05"), tides = tides).single()
        assertEquals("2027-01-05T18:00-08:00 2027-01-05T20:00-08:00", iso(w), "19:00 high, clipped to the day window")
        assertEquals(0, w.sub)
        val all = Recurrences.windowsOn(c, r.copy(anchor = r.anchor!!.copy(pick = AnchorPick.all)), d("2027-01-05"), tides = tides)
        assertEquals(listOf(0, 1), all.map { it.sub })
        val fb = Recurrences.windowsOn(c, r, d("2027-01-10"), tides = tides).single()
        assertEquals("2027-01-10T10:00-08:00 2027-01-10T12:00-08:00", iso(fb))
        assertNull(fb.sub)
        // min height filter rejects both highs → fallback; no table → fallback
        val strict = r.copy(anchor = r.anchor.copy(minHeightM = 4.0))
        assertNull(Recurrences.windowsOn(c, strict, d("2027-01-05"), tides = tides).single().sub)
        assertNull(Recurrences.windowsOn(c, r, d("2027-01-05"), tides = null).single().sub)
    }

    private val ferry = FerryTimetable.parse(
        """{"route":"seattle-bainbridge","crossingMin":35,"validUntil":"2027-12-31","timetables":[{"id":"t","from":"2027-01-01","to":"2027-12-31",
           "weekday":{"fromSeattle":["06:00"],"fromBainbridge":["04:45","06:10","07:45","09:25","11:05"]},
           "weekend":{"fromSeattle":["08:00"],"fromBainbridge":["09:00"]}}]}""",
    )

    @Test
    fun `ferry max 3`() {
        val r = Recurrence(
            every = Every.day, from = "05:00", to = "23:00",
            anchor = Anchor(AnchorAt.ferry_depart_bainbridge, before = "0m", after = "35m", pick = AnchorPick.all, max = 3),
        )
        val ws = Recurrences.windowsOn(c, r, d("2027-01-05"), ferry = ferry)
        assertEquals(
            listOf("2027-01-05T06:10-08:00 2027-01-05T06:45-08:00", "2027-01-05T07:45-08:00 2027-01-05T08:20-08:00", "2027-01-05T09:25-08:00 2027-01-05T10:00-08:00"),
            ws.map(::iso),
        )
        assertEquals(listOf(0, 1, 2), ws.map { it.sub })
        // no anchor fallback and no ferry table: no occurrence
        assertTrue(Recurrences.windowsOn(c, r, d("2027-01-05"), ferry = null).isEmpty())
    }

    @Test
    fun `sun relative and plus durations`() {
        val r = Recurrence(every = Every.day, from = "sunrise+30m", to = "sunset-30m")
        val w = Recurrences.windowsOn(c, r, d("2027-06-21")).single()
        assertTrue(w.start < w.end)
        val plus = Recurrence(every = Every.week, on = on("mon"), from = "05:00", to = "+6d")
        assertEquals("2027-03-08T05:00-08:00 2027-03-14T05:00-07:00", iso(Recurrences.windowsOn(c, plus, d("2027-03-08")).single()), "+Dur is wall-clock, as on the Worker")
        val gap = Recurrence(every = Every.week, on = on("sun"), from = "02:30", to = "04:00")
        assertEquals("2027-03-14T03:00-07:00 2027-03-14T04:00-07:00", iso(Recurrences.windowsOn(c, gap, d("2027-03-14")).single()), "a gap time moves forward")
        val overlap = Recurrence(every = Every.week, on = on("sun"), from = "01:30", to = "03:00")
        assertEquals("2027-11-07T01:30-07:00 2027-11-07T03:00-08:00", iso(Recurrences.windowsOn(c, overlap, d("2027-11-07")).single()), "an overlap takes the first instance")
    }

    @Test
    fun `server subset`() {
        val sweep = c.items.first { it.id == "ch-sweep" }
        assertTrue(Recurrences.serverEvaluable(c, c.effectiveWhen(sweep)!!))
        assertFalse(Recurrences.serverEvaluable(c, c.effectiveWhen(c.items.first { it.id == "ferry-sprite" })!!), "anchored")
        assertFalse(Recurrences.serverEvaluable(c, Recurrence(every = Every.day, from = "sunrise", to = "17:00")), "sun-relative")
        val o = assertNotNull(Recurrences.occurrenceOn(c, sweep, d("2027-03-13")))
        assertEquals("ch-sweep@2027-03-13", o.key)
        assertEquals("2027-03-13T00:00-08:00", Recurrences.islandIso(o.start))
        assertEquals("2027-03-15T00:00-07:00", Recurrences.islandIso(o.end!!), "24:00 on the last day = next day 00:00")
        assertNull(Recurrences.occurrenceOn(c, sweep, d("2027-03-14")))
        // always: one occurrence at notBefore, else the pack epoch; open-ended without expires
        val egg = c.items.first { it.id == "egg-frog" }
        val withEpoch = c.copy(epoch = d("2026-10-29")).plus(egg.copy(id = "egg-dated", notBefore = d("2027-02-01"), expires = d("2027-02-10")))
        assertEquals("egg-frog@2026-10-29", Recurrences.occurrenceOn(withEpoch, egg, d("2026-10-29"))?.key)
        assertNull(Recurrences.occurrenceOn(withEpoch, egg, d("2026-10-30")))
        assertNull(Recurrences.occurrenceOn(withEpoch, egg, d("2026-10-29"))!!.end)
        val dated = withEpoch.items.last()
        assertEquals("2027-02-11T00:00-08:00", Recurrences.islandIso(Recurrences.occurrenceOn(withEpoch, dated, d("2027-02-01"))!!.end!!))
        // notBefore / expires bound the start date
        val bounded = sweep.copy(notBefore = d("2027-03-14"), expires = d("2027-03-20"))
        assertNull(Recurrences.occurrenceOn(c, bounded, d("2027-03-13")))
        assertNotNull(Recurrences.occurrenceOn(c, bounded, d("2027-03-20")))
        assertNull(Recurrences.occurrenceOn(c, bounded, d("2027-03-27")))
    }
}
