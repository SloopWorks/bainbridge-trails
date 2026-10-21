package com.bainbridgetrails.play.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class IslandTimeTest {
    @Test
    fun `2027-03-14 0230 resolves to 0300`() {
        // Spring forward: 02:00–03:00 PST does not exist; a time in the gap moves to the next valid instant, 03:00 PDT.
        val t = IslandTime.instantOf(LocalDate(2027, 3, 14), LocalTime(2, 30))
        assertEquals(Instant.parse("2027-03-14T10:00:00Z"), t)
        assertEquals(Instant.parse("2027-03-14T10:00:00Z"), IslandTime.instantOf(LocalDate(2027, 3, 14), LocalTime(3, 0)))
        assertEquals(Instant.parse("2027-03-14T09:59:00Z"), IslandTime.instantOf(LocalDate(2027, 3, 14), LocalTime(1, 59)))
    }

    @Test
    fun `2027-11-07 0130 first instance`() {
        // Fall back: 01:00–02:00 happens twice; the first (PDT, UTC−7) wins.
        assertEquals(Instant.parse("2027-11-07T08:30:00Z"), IslandTime.instantOf(LocalDate(2027, 11, 7), LocalTime(1, 30)))
        assertEquals(Instant.parse("2027-11-07T10:00:00Z"), IslandTime.instantOf(LocalDate(2027, 11, 7), LocalTime(2, 0)))
    }

    @Test
    fun `week id is the Monday`() {
        assertEquals(LocalDate(2026, 12, 28), IslandTime.weekId(LocalDate(2027, 1, 3)))  // Sunday → previous Monday
        assertEquals(LocalDate(2027, 1, 4), IslandTime.weekId(LocalDate(2027, 1, 4)))    // Monday → itself
        assertEquals(LocalDate(2027, 1, 4), IslandTime.weekId(LocalDate(2027, 1, 10)))
        // Half-open week in island time: Sunday 23:59 PST is still the old week; Monday 00:00 starts the new one.
        assertEquals(LocalDate(2026, 12, 28), IslandTime.weekId(Instant.parse("2027-01-04T07:59:00Z")))
        assertEquals(LocalDate(2027, 1, 4), IslandTime.weekId(Instant.parse("2027-01-04T08:00:00Z")))
        assertEquals(LocalDate(2027, 1, 3), IslandTime.dateOf(Instant.parse("2027-01-04T07:59:00Z")))
    }

    @Test
    fun `season of a week is its Thursday's quarter`() {
        assertEquals(Season.STORM, IslandTime.season(LocalDate(2027, 3, 31)))
        assertEquals(Season.STRAWBERRY, IslandTime.season(LocalDate(2027, 4, 1)))
        assertEquals(Season.MADRONA, IslandTime.season(LocalDate(2027, 9, 30)))
        assertEquals(Season.KRAKEN, IslandTime.season(LocalDate(2027, 10, 1)))
        assertEquals(Season.STRAWBERRY, IslandTime.seasonOfWeek(LocalDate(2027, 3, 29)))  // Thu = Apr 1
        assertEquals(Season.STORM, IslandTime.seasonOfWeek(LocalDate(2027, 3, 22)))       // Thu = Mar 25
        assertEquals(Season.KRAKEN, IslandTime.seasonOfWeek(LocalDate(2026, 12, 28)))     // Thu = Dec 31
        assertEquals(Season.MADRONA, IslandTime.seasonOfWeek(LocalDate(2027, 9, 27)))     // Thu = Sep 30
        assertEquals("kraken", Season.KRAKEN.id)
    }
}
