package com.bainbridgetrails.play.rules

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The six trails rules (brief § 2, plan D1/D6) over a small synthetic island; the real-data cases are
 * `TrailRewardsGoldenTest` (G0 fixture). Every rule pays by key, never re-derived: paid keys are never taken back.
 */
class TrailRewardsTest {
    companion object {
        /**
         * Alpha Park: trail-a (30.0 m) + trail-b (69.9 m, steep). trail-c 0.1 m, trail-steep 40.0, trail-almost 39.9
         * (no park). trail-quiet: a quiet feature (66.5 m). trail-pritchard: Pritchard Park is not a Play park.
         * Solo Park has one trail (trail-one). Harbor Park's trail-blakely has one quiet-touching segment (s9).
         * s-open: counted metres on no trail (W-MILE only).
         */
        val tables = TrailTables(
            segmentLengthM = mapOf(
                "s1" to 100.0, "s2" to 200.0, "s3" to 150.0, "s4" to 300.0, "s5" to 50.0, "s6" to 80.0, "s7" to 120.0,
                "s8" to 90.0, "s9" to 60.0, "s10" to 40.0, "s11" to 25.0, "s-open" to 2_000.0,
            ),
            trailSegments = mapOf(
                "trail-a" to listOf("s1", "s2"),
                "trail-b" to listOf("s3"),
                "trail-c" to listOf("s11"),
                "trail-steep" to listOf("s4"),
                "trail-almost" to listOf("s5"),
                "trail-quiet" to listOf("s6"),
                "trail-pritchard" to listOf("s7"),
                "trail-one" to listOf("s8"),
                "trail-blakely" to listOf("s9", "s10"),
            ),
            quietSegments = setOf("s6", "s9"),
            quietFeatures = setOf("trail-quiet"),
            featurePark = mapOf(
                "trail-a" to "Alpha Park", "trail-b" to "Alpha Park", "trail-quiet" to "Alpha Park",
                "trail-pritchard" to "Pritchard Park", "trail-one" to "Solo Park", "trail-blakely" to "Harbor Park",
            ),
            featureGainM = mapOf(
                "trail-a" to 30.0, "trail-b" to 69.9, "trail-c" to 0.1, "trail-steep" to 40.0, "trail-almost" to 39.9,
                "trail-quiet" to 66.5, "trail-pritchard" to 10.0, "trail-one" to 8.4, "trail-blakely" to 7.7,
            ),
            parks = setOf("Alpha Park", "Harbor Park", "Solo Park"),
        )

        fun full(vararg trails: String): Map<String, Double> =
            trails.flatMap { tables.trailSegments.getValue(it) }.associateWith { tables.segmentLengthM.getValue(it) }
    }

    private fun keys(coverage: Map<String, Double>, completed: List<String>, island: Boolean = false, t: TrailTables = tables) =
        TrailRewards.qualifying(TrailRewards.inputs(coverage, completed, island, t), t)

    private fun marks(keys: Collection<String>) = keys.sumOf { TrailRewards.marksOf(it) }

    @Test
    fun `climb 99_9 m pays nothing`() {
        val q = keys(full("trail-a", "trail-b"), listOf("trail-a", "trail-b"))
        assertTrue(q.none { it.startsWith("climb:") }, "$q") // 300 + 699 = 999 dm < 1000
        val more = keys(full("trail-a", "trail-b", "trail-c"), listOf("trail-a", "trail-b", "trail-c"))
        assertEquals(listOf("climb:100"), more.filter { it.startsWith("climb:") }) // 1000 dm
        // partial coverage of a trail is not a completion: climb comes from the completed set only
        assertTrue(keys(full("trail-a", "trail-b", "trail-c"), emptyList()).none { it.startsWith("climb:") })
    }

    @Test
    fun `steep 40_0 pays 50 and 39_9 pays 0`() {
        val steep = keys(full("trail-steep"), listOf("trail-steep"))
        assertEquals(listOf("high:trail-steep"), steep.filter { it.startsWith("high:") })
        assertEquals(50, TrailRewards.marksOf("high:trail-steep"))
        assertEquals(LogKind.high_ground, TrailRewards.kindOf("high:trail-steep"))
        assertTrue(keys(full("trail-almost"), listOf("trail-almost")).none { it.startsWith("high:") })
        // re-completion: the key is already paid, nothing is owed
        val paid = TrailRewards.paid(steep, Logbook())
        assertTrue(TrailRewards.owed(steep, paid).isEmpty())
    }

    @Test
    fun `quiet trail gain 66_5 never pays`() {
        val input = TrailRewards.inputs(full("trail-quiet"), listOf("trail-quiet"), false, tables)
        assertEquals(0.0, input.nonQuietCoveredM)
        assertEquals(emptyList(), input.touchedTrails)
        assertEquals(emptySet(), TrailRewards.qualifying(input, tables))
    }

    @Test
    fun `one-trail park pays 50 and 150 same walk`() {
        val q = keys(full("trail-one"), listOf("trail-one"))
        assertEquals(setOf("park1:Solo Park", "parkc:Solo Park"), q)
        assertEquals(200, marks(q))
        assertEquals(LogKind.park_first, TrailRewards.kindOf("park1:Solo Park"))
        assertEquals(LogKind.park_complete, TrailRewards.kindOf("parkc:Solo Park"))
        // a park with a quiet feature among its trails completes without it
        val alpha = keys(full("trail-a", "trail-b"), listOf("trail-a", "trail-b"))
        assertTrue("parkc:Alpha Park" in alpha, "$alpha")
        assertTrue("parkc:Alpha Park" !in keys(full("trail-a", "trail-b"), listOf("trail-a")))
    }

    @Test
    fun `Pritchard Park never pays`() {
        val input = TrailRewards.inputs(full("trail-pritchard"), listOf("trail-pritchard"), false, tables)
        assertEquals(listOf("trail-pritchard"), input.touchedTrails)
        assertEquals(emptyList(), input.touchedParks)
        assertTrue(TrailRewards.qualifying(input, tables).none { "Pritchard" in it })
    }

    @Test
    fun `quiet Blakely segment starts no park`() {
        val quietOnly = TrailRewards.inputs(mapOf("s9" to 60.0), emptyList(), false, tables)
        assertEquals(0.0, quietOnly.nonQuietCoveredM)
        assertEquals(emptyList(), quietOnly.touchedParks)
        assertEquals(emptySet(), TrailRewards.qualifying(quietOnly, tables))
        val open = TrailRewards.inputs(mapOf("s9" to 60.0, "s10" to 12.5), emptyList(), false, tables)
        assertEquals(12.5, open.nonQuietCoveredM)
        assertEquals(setOf("park1:Harbor Park"), TrailRewards.qualifying(open, tables))
        // covered metres are capped at the segment's length; unknown (uncounted) segments add nothing
        assertEquals(40.0, TrailRewards.inputs(mapOf("s10" to 99.0, "s-unknown" to 500.0), emptyList(), false, tables).nonQuietCoveredM)
    }

    @Test
    fun `dropping a trail lowers nothing`() {
        val all = listOf("trail-a", "trail-b", "trail-c", "trail-one")
        val before = keys(full(*all.toTypedArray()), all)
        assertTrue("climb:100" in before && "high:trail-b" in before && "parkc:Solo Park" in before, "$before")
        val paid = TrailRewards.paid(before, Logbook())
        // a dataset without trail-b and Solo Park: climb drops to 30.1 m, the park is gone
        val smaller = tables.copy(
            trailSegments = tables.trailSegments - "trail-b" - "trail-one",
            featurePark = tables.featurePark - "trail-b" - "trail-one",
            featureGainM = tables.featureGainM - "trail-b" - "trail-one",
            parks = tables.parks - "Solo Park",
        )
        val after = keys(full(*all.toTypedArray()), all, t = smaller)
        assertTrue("climb:100" !in after && "high:trail-b" !in after, "$after")
        assertEquals(emptyList(), TrailRewards.owed(after, paid), "nothing new is owed")
        assertTrue(TrailRewards.paid(paid, Logbook()).containsAll(before), "paid keys are never re-derived")
    }

    @Test
    fun `adding a trail pays only its keys`() {
        val done = listOf("trail-a", "trail-b", "trail-c", "trail-one")
        val paid = TrailRewards.paid(keys(full(*done.toTypedArray()), done), Logbook())
        val bigger = tables.copy(
            segmentLengthM = tables.segmentLengthM + ("s-new" to 500.0),
            trailSegments = tables.trailSegments + ("trail-new" to listOf("s-new")),
            featurePark = tables.featurePark + ("trail-new" to "New Park"),
            featureGainM = tables.featureGainM + ("trail-new" to 45.0),
            parks = tables.parks + "New Park",
        )
        val now = done + "trail-new"
        val owed = TrailRewards.owed(keys(full(*done.toTypedArray()) + ("s-new" to 500.0), now, t = bigger), paid)
        // Σ climb 153.4 m: no new climb step; 1,065 m: no mile
        assertEquals(listOf("high:trail-new", "park1:New Park", "parkc:New Park"), owed)
        assertEquals(250, marks(owed))
    }

    @Test
    fun `keys are unit free`() {
        val q = keys(mapOf("s-open" to 1_609.344) + full("trail-a", "trail-b", "trail-c"), listOf("trail-a", "trail-b", "trail-c"))
        assertTrue("mile:1" in q && "climb:100" in q, "$q")
        assertTrue(q.none { "ft" in it || "km" in it || "." in it }, "$q")
        assertTrue("mile:1" !in keys(mapOf("s-open" to 1_609.0), emptyList()))
        assertEquals(listOf(1, 5, 10, 15, 20), TrailRewards.mileSteps(24.9))
        // review P2-A: capped at the Worker's MILE_STEPS (… 55, 60): no mile:65 at 105,000 m
        assertEquals(listOf(1) + (5..60 step 5), TrailRewards.mileSteps(105_000 / TrailRewards.METRES_PER_MILE))
        assertEquals(TrailRewards.MILE_STEPS, TrailRewards.mileSteps(1_000.0))
        assertEquals(listOf(100, 250, 500, 750, 1_000, 1_250), TrailRewards.CLIMB_STEPS_M)
        // island: once, by key
        assertEquals(setOf("island"), keys(emptyMap(), emptyList(), island = true))
        assertEquals(1_000, TrailRewards.marksOf("island"))
        assertEquals(0, TrailRewards.marksOf("bogus:1"))
        assertEquals(null, TrailRewards.kindOf("bogus:1"))
    }

    @Test
    fun `paid is the ledger and the trail kinds of the Logbook`() {
        val d = LocalDate(2026, 10, 31)
        val lb = Logbook()
            .add(LogEntry("1", LogKind.park_first, "Solo Park", "x", d, 50, walkId = "w", contentId = "park1:Solo Park"))
            .add(LogEntry("2", LogKind.complete, "Trail", "x", d, 100, walkId = "w", contentId = "trail-one"))
            .add(LogEntry("3", LogKind.island, "The whole island", "x", d, 1_000, contentId = "island"))
        assertEquals(setOf("climb:100", "park1:Solo Park", "island"), TrailRewards.paid(setOf("climb:100"), lb))
    }

    @Test
    fun `entries carry the key as contentId and the frozen text`() {
        var n = 0
        val d = LocalDate(2026, 10, 31)
        val es = TrailRewards.entries(
            listOf("mile:10", "climb:1250", "parkc:Solo Park", "island", "high:trail-b", "park1:Solo Park", "climb:250"),
            { if (it == "trail-b") "Bee Trail" else null }, d, "w1",
        ) { "e${n++}" }
        assertEquals(
            listOf(
                LogKind.high_ground, LogKind.park_first, LogKind.park_complete, LogKind.climb, LogKind.climb,
                LogKind.milestone, LogKind.island,
            ),
            es.map { it.kind },
        )
        assertEquals(listOf("Bee Trail", "Solo Park", "Solo Park", "250 m climbed", "1,250 m climbed", "10 mi of trail walked", "The whole island"), es.map { it.what })
        assertEquals(listOf("high:trail-b", "park1:Solo Park", "parkc:Solo Park", "climb:250", "climb:1250", "mile:10", "island"), es.map { it.contentId })
        assertEquals(listOf(50, 50, 150, 50, 50, 50, 1_000), es.map { it.marks })
        assertTrue(es.all { it.walkId == "w1" && it.date == d })
        assertEquals(LogCopy.why(LogKind.climb), es[3].why)
        // a high-ground trail without a name keeps its id
        assertEquals("trail-x", TrailRewards.entries(listOf("high:trail-x"), { null }, d, null) { "z" }.single().what)
    }
}
