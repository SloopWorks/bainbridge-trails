package com.bainbridgetrails.coverage

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealDatasetTest {
    @Test
    fun realDatasetParses() {
        val segs = File("../../data/dist/coverage_segments.json")
        val trails = File("../../data/dist/trails.json")
        if (!segs.exists() || !trails.exists()) {
            println("realDatasetParses: SKIPPED, ../../data/dist files missing")
            return
        }
        val ds = Dataset.parse(trails.readText(), segs.readText())
        val sum = ds.segments.filter { it.included }.sumOf { it.lengthM }
        println("realDatasetParses: trails=${ds.trails.size} segments=${ds.segments.size} includedSum=$sum islandTotalM=${ds.islandTotalM}")
        val expected = Regex("\"segment_ids\"").findAll(trails.readText()).count()
        assertEquals(expected, ds.trails.size)
        assertTrue(abs(sum - ds.islandTotalM) <= 1.0, "sum $sum vs ${ds.islandTotalM}")
        val canonical = File("../../data/canonical/segments.geojson")
        if (canonical.exists()) {
            val redistributable = Regex("\"geometry_redistributable\":\\s*true").findAll(canonical.readText()).count()
            assertEquals(redistributable, ds.segments.size)
        }
        assertTrue(ds.segments.all { it.category != null }, "every segment has a category")
    }
}
