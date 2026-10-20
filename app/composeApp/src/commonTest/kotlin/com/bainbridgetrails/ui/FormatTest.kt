package com.bainbridgetrails.ui

import com.bainbridgetrails.data.Units
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun percentRules() {
        assertEquals("0%", Format.percent(0.0))
        assertEquals("<0.1%", Format.percent(0.0009))
        assertEquals("0.1%", Format.percent(0.001))
        assertEquals("12.3%", Format.percent(0.123))
        assertEquals("12.3%", Format.percent(0.1239), "rounds down (TL-5)")
        assertEquals("84.9%", Format.percent(0.84999), "never shows 85% before Complete")
        assertEquals("99.9%", Format.percent(0.99999), "never shows 100% before it is reached")
        assertEquals("100%", Format.percent(1.0))
    }

    @Test
    fun distanceUnits() {
        assertEquals("1.0 mi", Format.distance(1609.344, Units.MILES))
        assertEquals("0.0 mi", Format.distance(0.0, Units.MILES))
        assertEquals("3.9 km", Format.distance(3_940.0, Units.KILOMETRES))
        assertEquals("2.5 km", Format.lengthMi(1.5534, Units.KILOMETRES))
    }

    @Test
    fun durations() {
        assertEquals("0:05", Format.duration(5_000))
        assertEquals("12:04", Format.duration(724_000))
        assertEquals("1:05:09", Format.duration(3_909_000))
    }

    @Test
    fun wholePercentRoundsDown() {
        assertEquals("0%", Format.percentWhole(0.0))
        assertEquals("<1%", Format.percentWhole(0.004))
        assertEquals("<1%", Format.percentWhole(0.0099), "never rounds a sub-1% value up to 1%")
        assertEquals("1%", Format.percentWhole(0.01))
        assertEquals("42%", Format.percentWhole(0.4299))
        assertEquals("84%", Format.percentWhole(0.8499), "never shows 85% before Complete (TL-5)")
        assertEquals("99%", Format.percentWhole(0.9999))
        assertEquals("100%", Format.percentWhole(1.0))
    }

    @Test
    fun spokenDurations() {
        assertEquals("0 minutes", Format.durationSpoken(59_000))
        assertEquals("1 minute", Format.durationSpoken(60_000))
        assertEquals("12 minutes", Format.durationSpoken(12 * 60_000L + 30_000))
        assertEquals("1 hour", Format.durationSpoken(3_600_000))
        assertEquals("1 hour 8 minutes", Format.durationSpoken(4_102_000))
        assertEquals("2 hours 1 minute", Format.durationSpoken(7_260_000))
    }
}
