package com.bainbridgetrails.play.time

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The bundled data/play/ferry-timetable.json parses and covers its declared range. */
class FerryTimetableBundledTest {
    private val ferry = FerryTimetable.parse(java.io.File("../../data/play/ferry-timetable.json").readText())

    @Test
    fun bundledTimetableParsesAndCoversItsSeasons() {
        assertEquals(35, ferry.crossingMin)
        for (season in ferry.seasons) {
            for (d in listOf(season.from, season.to)) {
                for (term in FerryTimetable.Terminal.entries) {
                    val s = assertNotNull(ferry.departures(d, term), "$d $term")
                    assertTrue(s.isNotEmpty())
                }
            }
        }
        assertEquals(LocalDate(2026, 12, 26), ferry.validUntil)  // WSF fall 2026 season end
    }

    @Test
    fun bundledTimetableIsTheWsfFall2026Schedule() {
        assertTrue(!ferry.isPlaceholder)
        val wed = LocalDate(2026, 10, 21)
        val sea = assertNotNull(ferry.departures(wed, FerryTimetable.Terminal.SEATTLE)).map { it.local }
        val bi = assertNotNull(ferry.departures(wed, FerryTimetable.Terminal.BAINBRIDGE)).map { it.local }
        assertEquals(23, sea.size)
        assertEquals(23, bi.size)
        assertEquals(listOf("05:30", "06:10"), sea.take(2))
        assertEquals("25:35", sea.last())  // 1:35 AM, same service day
        assertEquals(listOf("04:45", "05:20"), bi.take(2))
        assertEquals("24:55", bi.last())
        val sun = LocalDate(2026, 10, 25)
        assertEquals(FerryTimetable.Column.WEEKEND, ferry.column(sun))
        assertEquals(22, ferry.departures(sun, FerryTimetable.Terminal.SEATTLE)!!.size)
        // 07:55 from Bainbridge runs Saturdays and holidays only: holiday column, not weekend.
        assertTrue("07:55" !in ferry.departures(sun, FerryTimetable.Terminal.BAINBRIDGE)!!.map { it.local })
        val thanksgiving = LocalDate(2026, 11, 26)
        assertEquals(FerryTimetable.Column.HOLIDAY, ferry.column(thanksgiving))
        assertTrue("07:55" in ferry.departures(thanksgiving, FerryTimetable.Terminal.BAINBRIDGE)!!.map { it.local })
        assertEquals(FerryTimetable.Column.HOLIDAY, ferry.column(LocalDate(2026, 12, 25)))
        assertEquals(null, ferry.departures(LocalDate(2026, 12, 27), FerryTimetable.Terminal.SEATTLE))  // winter not yet published
    }
}
