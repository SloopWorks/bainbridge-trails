package com.bainbridgetrails.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StepHeartbeatTest {
    @Test
    fun heartbeatHoldsUnchangedCount() {
        val h = StepHeartbeat()
        assertNull(h.resolve(stepTick(10_000)), "no count known yet: nothing to assert")
        val live = SessionEvent.Steps(StepSample(12_000, 42))
        assertEquals(live, h.resolve(live))
        assertEquals(SessionEvent.Steps(StepSample(42_000, 42)), h.resolve(stepTick(42_000)))
        assertEquals(SessionEvent.Steps(StepSample(72_000, 42)), h.resolve(stepTick(72_000)), "unchanged count still written")
        h.resolve(SessionEvent.Steps(StepSample(80_000, 50)))
        assertEquals(SessionEvent.Steps(StepSample(102_000, 50)), h.resolve(stepTick(102_000)))
        val fix = SessionEvent.FixEvent(1, 0.0, 0.0, null, false)
        assertEquals(fix, h.resolve(fix))
    }

    @Test
    fun heartbeatIntervalIsThirtySeconds() = assertEquals(30_000L, STEP_HEARTBEAT_MS)
}
