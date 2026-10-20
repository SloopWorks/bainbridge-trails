package com.bainbridgetrails.coverage

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The roads dataset (data/roads/dist) ships the same contract as the trail dataset with `roads`/`road_ids` in place
 * of `trails`/`trail_ids` (docs/roads/data-spec.md). One model reads either, via @JsonNames aliases.
 */
class RoadsDatasetTest {
    private val roadsJson = """
        {"version":"2026-10-19+abc","island_total_m":264276.5,"island_total_shippable_m":261532.8,
         "roads":[{"id":"road-aaron-avenue-northeast","name":"Aaron Avenue Northeast","category":"residential",
                   "ownership":"public_city","segment_ids":["rseg-000099"],"computed_length_mi":0.303,
                   "climb_total_m":10.0,"surface":null,"speed_mph":20,"counted":true}]}
    """.trimIndent()

    private val coverageJson = """
        {"version":"2026-10-19+abc","segments":[
          {"id":"rseg-000099","length_m":487.5,"included":true,"road_ids":["road-aaron-avenue-northeast"],
           "coords":[[-122.53,47.63],[-122.525,47.63]],"category":"residential","ownership":"public_city"}]}
    """.trimIndent()

    @Test
    fun parsesTheRoadsDatasetWithNoSecondModel() {
        val d = Dataset.parse(roadsJson, coverageJson)
        assertEquals("2026-10-19+abc", d.version)
        assertEquals(261532.8, d.islandTotalM)
        assertEquals(listOf("road-aaron-avenue-northeast"), d.trails.map { it.id })
        assertEquals("Aaron Avenue Northeast", d.trails.single().name)
        assertEquals(listOf("rseg-000099"), d.trails.single().segmentIds)
        assertEquals(0.303, d.trails.single().displayLengthMi)
        // road_ids lands in trailIds, so Progress/SegmentIndex score roads with no change.
        assertEquals(listOf("road-aaron-avenue-northeast"), d.segments.single().trailIds)
        assertEquals(487.5, d.segments.single().lengthM)
        assertEquals("residential", d.segments.single().category)
    }

    @Test
    fun stillReadsTheTrailDatasetSpelling() {
        val d = Dataset.parse(
            """{"version":"v1","island_total_shippable_m":100.0,
                "trails":[{"id":"t1","name":"T","segment_ids":["seg-1"],"computed_length_mi":0.1}]}""",
            """{"version":"v1","segments":[{"id":"seg-1","length_m":100.0,"included":true,"trail_ids":["t1"],
                "coords":[[-122.53,47.63],[-122.528,47.63]]}]}""",
        )
        assertEquals(listOf("t1"), d.trails.map { it.id })
        assertEquals(listOf("t1"), d.segments.single().trailIds)
    }
}
