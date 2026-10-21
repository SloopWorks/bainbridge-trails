package com.bainbridgetrails.play

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.schedule.DerivedGoal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one parse of `derived-<goal>.json` yields what the scheduler's own decoder ([DerivedGoal.decode]) reads. */
class PlayDerivedTest {
    @Test
    fun derivedGoalMatchesTheSchedulerDecoder() {
        for (goal in Goal.entries) {
            val text = PlayFixtures.read("derived-${goal.name}.json")
            val d = PlayDerived.parse(text)
            assertEquals(DerivedGoal.decode(text), d.derivedGoal(), goal.name)
            assertEquals(goal, d.goal)
            assertTrue(d.playSafe.isNotEmpty(), "${goal.name} play-safe pool")
            assertNotNull(d.stretchSegments, "${goal.name} stretches")
        }
        assertTrue(PlayDerived.parse(PlayFixtures.read("derived-roads.json")).roadSafety.isNotEmpty(), "roads roadSafety pass list")
    }

    @Test
    fun missingTableReadsEmpty() {
        assertNull(PlayDerived.parse(null).derivedGoal())
        assertNull(PlayDerived.parse("{}").stretchSegments, "no stretches key: stretches resolve to the whole feature")
        assertNull(PlayDerived.parse("{}").includedSegments)
    }
}
