package com.bainbridgetrails.play.geo

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigResult
import com.bainbridgetrails.play.config.RoadSafety
import com.bainbridgetrails.play.schedule.FeatureInfo
import com.bainbridgetrails.play.schedule.JvmDatasetPlaceIndex
import com.bainbridgetrails.play.schedule.Ref
import com.bainbridgetrails.play.schedule.StretchRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PD-7 / product §4.0.2: the one roadSafety rule, over the real roads.json when present. */
class RoadSafetyTest {
    private val rule = RoadSafety()

    private fun road(category: String = "residential", speed: Int? = 25, public: Boolean? = true, ownership: String? = "public_city", counted: Boolean = true) =
        FeatureInfo("road-x", Goal.roads, counted, category, public, ownership, speed)

    @Test
    fun ruleUnits() {
        assertNull(rule.check(road()))
        assertNull(rule.check(road(speed = null)), "null speed passes for residential")
        assertNull(rule.check(road(category = "arterial", speed = 25)))
        assertEquals(RoadSafetyFailure.SPEED, rule.check(road(speed = 30)))
        assertEquals(RoadSafetyFailure.NULL_SPEED, rule.check(road(category = "collector", speed = null)))
        assertEquals(RoadSafetyFailure.CATEGORY, rule.check(road(category = "state_route", speed = 25)))
        assertEquals(RoadSafetyFailure.CATEGORY, rule.check(road(category = "lane")))
        assertEquals(RoadSafetyFailure.NOT_PUBLIC, rule.check(road(public = false)))
        assertEquals(RoadSafetyFailure.NOT_PUBLIC, rule.check(road(public = null)))
        assertEquals(RoadSafetyFailure.OWNERSHIP, rule.check(road(ownership = "unknown")))
        assertEquals(RoadSafetyFailure.NOT_COUNTED, rule.check(road(counted = false)))
        assertTrue(rule.isAtLeastAsStrictAs(RoadSafety()))
        assertTrue(!RoadSafety(maxSpeedMph = 30).isAtLeastAsStrictAs(RoadSafety()))
        assertTrue(!RoadSafety(categories = listOf("residential", "state_route")).isAtLeastAsStrictAs(RoadSafety()))
        assertTrue(RoadSafety(maxSpeedMph = 20).isAtLeastAsStrictAs(RoadSafety()))
    }

    @Test
    fun `370 of 430 pass on 2026-10-20 data`() {
        val index = JvmDatasetPlaceIndex.loadOrSkip("RoadSafetyTest")
        val counted = index.features(Goal.roads).filter { it.counted }
        val failures = counted.mapNotNull { rule.check(it) }
        println("RoadSafetyTest: ${counted.size - failures.size} of ${counted.size} counted roads pass; failures ${failures.groupingBy { it }.eachCount()}")
        assertEquals(430, counted.size)
        assertEquals(370, counted.size - failures.size)
    }

    @Test
    fun `every coast pool road passes`() {
        val index = JvmDatasetPlaceIndex.loadOrSkip("RoadSafetyTest")
        val pack = File("../composeApp/src/commonMain/composeResources/files/play/play-content.json")
        val config = assertIs<PlayConfigResult.Ok>(PlayConfig.decode(pack.readText()), pack.path).config
        val coastRoads = config.pools.filterKeys { it.startsWith("coast-k") }.values
            .flatMap { it.members }.map { assertIs<StretchRef>(Ref.parse(it), it).featureId }
        assertTrue(coastRoads.size >= 10, coastRoads.toString())
        for (id in coastRoads) {
            val f = index.feature(id) ?: error("$id not in roads.json")
            assertNull(rule.check(f), "$id fails roadSafety")
        }
    }
}
