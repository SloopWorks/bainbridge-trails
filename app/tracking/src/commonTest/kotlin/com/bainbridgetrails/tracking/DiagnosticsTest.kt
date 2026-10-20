package com.bainbridgetrails.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiagnosticsTest {
    @Test
    fun emptyWindowHasNoMean() {
        val a = DiagAccumulator()
        assertEquals(SessionEvent.Diag(1000, 50, 0, null), a.flush(1000, 50))
    }

    @Test
    fun meanAndCountOverWindowThenReset() {
        val a = DiagAccumulator()
        a.onFix(4.0)
        a.onFix(8.0)
        a.onFix(null) // counts as a fix, no accuracy
        assertEquals(SessionEvent.Diag(2000, 80, 3, 6.0), a.flush(2000, 80))
        val next = a.flush(3000, null)
        assertEquals(0, next.fixCount)
        assertNull(next.meanAccuracyM)
        assertNull(next.batteryPct)
    }

    @Test
    fun batteryPercentFromLevelAndScale() {
        assertEquals(50, batteryPercent(50, 100))
        assertEquals(33, batteryPercent(1, 3))
        assertNull(batteryPercent(-1, 100))
        assertNull(batteryPercent(5, 0))
    }
}

class DiagResolveTest {
    @Test
    fun writerCountsFixesAndResolvesTick() {
        val a = DiagAccumulator()
        a.resolve(SessionEvent.FixEvent(1, 0.0, 0.0, 10.0, false))
        a.resolve(SessionEvent.FixEvent(2, 0.0, 0.0, 20.0, false))
        assertEquals(SessionEvent.Diag(300, 71, 2, 15.0), a.resolve(diagTick(300, 71)))
        assertEquals(SessionEvent.Diag(600, 70, 0, null), a.resolve(diagTick(600, 70)))
    }
}
