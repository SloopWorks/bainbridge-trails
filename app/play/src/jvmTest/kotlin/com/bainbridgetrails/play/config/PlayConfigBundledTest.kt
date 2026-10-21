package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.geo.QuietZones
import com.bainbridgetrails.play.geo.check
import com.bainbridgetrails.play.schedule.JvmDatasetPlaceIndex
import com.bainbridgetrails.play.schedule.TrailRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * schedule.md §4.1 step 3: the bundled `composeResources/files/play/play-content.json` (copied there by
 * `scripts/build-play-content.mjs`) decodes and validates. Structural rules always run; dataset rules (refs,
 * roadSafety, quiet zones, parks) run against [JvmDatasetPlaceIndex] and are skipped when the datasets are absent.
 */
class PlayConfigBundledTest {
    private val bundled = File("../composeApp/src/commonMain/composeResources/files/play/play-content.json")

    private fun bundledConfig(): PlayConfig {
        assertTrue(bundled.exists(), "${bundled.path} missing: run node scripts/build-play-content.mjs")
        return when (val r = PlayConfig.decode(bundled.readText())) {
            is PlayConfigResult.Ok -> r.config
            is PlayConfigResult.Rejected -> fail("bundled pack rejected:\n" + r.errors.joinToString("\n"))
        }
    }

    @Test
    fun bundledPackValidatesStructurally() {
        val config = bundledConfig()
        val errs = PlayConfigValidator.validate(config, places = null)
        assertTrue(errs.isEmpty(), errs.joinToString("\n"))
        // the bundle is a copy of the one source file
        val source = File("../../data/play/play-content.json")
        if (source.exists()) assertEquals(source.readText(), bundled.readText(), "bundle out of date: rerun the build script")
    }

    @Test
    fun bundledPackValidatesAgainstTheDatasets() {
        val index = JvmDatasetPlaceIndex.loadOrSkip("PlayConfigBundledTest")
        val errs = PlayConfigValidator.validate(bundledConfig(), index)
        assertTrue(errs.isEmpty(), errs.joinToString("\n"))
    }

    @Test
    fun publishedPacksAreNeverEditedInPlace() {
        val config = bundledConfig()
        val published = File("../../data/play/published").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .filter { it.nameWithoutExtension != config.version }
        for (f in published) {
            val prev = (PlayConfig.decode(f.readText()) as? PlayConfigResult.Ok)?.config ?: continue
            val errs = PlayConfigValidator.validate(config, places = null, previous = prev)
            assertTrue(errs.isEmpty(), "${f.name}:\n" + errs.joinToString("\n"))
        }
    }

    @Test
    fun examplePackFixtureValidatesStructurally() {
        val text = javaClass.getResource("/play/example-pack.json")!!.readText()
        val config = (PlayConfig.decode(text) as PlayConfigResult.Ok).config
        val errs = PlayConfigValidator.validate(config, places = null)
        assertTrue(errs.isEmpty(), errs.joinToString("\n"))
    }

    @Test
    fun quietZonesAndSegmentsFromTheDatasets() {
        val index = JvmDatasetPlaceIndex.loadOrSkip("PlayConfigBundledTest")
        val config = bundledConfig()
        val built = QuietZones.build(config.quietZones, config.places, index)
        assertTrue(built.unresolved.isEmpty(), built.unresolved.toString())
        assertEquals(listOf(7, 3), built.zones.map { it.lines.size }, "memorial 7 segments, cemetery 3 (PD-6)")
        val zones = QuietZones(built.zones)
        // the old hand-typed circle sat ≈ 0.6 km from the memorial trail
        assertEquals(null, zones.zoneAt(GeoPoint(lat = 47.6098, lon = -122.5076)))
        val card = config.places.getValue("memorial-card")
        assertNotNull(zones.zoneAt(GeoPoint(lat = card.lat, lon = card.lon)))

        val touchingTrails = index.features(Goal.trails).filter { zones.touching(index.segments(TrailRef(it.id))) != null }.map { it.id }
        assertEquals(
            setOf(
                "trail-bainbridge-island-japanese-american-memorial-trail", "trail-blakley-cemetary-trail",
                "trail-pritchard-trail", "trail-blakely-harbor-trail", "trail-blakely-trail", "trail-blakely-hills-trail",
            ),
            touchingTrails.toSet(),
        )
        val touchingSafeRoads = index.features(Goal.roads)
            .filter { config.rules.roadSafety.check(it) == null }
            .filter { zones.touching(index.segments(com.bainbridgetrails.play.schedule.RoadRef(it.id))) != null }
            .map { it.id }
        assertEquals(setOf("road-taylor-avenue-northeast", "road-welfare-avenue-northeast"), touchingSafeRoads.toSet())

        val quietFeatureSegs = (config.quietZones.mapNotNull { (com.bainbridgetrails.play.schedule.Ref.parse(it.feature) as? TrailRef) } +
            index.features(Goal.trails).filter { it.park in config.excludedParks }.map { TrailRef(it.id) })
            .flatMap { index.segments(it) }
        val trailsQuiet = zones.quietSegmentIds(index.allSegments(Goal.trails), quietFeatureSegs)
        val roadsQuiet = zones.quietSegmentIds(index.allSegments(Goal.roads), emptyList())
        println("PlayConfigBundledTest: quiet segments ${trailsQuiet.size} trails / ${roadsQuiet.size} roads")
        assertEquals(55, trailsQuiet.size)
        assertEquals(62, roadsQuiet.size)
    }
}
