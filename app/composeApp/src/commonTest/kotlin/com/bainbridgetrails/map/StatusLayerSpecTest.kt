package com.bainbridgetrails.map

import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.DatasetProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StatusLayerSpecTest {
    @Test fun profileSelectsItsSpec() {
        assertSame(StatusLayerSpec.TRAILS, StatusLayerSpec.of(DatasetProfile.TRAILS))
        assertSame(StatusLayerSpec.ROADS, StatusLayerSpec.of(DatasetProfile.ROADS))
    }

    @Test fun trailsKeepTheV1Encoding() {
        val t = StatusLayerSpec.TRAILS
        assertEquals(5.6f, t.widthAt(TrailStatus.COMPLETE, 15))
        assertEquals(listOf(0.01f, 1.9f), t.dash(TrailStatus.NOT_STARTED))
        assertEquals(TrailStatus.entries.toSet(), t.haloStatuses)
        assertEquals(12.0..16.5, t.fitZoomRange)
        assertTrue(t.drawUnnamedHairlines && t.drawNamedNotCounted)
        assertEquals(
            TrailStatus.entries.flatMap { listOf(statusLayerId(it), namedNotCountedLayerId(it)) }.toSet(),
            t.tappableLayerIds,
        )
    }

    @Test fun roadsTapTheStyleBedAndGateNotStarted() {
        val r = StatusLayerSpec.ROADS
        assertTrue(StatusLayerSpec.ROAD_BED_LAYER in r.tappableLayerIds)
        assertTrue(statusLayerId(TrailStatus.COMPLETE) in r.tappableLayerIds)
        assertFalse(namedNotCountedLayerId(TrailStatus.COMPLETE) in r.tappableLayerIds)
        assertEquals(15f, r.notStartedMinZoom)
        assertFalse(TrailStatus.NOT_STARTED in r.haloStatuses)
        assertEquals(13.0..17.0, r.fitZoomRange)
        assertFalse(r.drawUnnamedHairlines)
    }

    @Test fun widthInterpolatesAndClamps() {
        val r = StatusLayerSpec.ROADS
        assertEquals(3.0f, r.widthAt(TrailStatus.COMPLETE, 12)) // between 2.6 @11 and 3.4 @13
        assertEquals(2.6f, r.widthAt(TrailStatus.COMPLETE, 9))
        assertEquals(6.0f, r.widthAt(TrailStatus.COMPLETE, 19))
        assertEquals(2.0f, r.widthAt(TrailStatus.NOT_STARTED, 13)) // first stop is 15
    }

    @Test fun selectionOfANotStartedRoadIsAtLeastNearlyDoneWide() {
        val stops = StatusLayerSpec.ROADS.selectionWidths(TrailStatus.NOT_STARTED)
        assertEquals(listOf(11, 13, 15, 17), stops.map { it.first })
        assertEquals(2.0f, stops.first { it.first == 11 }.second) // nearly done @11 (no not-started line there)
        assertEquals(3.6f, stops.first { it.first == 15 }.second) // max(2.0, 3.6)
        // Trails: the item's own widths.
        assertEquals(StatusLayerSpec.TRAILS.widths.getValue(TrailStatus.PARTWAY), StatusLayerSpec.TRAILS.selectionWidths(TrailStatus.PARTWAY))
    }

    @Test fun roadEndsParseNearestShoreFirstAndPinsGeoJson() {
        val ends = parseRoadEnds(
            """{"road_ends":[
              {"road_id":"road-a","coordinates":[-122.5,47.6],"shore_distance_m":30.0},
              {"road_id":"road-a","coordinates":[-122.51,47.61],"shore_distance_m":9.5,"city_confirmed":true},
              {"road_id":"road-b","coordinates":[1.0]}
            ]}""",
        )
        assertEquals(setOf("road-a"), ends.keys)
        assertEquals(listOf(9.5, 30.0), ends.getValue("road-a").map { it.shoreDistanceM })
        val json = pointsGeoJson(ends.getValue("road-a"))
        assertTrue(json.startsWith("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"road_id":"road-a"},"geometry":{"type":"Point","coordinates":[-122.51,47.61]}}"""))
    }
}
