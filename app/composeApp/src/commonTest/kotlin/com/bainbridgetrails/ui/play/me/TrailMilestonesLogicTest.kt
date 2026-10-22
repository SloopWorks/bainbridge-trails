package com.bainbridgetrails.ui.play.me

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.play.TrailFixtures
import com.bainbridgetrails.play.rules.TrailRewardInput
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.rules.TrailTables
import com.bainbridgetrails.ui.MilestoneState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/play/trails/ux.md § 5 (UX-5) over the bundled `derived-trails.json` (19 Play parks, 10 high-ground trails, the
 * 1,368.8 m climb): the strip is always four badges in a fixed order, the sheet lists every ladder, park and steep
 * trail, and every count comes from the tables (ux § 0.5), never a constant. Roads has no model (ux § 0.1, UX-1).
 */
class TrailMilestonesLogicTest {
    private val mi = Units.MILES
    private val km = Units.KILOMETRES
    private val mile = TrailRewards.METRES_PER_MILE
    private val gfe = "trail-grand-forest-east-trail"

    /** The fixture tables plus one stand-in segment, so the island's non-quiet distance is today's 102,008.8 m. */
    private val tables: TrailTables = TrailFixtures.tables.let { t ->
        t.copy(segmentLengthM = t.segmentLengthM + ("seg-rest" to 102_008.8 - t.segmentLengthM.values.sum()))
    }

    private val names: (String) -> String? = { TrailFixtures.names[it] }

    private fun model(input: TrailRewardInput, t: TrailTables = tables): TrailMilestonesModel =
        assertNotNull(TrailMilestonesLogic.model(t, input, names, isRoads = false))

    private val newPlayer = TrailRewardInput(0.0, emptyList(), emptyList(), emptySet(), islandWhole = false)

    /** The AC 8 E2E state after walk 3: Hayano, Peter's and Grand Forest East completed, 3,290 m of trail covered. */
    private val afterThreeWalks = TrailRewardInput(
        nonQuietCoveredM = 3_290.0,
        touchedTrails = listOf(gfe, TrailFixtures.HAYANO, TrailFixtures.PETERS),
        touchedParks = listOf(TrailFixtures.GAZZAM, "Grand Forest", TrailFixtures.MORITANI),
        completed = setOf(TrailFixtures.HAYANO, TrailFixtures.PETERS, gfe),
        islandWhole = false,
    )

    private fun TrailBadge.shown() = listOf(ladder.name, state.name, mark, progress?.let { "${it.first} / ${it.second}" })

    @Test
    fun `4 badges fixed order with fixture values`() {
        val strip = TrailMilestonesLogic.strip(model(afterThreeWalks), mi)
        assertEquals(
            listOf(
                listOf("CLIMB", "NEXT", "820", "522 / 820"), // Σ 159.2 m = 522.3 ft (E2E "522 / 820")
                listOf("MILES", "NEXT", "5", "2.0 / 5"), // 3,290 m = 2.04 mi
                listOf("PARKS", "NEXT", "19", "1 / 19"), // Moritani finished
                listOf("HIGH", "NEXT", "10", "2 / 10"), // Peter's 92.1, Grand Forest East 58.7
            ),
            strip.map { it.shown() },
        )
        assertEquals("820 ft", strip[0].value?.text)
        assertEquals("5 mi", strip[1].value?.text)
        assertNull(strip[2].value)
        assertEquals(1592.0 / 2500, strip[0].fraction, 1e-9)
        assertEquals(1.0 / 19, strip[2].fraction, 1e-9)
        // km: the same keys, other display units.
        val kmStrip = TrailMilestonesLogic.strip(model(afterThreeWalks), km)
        assertEquals(listOf("250", "8.0", "19", "10"), kmStrip.map { it.mark })
        assertEquals("159 / 250", kmStrip[0].progress?.let { "${it.first} / ${it.second}" })
    }

    @Test
    fun `new player 0 of 328`() {
        val strip = TrailMilestonesLogic.strip(model(newPlayer), mi)
        assertEquals(
            listOf(
                listOf("CLIMB", "NEXT", "328", "0 / 328"),
                listOf("MILES", "NEXT", "1", "0.0 / 1"),
                listOf("PARKS", "NEXT", "19", "0 / 19"),
                listOf("HIGH", "NEXT", "10", "0 / 10"),
            ),
            strip.map { it.shown() },
        )
        assertTrue(strip.all { it.fraction == 0.0 })
        assertEquals("0.0 / 1.6", TrailMilestonesLogic.strip(model(newPlayer), km)[1].progress?.let { "${it.first} / ${it.second}" })
    }

    @Test
    fun `every ladder done shows four earned badges without progress`() {
        val all = tables.gainDm.keys.filter { it !in tables.quietFeatures }.toSet()
        val done = TrailRewardInput(102_008.8, emptyList(), tables.parks.sorted(), all, islandWhole = true)
        val strip = TrailMilestonesLogic.strip(model(done), mi)
        assertEquals(
            listOf(
                listOf("CLIMB", "EARNED", "4,101", null),
                listOf("MILES", "EARNED", "60", null),
                listOf("PARKS", "EARNED", "19", null),
                listOf("HIGH", "EARNED", "10", null),
            ),
            strip.map { it.shown() },
        )
        assertTrue(strip.all { it.fraction == 1.0 })
    }

    @Test
    fun `sheet - 6 climb steps, miles to reachable top 60, 19 parks alphabetical without Pritchard, 10 high-ground steepest first`() {
        val m = model(afterThreeWalks)
        val climb = TrailMilestonesLogic.climbLadder(m, mi)
        assertEquals(listOf("328", "820", "1,640", "2,461", "3,281", "4,101"), climb.map { it.mark })
        assertEquals(
            listOf(MilestoneState.EARNED, MilestoneState.NEXT) + List(4) { MilestoneState.LOCKED },
            climb.map { it.state },
        )
        assertEquals("522" to "820", climb[1].progress)
        assertNull(climb[0].progress)
        assertEquals("522 ft", TrailMilestonesLogic.climbSoFar(m, mi).text)

        // Miles: earned, the next, then 2 locked (roads lockedAhead) ...
        val miles = TrailMilestonesLogic.mileLadder(m, mi)
        assertEquals(listOf(1, 5, 10, 15), miles.map { it.step })
        assertEquals(listOf(MilestoneState.EARNED, MilestoneState.NEXT, MilestoneState.LOCKED, MilestoneState.LOCKED), miles.map { it.state })
        assertEquals(listOf("1.6", "8.0", "16.1", "24.1"), TrailMilestonesLogic.mileLadder(m, km).map { it.mark })
        assertEquals("2.0 mi", TrailMilestonesLogic.milesSoFar(m, mi).text)
        // ... capped at the reachable top: 102,008.8 m = 63.4 mi → 60.
        assertEquals(60, m.mileSteps.last())
        val far = model(afterThreeWalks.copy(nonQuietCoveredM = 57 * mile))
        val farLadder = TrailMilestonesLogic.mileLadder(far, mi)
        assertEquals(listOf(1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60), farLadder.map { it.step })
        assertEquals(MilestoneState.NEXT, farLadder.last().state)
        assertEquals(listOf(1, 5, 10), TrailMilestonesLogic.mileLadder(model(newPlayer), mi).map { it.step })

        // Parks: one row per Play park, alphabetical, no Pritchard Park.
        assertEquals(19, m.parks.size)
        assertEquals(m.parks.map { it.park }.sorted(), m.parks.map { it.park })
        assertTrue(m.parks.none { "Pritchard" in it.park })
        val state = m.parks.associate { it.park to it.state }
        assertEquals(ParkState.FINISHED, state[TrailFixtures.MORITANI])
        assertEquals(ParkState.STARTED, state[TrailFixtures.GAZZAM])
        assertEquals(ParkState.STARTED, state["Grand Forest"])
        assertEquals(16, state.values.count { it == ParkState.NOT_STARTED })
        assertEquals(1 to 2, m.parksFinished to m.parksStarted)

        // High ground: every non-quiet trail ≥ 40.0 m, steepest first; names from the dataset, else the id.
        assertEquals(
            listOf(
                "trail-peters-trail", "trail-hidden-cove-trails", "trail-main-loop-trail", gfe, "trail-manzanita-park",
                "trail-grand-forest-west", "trail-mcredmond-trail", "trail-lost-valley-trail", "trail-kojima-trail",
                "trail-trillium-trail",
            ),
            m.high.map { it.trailId },
        )
        assertEquals(listOf(true, false, false, true, false, false, false, false, false, false), m.high.map { it.earned })
        assertEquals("Peter's Trail", m.high[0].name)
        assertEquals("302 ft", TrailMilestonesLogic.highClimb(m.high[0], mi).text)
        assertEquals(gfe, m.high[3].name)
        assertEquals(2, m.highEarned)
    }

    @Test
    fun `counts come from the tables`() {
        val fewerParks = tables.copy(parks = tables.parks - "Grand Forest")
        val noTrillium = tables.copy(featureGainM = tables.featureGainM - "trail-trillium-trail")
        val quietKojima = tables.copy(quietFeatures = tables.quietFeatures + "trail-kojima-trail")
        assertEquals("18", TrailMilestonesLogic.strip(model(newPlayer, fewerParks), mi)[2].mark)
        assertEquals(18, model(newPlayer, fewerParks).parks.size)
        assertEquals("9", TrailMilestonesLogic.strip(model(newPlayer, noTrillium), mi)[3].mark)
        assertEquals(9, model(newPlayer, quietKojima).high.size)
        // A smaller island lowers the reachable mile top; a park the tables drop is neither listed nor counted.
        val small = tables.copy(segmentLengthM = mapOf("seg-x" to 11 * mile))
        assertEquals(listOf(1, 5, 10), model(newPlayer, small).mileSteps)
        assertEquals(1 to 1, model(afterThreeWalks, fewerParks).let { it.parksFinished to it.parksStarted })
        // Climb sums the tables' decimetres of completed non-quiet trails (quiet Blakley Cemetery adds nothing).
        val withQuiet = afterThreeWalks.copy(completed = afterThreeWalks.completed + "trail-blakley-cemetary-trail")
        assertEquals(1_592, model(withQuiet).climbDm)
    }

    @Test
    fun `roads model null`() {
        assertNull(TrailMilestonesLogic.model(tables, afterThreeWalks, names, isRoads = true))
        assertNull(TrailMilestonesLogic.model(null, afterThreeWalks, names, isRoads = false))
        assertNull(TrailMilestonesLogic.model(tables, null, names, isRoads = false))
    }
}
