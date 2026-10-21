package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.Band
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.TestPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** product §3.2–§3.3, PD-2; mirrored by backend/src/play/marks.ts. */
class MarksTest {
    @Test
    fun `W-COV trails floor nonQuiet over 10`() {
        assertEquals(0, Marks.coverage(Goal.trails, 9.99))
        assertEquals(1, Marks.coverage(Goal.trails, 10.0))
        assertEquals(10_296, Marks.coverage(Goal.trails, 102_964.0)) // today's whole map outside quiet trails
        assertEquals(840, Marks.coverage(Goal.trails, 8_400.0)) // Marcus's starting balance, §3.9
        // a walk earns the cumulative value after minus before (never a share of the island)
        assertEquals(180, Marks.coverageDelta(Goal.trails, 8_400.0, 10_200.0))
        assertEquals(1, Marks.coverageDelta(Goal.trails, 9.0, 11.0))
        assertEquals(0, Marks.coverageDelta(Goal.trails, 500.0, 400.0), "monotone: a smaller map never takes marks back")
    }

    @Test
    fun `roads over 25`() {
        assertEquals(864, Marks.coverage(Goal.roads, 21_600.0)) // Dana, §3.9
        assertEquals(10_461, Marks.coverage(Goal.roads, 261_537.0))
        assertEquals(24, Marks.coverageDelta(Goal.roads, 21_600.0, 22_200.0))
        assertEquals(WalkDerived(coverage = 840, complete = 200, squares = 150), Marks.walkDerived(Goal.trails, 8_400.0, 2, 6))
        assertEquals(1_190, Marks.walkDerived(Goal.trails, 8_400.0, 2, 6).total)
        assertEquals(25, Marks.walkDerived(Goal.roads, 0.0, 1, 0).total)
    }

    @Test
    fun `F-INK buckets 60 70 80`() {
        val bands = TestPack.kraken.bands
        assertEquals(listOf(Band(60, 60), Band(80, 70), Band(95, 80)), bands)
        assertNull(Marks.inkBand(bands, 0.5999))
        assertEquals(Band(60, 60), Marks.inkBand(bands, 0.60))
        assertEquals(Band(60, 60), Marks.inkBand(bands, 0.7999))
        assertEquals(Band(80, 70), Marks.inkBand(bands, 0.80))
        assertEquals(Band(80, 70), Marks.inkBand(bands, 0.949))
        assertEquals(Band(95, 80), Marks.inkBand(bands, 0.95))
        assertEquals(Band(95, 80), Marks.inkBand(bands, 1158.0 / 1158.0))
    }

    @Test
    fun `riddle hints -25 pct floor 50 pct`() {
        assertEquals(40, Marks.riddle(stars = 1, hints = 0))
        assertEquals(80, Marks.riddle(2, 0))
        assertEquals(60, Marks.riddle(2, 1)) // PF8-3, Marcus's "Bought with a bond"
        assertEquals(40, Marks.riddle(2, 2))
        assertEquals(60, Marks.riddle(3, 2))
        assertEquals(60, Marks.riddle(3, 5), "never below 50 %")
        assertEquals(120, Marks.riddle(3, -1))
    }

    @Test
    fun `remembrance 0`() {
        assertEquals(0, Marks.found(TestPack.memorial))
        assertEquals(0, Marks.found(TestPack.memorial.copy(marks = 15)), "remembrance pays 0 whatever the pack says")
        assertEquals(15, Marks.found(TestPack.lore))
        assertEquals(20, Marks.found(TestPack.egg))
        assertEquals(100, Marks.found(TestPack.challenge))
        // walk-derived constants (§3.2)
        assertEquals(15, Marks.DAY)
        assertEquals(25, Marks.SQUARE)
        assertEquals(100, Marks.doneMarks(Goal.trails))
        assertEquals(25, Marks.doneMarks(Goal.roads))
        assertEquals(40, Marks.keptWeek(0))
        assertEquals(70, Marks.keptWeek(3))
        assertEquals(100, Marks.keptWeek(9))
        assertEquals(true, Marks.isWalkingDay(durationMs = 10 * 60_000L, matchedM = 500.0))
        assertEquals(false, Marks.isWalkingDay(durationMs = 9 * 60_000L, matchedM = 5_000.0))
        assertEquals(false, Marks.isWalkingDay(durationMs = 60 * 60_000L, matchedM = 499.0))
    }
}
