package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.ChallengeMetric
import com.bainbridgetrails.play.schedule.RealPack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan G0 / L1-1, brief AC 2: the shared parity fixture `data/play/golden/trails-rewards.json` (read-only; the Worker's
 * `backend/test/play-trails.test.ts` reads the same cases). Tables come from the committed files the fixture was
 * computed from (its `_note`): `data/play/derived/trails.json` (quiet sets, featurePark, featureGainM, parks,
 * included segments) and `backend/src/data/segments.json` (counted segment lengths, trail membership). The fixture's
 * `tables` summary is the drift guard on both.
 */
class TrailRewardsGoldenTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun read(path: String): JsonObject = json.parseToJsonElement(File(RealPack.ROOT, path).readText()).jsonObject
    private fun strings(a: Any?): List<String> = (a as JsonArray).map { it.jsonPrimitive.content }

    private val golden = read("data/play/golden/trails-rewards.json")

    private val tables: TrailTables by lazy {
        val derived = read("data/play/derived/trails.json")
        val segments = read("backend/src/data/segments.json")
        // [lengthM, ticks, weight, counted]: only counted (included) segments count
        val counted = segments.getValue("segments").jsonObject
            .filter { (_, v) -> v.jsonArray[3].jsonPrimitive.int == 1 }
            .mapValues { (_, v) -> v.jsonArray[0].jsonPrimitive.double }
        assertEquals(derived.getValue("segmentSquares").jsonObject.keys, counted.keys, "segments.json counted set = derived included segments")
        TrailTables(
            segmentLengthM = counted,
            trailSegments = segments.getValue("trails").jsonObject.mapValues { (_, v) -> strings(v) },
            quietSegments = strings(derived["quietSegments"]).toSet(),
            quietFeatures = strings(derived["quietFeatures"]).toSet(),
            featurePark = derived.getValue("featurePark").jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content },
            featureGainM = derived.getValue("featureGainM").jsonObject.mapValues { (_, v) -> v.jsonPrimitive.double },
            parks = strings(derived["parks"]).toSet(),
        )
    }

    @Test
    fun `tables match the fixture summary`() {
        val t = golden.getValue("tables").jsonObject
        assertEquals(t.getValue("countedSegments").jsonPrimitive.int, tables.segmentLengthM.size)
        assertEquals(t.getValue("quietSegments").jsonPrimitive.int, tables.quietSegments.size)
        assertEquals(strings(t["quietFeatures"]).toSet(), tables.quietFeatures)
        assertEquals(strings(t["parks"]), tables.parks.sorted())
        val nonQuiet = tables.featureGainM.keys - tables.quietFeatures
        assertEquals(t.getValue("nonQuietTrails").jsonPrimitive.int, nonQuiet.size)
        assertEquals((t.getValue("nonQuietGainM").jsonPrimitive.double * 10).roundToInt(), nonQuiet.sumOf { tables.gainDm.getValue(it) })
        val island = TrailRewards.inputs(tables.segmentLengthM, emptyList(), false, tables).nonQuietCoveredM
        assertTrue(abs(island - t.getValue("islandNonQuietM").jsonPrimitive.double) < 0.05, "$island")
        val steep = t.getValue("steepTrails").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(steep.toSet(), nonQuiet.filter { tables.gainDm.getValue(it) >= TrailRewards.STEEP_DM }.toSet())
        val rules = golden.getValue("rules").jsonObject
        assertEquals(rules.getValue("climbStepsM").jsonArray.map { it.jsonPrimitive.int }, TrailRewards.CLIMB_STEPS_M)
        assertEquals(rules.getValue("steepGainDm").jsonPrimitive.int, TrailRewards.STEEP_DM)
        assertEquals(rules.getValue("metresPerMile").jsonPrimitive.double, TrailRewards.METRES_PER_MILE)
        val mileSteps = rules.getValue("mileSteps").jsonArray.map { it.jsonPrimitive.int }
        assertEquals(mileSteps, TrailRewards.mileSteps(mileSteps.last().toDouble()))
        val marks = rules.getValue("marks").jsonObject.mapValues { it.value.jsonPrimitive.int }
        val sample = mapOf("climb" to "climb:100", "high_ground" to "high:x", "milestone" to "mile:1", "park_first" to "park1:x", "park_complete" to "parkc:x", "island" to "island")
        for ((kind, key) in sample) {
            assertEquals(marks.getValue(kind), TrailRewards.marksOf(key), kind)
            assertEquals(kind, TrailRewards.kindOf(key)!!.name)
        }
    }

    @Test
    fun `inputs and keys of every trails-rewards case`() {
        val cases = golden.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(26, cases.size)
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val input = c.getValue("input").jsonObject
            val coverage = input.getValue("coverage").jsonObject.mapValues { it.value.jsonPrimitive.double }
            val got = TrailRewards.inputs(coverage, strings(input["completed"]), input.getValue("islandWhole").jsonPrimitive.boolean, tables)
            val expect = c.getValue("expectInputs").jsonObject
            val m = expect.getValue("nonQuietCoveredM").jsonPrimitive.double
            assertTrue(abs(got.nonQuietCoveredM - m) < 1e-6, "$name: nonQuietCoveredM ${got.nonQuietCoveredM} ≠ $m")
            assertEquals(strings(expect["touchedTrails"]), got.touchedTrails, "$name: touchedTrails")
            assertEquals(strings(expect["touchedParks"]), got.touchedParks, "$name: touchedParks")
            val owed = TrailRewards.owed(TrailRewards.qualifying(got, tables), strings(c["paidBefore"]).toSet())
            assertEquals(strings(c["expectNewKeys"]), owed, "$name: new keys")
            val marks = c.getValue("expectMarks").jsonObject.mapValues { it.value.jsonPrimitive.int }
            val byKind = owed.groupBy { TrailRewards.kindOf(it)!!.name }.mapValues { (_, ks) -> ks.sumOf { TrailRewards.marksOf(it) } }
            for ((kind, n) in marks) assertEquals(n, byKind[kind] ?: 0, "$name: $kind marks")
        }
    }

    @Test
    fun `targets and claims of every challenge case`() {
        val cases = golden.getValue("challengeCases").jsonArray.map { it.jsonObject }
        assertEquals(15, cases.size)
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val metric = ChallengeMetric.valueOf(c.getValue("metric").jsonPrimitive.content)
            val p = c.getValue("progress")
            val progress = when (metric) {
                ChallengeMetric.climb -> TrailProgress(p.jsonPrimitive.int, emptySet())
                else -> TrailProgress(0, strings(p.jsonObject["parks"]).toSet())
            }
            val target = TrailRewards.challengeTarget(metric, c.getValue("default").jsonPrimitive.int, strings(c["completed"]).toSet(), progress, tables)
            assertEquals(c.getValue("expectTarget").jsonPrimitive.int, target, "$name: target")
            assertEquals(c.getValue("expectClaim").jsonPrimitive.boolean, TrailRewards.claims(target, progress.value(metric)), "$name: claim")
        }
    }
}
