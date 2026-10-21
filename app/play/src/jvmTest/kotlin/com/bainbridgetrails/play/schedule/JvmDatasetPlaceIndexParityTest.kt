package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigResult
import com.bainbridgetrails.play.geo.ChartSquare
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.geo.QuietZones
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review round 1 P1-7: the committed map-data fixture (`src/jvmTest/resources/fixture/places.json`) stands in for the
 * gitignored datasets, so it must answer as they do. Always: the fixture is from the datasets the committed derived
 * tables were built from, within its size budget, and every derived stretch resolves. With the datasets built here
 * (and not `-Pplay.noData=true`): features, segments, lengths, geometry, trailheads, stretches, chart squares,
 * quiet segments and eight weeks of agenda are the same on both sources.
 */
class JvmDatasetPlaceIndexParityTest {
    private val fixture = JvmDatasetPlaceIndex.fixture

    @Test
    fun `fixture matches the committed derived tables`() {
        assertEquals(JvmDatasetPlaceIndex.Source.FIXTURE, fixture.source)
        val bytes = javaClass.getResource(JvmDatasetPlaceIndex.FIXTURE)!!.readBytes().size
        assertTrue(bytes <= 600 * 1024, "fixture $bytes bytes > 600 KiB")
        for (goal in Goal.entries) {
            val derived = Json.parseToJsonElement(File(RealPack.ROOT, "data/play/derived/${goal.name}.json").readText()).jsonObject
            assertEquals(derived.getValue("dataset").jsonPrimitive.content, fixture.versions[goal], "$goal: rerun node scripts/build-play-content.mjs")
            val stretches = derived["stretches"]?.jsonObject.orEmpty()
            for (id in stretches.keys) assertTrue(fixture.segments(StretchRef(id)).isNotEmpty(), "stretch:$id resolves")
        }
        assertTrue(fixture.features(Goal.trails).isNotEmpty() && fixture.features(Goal.roads).isNotEmpty())
    }

    @Test
    fun `fixture answers as the datasets`() {
        val real = JvmDatasetPlaceIndex.datasets
        if (real == null) {
            println("JvmDatasetPlaceIndexParityTest: datasets not built here (or -Pplay.noData=true): fixture-only checks")
            return
        }
        assertEquals(real.versions, fixture.versions, "fixture stale: node scripts/build-play-content.mjs --fixture")
        assertEquals(real.trailheads, fixture.trailheads)
        for (goal in Goal.entries) {
            assertEquals(real.features(goal), fixture.features(goal), "$goal features")
            for (f in real.features(goal)) {
                val ref = if (goal == Goal.trails) TrailRef(f.id) else RoadRef(f.id)
                assertEquals(real.segments(ref), fixture.segments(ref), "${f.id} segments (lossless)")
                assertEquals(real.segments(StretchRef(f.id)), fixture.segments(StretchRef(f.id)), "stretch:${f.id}")
            }
            assertEquals(real.includedSegments(goal), fixture.includedSegments(goal), "$goal included segments (lossless)")
            val fx = fixture.allSegments(goal).associateBy { it.id }
            assertEquals(real.allSegments(goal).map { it.id }.toSet(), fx.keys, "$goal segment ids")
            // simplified segments: within the build's tolerance (5 m) + 1e-5° rounding of the datasets' lines
            for (s in real.allSegments(goal)) {
                val f = fx.getValue(s.id)
                if (f == s) continue
                assertEquals(s.copy(midLat = 0.0, midLon = 0.0, lengthM = 0.0, line = emptyList()), f.copy(midLat = 0.0, midLon = 0.0, lengthM = 0.0, line = emptyList()), s.id)
                for (p in s.line) assertTrue(GeoMath.pointToLineM(p, f.line) <= 6.0, "${s.id}: dataset vertex $p off the fixture line")
                for (p in f.line) assertTrue(GeoMath.pointToLineM(p, s.line) <= 1.0, "${s.id}: fixture vertex $p off the dataset line")
            }
            assertEquals(
                ChartSquare.covering(real.includedSegments(goal).map { it.line }),
                ChartSquare.covering(fixture.includedSegments(goal).map { it.line }),
                "$goal chart squares",
            )
        }
        val pack = File(RealPack.ROOT, "data/play/play-content.json").readText()
        val config = (PlayConfig.decode(pack) as PlayConfigResult.Ok).config
        fun quiet(index: JvmDatasetPlaceIndex) = QuietZones(QuietZones.build(config.quietZones, config.places, index).zones)
            .let { z -> Goal.entries.associateWith { z.quietSegmentIds(index.allSegments(it), emptyList()) } }
        assertEquals(quiet(real), quiet(fixture), "quiet segments")
        val first = LocalDate(2026, 10, 26)
        val last = LocalDate(2026, 12, 20)
        for (goal in Goal.entries) {
            assertEquals(
                PlaySchedulerGoldenTest.agenda(RealPack.scheduler(real), goal, first, last),
                PlaySchedulerGoldenTest.agenda(RealPack.scheduler(fixture), goal, first, last),
                "$goal agenda",
            )
        }
    }
}
