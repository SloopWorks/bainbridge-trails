package com.bainbridgetrails.play.geo

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Placement
import com.bainbridgetrails.play.config.PlayConfigValidator
import com.bainbridgetrails.play.config.QuietZone
import com.bainbridgetrails.play.config.Rule
import com.bainbridgetrails.play.config.TestPack
import com.bainbridgetrails.play.config.TestPack.replace
import com.bainbridgetrails.play.config.TestPack.withPool
import com.bainbridgetrails.play.schedule.FakePlaceIndex
import com.bainbridgetrails.play.schedule.FakePlaceIndex.Companion.MEMORIAL
import com.bainbridgetrails.play.schedule.RoadRef
import com.bainbridgetrails.play.schedule.TrailRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PD-6 / product §4.6.3: quiet zones are buffers around dataset segments, never typed circles. */
class QuietZoneTest {
    private val index = FakePlaceIndex.island()
    private val zones = QuietZones.build(TestPack.base.quietZones, TestPack.base.places, index).zones

    @Test
    fun buffersFromSegmentGeometry() {
        assertEquals(listOf("exclusion-memorial", "blakely-cemetery"), zones.map { it.id })
        val memorial = zones.first()
        assertEquals(200.0, memorial.bufferM)
        assertEquals(index.segments(TrailRef(MEMORIAL)).map { it.line }, memorial.lines)
        // 150 m east of the trail's north end is inside; the same distance from the trail's far (south) end would be
        // > 200 m from the start vertex alone, so this proves the buffer follows the whole line, not a centre circle.
        val northEnd = GeoPoint(lat = 47.6157, lon = -122.5097)
        val east150 = GeoMath.offset(northEnd, eastM = 150.0, northM = 0.0)
        assertTrue(GeoMath.distanceM(east150, GeoPoint(lat = 47.6146, lon = -122.5103)) > 200.0)
        assertEquals("exclusion-memorial", QuietZones(zones).zoneAt(east150)?.id)
        assertNull(QuietZones(zones).zoneAt(GeoMath.offset(northEnd, eastM = 230.0, northM = 0.0)))
        // the old hand-typed circle (47.6098, −122.5076) is ≈ 0.6 km away and covers nothing
        assertNull(QuietZones(zones).zoneAt(GeoPoint(lat = 47.6098, lon = -122.5076)))
    }

    @Test
    fun touchingMemberRejected() {
        val qz = QuietZones(zones)
        assertEquals("exclusion-memorial", qz.touching(index.segments(RoadRef("road-taylor")))?.id)
        assertEquals("exclusion-memorial", qz.touching(index.segments(TrailRef("trail-pritchard-trail")))?.id)
        assertEquals("blakely-cemetery", qz.touching(index.segments(TrailRef("trail-blakely-harbor-trail")))?.id)
        assertNull(qz.touching(index.segments(RoadRef("road-safe-a"))))

        val errs = PlayConfigValidator.validate(TestPack.base.withPool("west", "trail:trail-pritchard-trail", goal = Goal.trails), index)
        assertTrue(errs.any { it.rule == Rule.QUIET_ZONE_TOUCH && "trail-pritchard-trail" in it.message }, errs.toString())
        val roads = PlayConfigValidator.validate(TestPack.base.withPool("coast-k1", "road:road-taylor", goal = Goal.roads), index)
        assertEquals(setOf(Rule.QUIET_ZONE_TOUCH), roads.map { it.rule }.toSet(), roads.toString())
    }

    @Test
    fun remembranceCardAllowedInItsOwnZone() {
        val card = TestPack.base.places.getValue("memorial-card")
        assertNotNull(QuietZones(zones).zoneAt(GeoPoint(lat = card.lat, lon = card.lon)), "the card sits inside the memorial zone")
        assertTrue(PlayConfigValidator.validate(TestPack.base, index).isEmpty())
        // ...but only as a quiet card: with an effect it is a playful marker and is rejected
        val playful = TestPack.base.replace(TestPack.memorial.copy(fx = "frog_rock"))
        assertEquals(setOf(Rule.QUIET_ZONE_ANCHOR), PlayConfigValidator.validate(playful, index).map { it.rule }.toSet())
        // ...and any other item at the same place is rejected
        val egg = TestPack.base.replace(TestPack.egg.copy(where = mapOf(Goal.roads to Placement(at = "place:memorial-card"))))
        assertEquals(setOf(Rule.QUIET_ZONE_ANCHOR), PlayConfigValidator.validate(egg, index).map { it.rule }.toSet())
    }

    @Test
    fun placeZonesAndUnresolvedFeatures() {
        val built = QuietZones.build(
            listOf(QuietZone("owner-tagged", "place:frog-rock", 50), QuietZone("ghost", "trail:trail-nope", 100)),
            TestPack.base.places,
            index,
        )
        assertEquals(listOf("owner-tagged"), built.zones.map { it.id })
        assertEquals(listOf("ghost"), built.unresolved.map { it.id })
    }

    @Test
    fun quietSegmentsAreBufferHitsPlusQuietFeatures() {
        val qz = QuietZones(zones)
        val all = listOf("road-taylor", "road-safe-a").flatMap { index.segments(RoadRef(it)) }
        val far = index.segments(TrailRef("trail-pritchard-far"))
        val ids = qz.quietSegmentIds(all, quietFeatureSegments = far)
        assertEquals(setOf(all.first().id) + far.map { it.id }, ids)
    }
}
