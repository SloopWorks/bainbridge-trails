package com.bainbridgetrails.record

import com.bainbridgetrails.coverage.Fix
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackGeoJsonTest {
    @Test
    fun emptyUntilTwoFixes() {
        val empty = """{"type":"FeatureCollection","features":[]}"""
        assertEquals(empty, trackGeoJson(emptyList()))
        assertEquals(empty, trackGeoJson(listOf(Fix(0, -122.5, 47.6))))
    }

    @Test
    fun lineStringInLonLatOrder() {
        assertEquals(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[-122.5,47.6],[-122.51,47.61]]}}]}""",
            trackGeoJson(listOf(Fix(0, -122.5, 47.6), Fix(1, -122.51, 47.61))),
        )
    }
}
