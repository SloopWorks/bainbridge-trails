package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.LocalProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatingTest {
    private val proj = LocalProjection()
    private val t0 = 1_700_000_000_000L

    /** One fix every [stepS] seconds for [seconds], walking east at [mps]. */
    private fun walk(startS: Int, seconds: Int, mps: Double, stepS: Int = 10): List<Fix> =
        (startS..startS + seconds step stepS).map { s ->
            val (lon, lat) = proj.toLonLat(com.bainbridgetrails.coverage.Xy(s * mps, 0.0))
            Fix(t0 + s * 1000L, lon, lat)
        }

    private fun session(
        fixes: List<Fix>,
        acts: List<ActivitySample> = emptyList(),
        steps: List<StepSample> = emptyList(),
        activityAvailable: Boolean = true,
        stepsAvailable: Boolean = true,
    ) = RecordedSession("s", fixes, acts, steps, activityAvailable, stepsAvailable, true)

    private fun act(atS: Int, kind: ActivityKind, c: Confidence) = ActivitySample(t0 + atS * 1000L, kind, c)

    @Test
    fun vehicleDropped() {
        val fixes = walk(0, 300, 1.4)
        val r = Gating.gate(session(fixes, listOf(act(0, ActivityKind.ON_FOOT, Confidence.HIGH), act(100, ActivityKind.VEHICLE, Confidence.HIGH), act(220, ActivityKind.ON_FOOT, Confidence.HIGH)), stepsAvailable = false))
        // fixes with t in [100, 220) governed by vehicle
        val expected = fixes.count { it.timeMs >= t0 + 100_000 && it.timeMs < t0 + 220_000 }
        assertTrue(expected >= 12)
        assertEquals(expected, r.droppedVehicle)
        assertEquals(fixes.size - expected, r.fixes.size)
    }

    @Test
    fun lowConfidenceVehicleKept() {
        val fixes = walk(0, 100, 1.4)
        val r = Gating.gate(session(fixes, listOf(act(0, ActivityKind.VEHICLE, Confidence.LOW)), stepsAvailable = false))
        assertEquals(0, r.droppedVehicle)
        assertEquals(fixes.size, r.fixes.size)
    }

    @Test
    fun staleActivityDoesNotGovern() {
        // vehicle sample at 0, fixes only from 300 s: sample 5 min old
        val fixes = walk(300, 60, 1.4)
        val r = Gating.gate(session(fixes, listOf(act(0, ActivityKind.VEHICLE, Confidence.HIGH)), stepsAvailable = false))
        assertEquals(0, r.droppedVehicle)
        assertEquals(fixes.size, r.fixes.size)
    }

    @Test
    fun stepImplausibleWindowDropped() {
        val fixes = walk(0, 59, 5.0) // 295 m in window 0
        val steps = listOf(StepSample(t0, 0), StepSample(t0 + 60_000, 20))
        val r = Gating.gate(session(fixes, steps = steps, acts = listOf(act(0, ActivityKind.ON_FOOT, Confidence.HIGH))))
        assertEquals(fixes.size, r.droppedSteps)
        assertTrue(r.fixes.isEmpty())
    }

    @Test
    fun walkingStepsKept() {
        val fixes = walk(0, 59, 1.4) // ~83 m
        val steps = listOf(StepSample(t0, 0), StepSample(t0 + 60_000, 110))
        val r = Gating.gate(session(fixes, steps = steps, acts = listOf(act(0, ActivityKind.ON_FOOT, Confidence.HIGH))))
        assertEquals(0, r.droppedSteps)
        assertEquals(fixes.size, r.fixes.size)
    }

    @Test
    fun missingActivityIsUnverifiedNotDropped() {
        val fixes = walk(0, 100, 1.4)
        val r = Gating.gate(session(fixes, activityAvailable = false, stepsAvailable = false))
        assertEquals(fixes.size, r.fixes.size)
        assertFalse(r.verified)
    }

    @Test
    fun noStepDataSkipsStepCheck() {
        val fixes = walk(0, 59, 5.0)
        val r = Gating.gate(session(fixes, stepsAvailable = false, acts = listOf(act(0, ActivityKind.ON_FOOT, Confidence.HIGH))))
        assertEquals(0, r.droppedSteps)
        assertEquals(fixes.size, r.fixes.size)
        // also: available but zero samples
        val r2 = Gating.gate(session(fixes, stepsAvailable = true))
        assertEquals(0, r2.droppedSteps)
    }

    @Test
    fun verifiedOnlyWhenBothSourcesAvailable() {
        val fixes = walk(0, 59, 1.4)
        val steps = listOf(StepSample(t0, 0), StepSample(t0 + 60_000, 110))
        assertTrue(Gating.gate(session(fixes, steps = steps)).verified)
        assertFalse(Gating.gate(session(fixes, steps = steps, activityAvailable = false)).verified)
        assertFalse(Gating.gate(session(fixes, steps = steps, stepsAvailable = false)).verified)
    }
}
