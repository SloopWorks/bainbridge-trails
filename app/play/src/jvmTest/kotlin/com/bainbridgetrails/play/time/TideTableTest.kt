package com.bainbridgetrails.play.time

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** jvmTest: reads the fixture copy of data/play/tides/9445882-2027.json from resources. */
class TideTableTest {
    private val text = checkNotNull(javaClass.getResource("/tides/9445882-2027.json")).readText()
    private val table = TideTable.parse(text)

    @Test
    fun `events per day 3 to 4`() {
        assertEquals("9445882", table.station)
        var day = LocalDate(2027, 1, 1)
        val last = LocalDate(2027, 12, 30) // Dec 31 island time ends in 2028: not covered by this table alone
        while (day <= last) {
            val ev = assertNotNull(table.events(day), "$day")
            assertTrue(ev.size in 3..4, "$day: ${ev.size} events")
            assertTrue(ev.zipWithNext { a, b -> a.high != b.high && a.at < b.at }.all { it }, "$day alternates")
            day = LocalDate.fromEpochDays(day.toEpochDays() + 1)
        }
        // NOAA: 2027-01-01 08:49Z H 2.532 m (00:49 PST), 13:09Z L 1.981, 19:18Z H 3.581, 2027-01-02 02:48Z L 0.244.
        val jan1 = table.events(LocalDate(2027, 1, 1))!!
        assertEquals(Instant.parse("2027-01-01T08:49:00Z"), jan1[0].at)
        assertEquals(2.53, jan1[0].heightM, 1e-9)
        assertTrue(jan1[0].high)
        assertEquals(Instant.parse("2027-01-02T02:48:00Z"), jan1[3].at)
        assertEquals(0.24, jan1[3].heightM, 1e-9)
    }

    @Test
    fun `cosine interpolation monotonic between extrema`() {
        val days = (0 until 14).flatMap { table.events(LocalDate(2027, 6, 1 + it))!! }
        for ((a, b) in days.zipWithNext()) {
            assertEquals(a.heightM, table.heightAt(a.at)!!, 1e-9)
            var t = a.at
            var prev = a.heightM
            while (t < b.at) {
                t += 1.minutes
                val h = table.heightAt(minOf(t, b.at))!!
                if (b.heightM > a.heightM) assertTrue(h >= prev - 1e-12, "rising $t") else assertTrue(h <= prev + 1e-12, "falling $t")
                prev = h
            }
            assertEquals(b.heightM, prev, 1e-9)
            // Midpoint is the mean of the two extrema (cosine curve).
            val mid = Instant.fromEpochSeconds((a.at.epochSeconds + b.at.epochSeconds) / 2)
            assertEquals((a.heightM + b.heightM) / 2, table.heightAt(mid)!!, 0.01)
        }
    }

    @Test
    fun `out of range returns null`() {
        assertNull(table.events(LocalDate(2026, 12, 31)))
        assertNull(table.events(LocalDate(2027, 12, 31)), "ends in 2028")
        assertNull(table.events(LocalDate(2028, 6, 1)))
        assertNull(table.heightAt(Instant.parse("2027-01-01T00:30:00Z")), "before the first extremum")
        assertNull(table.heightAt(Instant.parse("2028-02-01T00:00:00Z")))
        assertFalse(table.isPlaceholder)
    }

    @Test
    fun bundledYearsMergeAndCoverDecember31() {
        val dir = java.io.File("../../data/play/tides")
        val years = (2026..2029).map { java.io.File(dir, "9445882-$it.json").readText() }
        val all = TideTable.parse(*years.toTypedArray())
        assertNotNull(all.events(LocalDate(2027, 12, 31)))
        assertNotNull(all.events(LocalDate(2026, 1, 1)))
        assertNull(all.events(LocalDate(2029, 12, 31)))
        // Fixture copy is byte-equal to the source (scripts/build-tides.mjs --check guards this too).
        assertEquals(java.io.File(dir, "9445882-2027.json").readText(), text)
    }
}
