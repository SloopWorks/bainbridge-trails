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
        // vehicle sample at 0, fixes only from 610 s: sample older than the 10 min cap
        val fixes = walk(610, 60, 1.4)
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

    private fun govern(kind: ActivityKind, c: Confidence): GateResult =
        Gating.gate(session(walk(0, 100, 1.4), listOf(act(0, kind, c)), stepsAvailable = false))

    @Test
    fun bicycleHighDropped() {
        val r = govern(ActivityKind.BICYCLE, Confidence.HIGH)
        assertEquals(11, r.droppedVehicle)
        assertTrue(r.fixes.isEmpty())
    }

    @Test
    fun vehicleMediumDropped() = assertEquals(11, govern(ActivityKind.VEHICLE, Confidence.MEDIUM).droppedVehicle)

    @Test
    fun bicycleMediumDropped() = assertEquals(11, govern(ActivityKind.BICYCLE, Confidence.MEDIUM).droppedVehicle)

    @Test
    fun lowConfidenceKept() {
        assertEquals(0, govern(ActivityKind.VEHICLE, Confidence.LOW).droppedVehicle)
        assertEquals(0, govern(ActivityKind.BICYCLE, Confidence.LOW).droppedVehicle)
    }

    @Test
    fun stepCounterResetIsNoData() {
        val fixes = walk(0, 59, 5.0)
        val steps = listOf(StepSample(t0, 5000), StepSample(t0 + 30_000, 10), StepSample(t0 + 60_000, 20))
        val r = Gating.gate(session(fixes, steps = steps))
        assertEquals(0, r.droppedSteps)
        assertEquals(fixes.size, r.fixes.size)
    }
    @Test
    fun vehicleSampleThenTenMinutesSilenceDropped() {
        // iOS reports transitions only: one VEHICLE sample, then nothing for 10 min of driving.
        val fixes = walk(0, 600, 12.0)
        val r = Gating.gate(session(fixes, listOf(act(0, ActivityKind.VEHICLE, Confidence.HIGH)), stepsAvailable = false))
        assertEquals(fixes.size, r.droppedVehicle)
        assertTrue(r.fixes.isEmpty())
    }

    @Test
    fun bicycleGovernsUntilNextSample() {
        val fixes = walk(0, 500, 3.5)
        val acts = listOf(act(0, ActivityKind.BICYCLE, Confidence.MEDIUM), act(400, ActivityKind.ON_FOOT, Confidence.HIGH))
        val r = Gating.gate(session(fixes, acts, stepsAvailable = false))
        val bike = fixes.count { it.timeMs < t0 + 400_000 }
        assertTrue(bike > 12, "longer than the old 120 s window")
        assertEquals(bike, r.droppedVehicle)
        assertEquals(fixes.size - bike, r.fixes.size)
    }

    @Test
    fun movingWithNoStepSamplesAfterLastIsDropped() {
        // Android reports steps only on change: after the last sample the count is unchanged.
        val fixes = walk(0, 59, 5.0) // 295 m
        val steps = listOf(StepSample(t0, 0), StepSample(t0 + 5_000, 3))
        val r = Gating.gate(session(fixes, steps = steps))
        assertEquals(fixes.size, r.droppedSteps)
        assertTrue(r.fixes.isEmpty())
    }

    @Test
    fun allRideSessionZeroStepsDropped() {
        // A slow ride (~14 km/h) the OS never labelled: one step sample at start, no activity samples.
        val fixes = walk(0, 599, 3.8) // ten full 60 s windows
        val oneSample = Gating.gate(session(fixes, steps = listOf(StepSample(t0, 0))))
        assertEquals(fixes.size, oneSample.droppedSteps)
        assertTrue(oneSample.fixes.isEmpty())
        // Same ride with the 30 s heartbeat holding the unchanged count.
        val heartbeat = (0..600 step 30).map { StepSample(t0 + it * 1000L, 0) }
        val r = Gating.gate(session(fixes, steps = heartbeat))
        assertEquals(fixes.size, r.droppedSteps)
    }

    @Test
    fun walkBeforeFirstStepSampleKept() {
        // No sample at or before the window start: no step evidence either way, so nothing is dropped.
        val fixes = walk(0, 59, 5.0)
        val r = Gating.gate(session(fixes, steps = listOf(StepSample(t0 + 30_000, 0))))
        assertEquals(0, r.droppedSteps)
    }
}
