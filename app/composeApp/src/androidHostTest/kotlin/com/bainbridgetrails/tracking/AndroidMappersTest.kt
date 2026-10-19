package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import com.google.android.gms.location.DetectedActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidMappersTest {

    @Test
    fun activityTypeMapping() {
        assertEquals(ActivityKind.VEHICLE, AndroidMappers.activityKind(DetectedActivity.IN_VEHICLE))
        assertEquals(ActivityKind.BICYCLE, AndroidMappers.activityKind(DetectedActivity.ON_BICYCLE))
        assertEquals(ActivityKind.ON_FOOT, AndroidMappers.activityKind(DetectedActivity.ON_FOOT))
        assertEquals(ActivityKind.ON_FOOT, AndroidMappers.activityKind(DetectedActivity.WALKING))
        assertEquals(ActivityKind.RUNNING, AndroidMappers.activityKind(DetectedActivity.RUNNING))
        assertEquals(ActivityKind.STILL, AndroidMappers.activityKind(DetectedActivity.STILL))
        assertEquals(ActivityKind.UNKNOWN, AndroidMappers.activityKind(DetectedActivity.TILTING))
        assertEquals(ActivityKind.UNKNOWN, AndroidMappers.activityKind(DetectedActivity.UNKNOWN))
        assertEquals(ActivityKind.UNKNOWN, AndroidMappers.activityKind(999))
    }

    @Test
    fun confidenceMapping() {
        assertEquals(Confidence.HIGH, AndroidMappers.confidence(100))
        assertEquals(Confidence.HIGH, AndroidMappers.confidence(75))
        assertEquals(Confidence.MEDIUM, AndroidMappers.confidence(74))
        assertEquals(Confidence.MEDIUM, AndroidMappers.confidence(50))
        assertEquals(Confidence.LOW, AndroidMappers.confidence(49))
        assertEquals(Confidence.LOW, AndroidMappers.confidence(0))
    }

    @Test
    fun elapsedRealtimeMapsToEpoch() {
        val clock = object : DeviceClock {
            override fun currentTimeMillis() = 1_700_000_000_000L
            override fun elapsedRealtimeNanos() = 50_000_000_000L // 50 s since boot
        }
        // Event 3.5 s before "now" on the elapsed clock.
        assertEquals(1_699_999_996_500L, AndroidMappers.elapsedToEpochMs(46_500_000_000L, clock))
        // Event at "now".
        assertEquals(1_700_000_000_000L, AndroidMappers.elapsedToEpochMs(50_000_000_000L, clock))
    }

    private fun loc(
        hasAccuracy: Boolean = true,
        accuracy: Float = 4.5f,
        isMock: Boolean = false,
        fromMockProvider: Boolean = false,
    ) = object : LocationFields {
        override val timeMs = 1_234L
        override val lon = -122.52
        override val lat = 47.62
        override val hasAccuracy = hasAccuracy
        override val accuracyM = accuracy
        override fun isMock(): Boolean = isMock
        override fun isFromMockProvider(): Boolean = fromMockProvider
    }

    @Test
    fun mockFlagMapping() {
        // API 31+: isMock is authoritative; legacy flag ignored.
        assertEquals(true, AndroidMappers.toFix(loc(isMock = true), sdkInt = 31).isMock)
        assertEquals(false, AndroidMappers.toFix(loc(isMock = false, fromMockProvider = true), sdkInt = 34).isMock)
        // Pre-31: isFromMockProvider; isMock must never be read (API absent).
        val legacy = object : LocationFields by loc(fromMockProvider = true) {
            override fun isMock(): Boolean = throw AssertionError("isMock read below API 31")
        }
        assertEquals(true, AndroidMappers.toFix(legacy, sdkInt = 30).isMock)
        val legacyReal = object : LocationFields by loc(fromMockProvider = false) {
            override fun isMock(): Boolean = throw AssertionError("isMock read below API 31")
        }
        assertEquals(false, AndroidMappers.toFix(legacyReal, sdkInt = 26).isMock)
    }

    @Test
    fun fixFieldMapping() {
        assertEquals(Fix(1_234L, -122.52, 47.62, 4.5, false), AndroidMappers.toFix(loc(), sdkInt = 34))
        assertEquals(null, AndroidMappers.toFix(loc(hasAccuracy = false), sdkInt = 34).accuracyM)
    }

    @Test
    fun stepCounterIsSessionRelative() {
        val steps = StepBaseline()
        assertEquals(0L, steps.relative(10_000f))
        assertEquals(25L, steps.relative(10_025f))
        // Counter reset (reboot mid-session) re-baselines rather than going negative.
        assertEquals(25L, steps.relative(3f))
        assertEquals(30L, steps.relative(8f))
        assertFailsWith<IllegalArgumentException> { steps.relative(-1f) }
    }
}
