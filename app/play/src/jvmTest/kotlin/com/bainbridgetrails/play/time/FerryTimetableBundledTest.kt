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
        assertTrue(ferry.validUntil >= LocalDate(2027, 1, 1))
    }
}
