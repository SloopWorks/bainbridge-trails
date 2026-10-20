package com.bainbridgetrails.tracking

import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorHeadingFailure
import platform.CoreLocation.kCLErrorNetwork
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreMotion.CMMotionActivityConfidenceHigh
import platform.CoreMotion.CMMotionActivityConfidenceLow
import platform.CoreMotion.CMMotionActivityConfidenceMedium
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosMappersTest {
    private fun kind(
        automotive: Boolean = false,
        cycling: Boolean = false,
        running: Boolean = false,
        walking: Boolean = false,
        stationary: Boolean = false,
    ) = IosMappers.activityKind(automotive, cycling, running, walking, stationary)

    @Test
    fun motionMapping() {
        assertEquals(ActivityKind.VEHICLE, kind(automotive = true))
        assertEquals(ActivityKind.BICYCLE, kind(cycling = true))
        assertEquals(ActivityKind.RUNNING, kind(running = true))
        assertEquals(ActivityKind.ON_FOOT, kind(walking = true))
        assertEquals(ActivityKind.STILL, kind(stationary = true))
        assertEquals(ActivityKind.UNKNOWN, kind())
        // Stopped at a light: CoreMotion reports automotive + stationary; the vehicle wins.
        assertEquals(ActivityKind.VEHICLE, kind(automotive = true, stationary = true))
        assertEquals(ActivityKind.BICYCLE, kind(cycling = true, stationary = true))
        assertEquals(ActivityKind.ON_FOOT, kind(walking = true, stationary = true))
    }

    @Test
    fun confidenceMapping() {
        assertEquals(Confidence.LOW, IosMappers.confidence(CMMotionActivityConfidenceLow))
        assertEquals(Confidence.MEDIUM, IosMappers.confidence(CMMotionActivityConfidenceMedium))
        assertEquals(Confidence.HIGH, IosMappers.confidence(CMMotionActivityConfidenceHigh))
        assertEquals(Confidence.LOW, IosMappers.confidence(99L)) // unknown future value: least trust
    }

    @Test
    fun simulatedSourceIsMock() {
        assertTrue(IosMappers.isMock(simulatedBySoftware = true))
        assertFalse(IosMappers.isMock(simulatedBySoftware = false))
        assertFalse(IosMappers.isMock(simulatedBySoftware = null)) // no sourceInformation (iOS < 15 or unknown)
    }

    @Test
    fun nsdateToEpochMs() {
        assertEquals(0L, IosMappers.epochMs(NSDate.dateWithTimeIntervalSince1970(0.0)))
        assertEquals(1_760_000_000_123L, IosMappers.epochMs(NSDate.dateWithTimeIntervalSince1970(1_760_000_000.123)))
        assertEquals(1_760_000_000_999L, IosMappers.epochMs(1_760_000_000.9994)) // truncates, like Android's ms clock
    }

    @Test
    fun fixMappingKeepsNegativeAccuracy() {
        val fix = IosMappers.toFix(timeMs = 5, lon = -122.5, lat = 47.6, horizontalAccuracy = -1.0, simulatedBySoftware = null)
        assertEquals(-1.0, fix.accuracyM) // invalid: passes through, filterFixes drops it
        assertEquals(-122.5, fix.lon)
        assertEquals(47.6, fix.lat)
        assertFalse(fix.isMock)
    }

    @Test
    fun staleFixesBeforeSessionStartAreDropped() {
        assertFalse(IosMappers.isSessionFix(fixTimeMs = 6_999, sessionStartMs = 10_000))
        assertTrue(IosMappers.isSessionFix(fixTimeMs = 7_000, sessionStartMs = 10_000)) // clock skew tolerance
        assertTrue(IosMappers.isSessionFix(fixTimeMs = 1_000, sessionStartMs = 1_000))
    }

    @Test
    fun authorizationLoss() {
        assertNull(IosMappers.authorizationLoss(kCLAuthorizationStatusAuthorizedWhenInUse, servicesEnabled = true))
        assertNull(IosMappers.authorizationLoss(kCLAuthorizationStatusAuthorizedAlways, servicesEnabled = true))
        assertNull(IosMappers.authorizationLoss(kCLAuthorizationStatusNotDetermined, servicesEnabled = true))
        assertEquals("location permission revoked", IosMappers.authorizationLoss(kCLAuthorizationStatusDenied, servicesEnabled = true))
        assertEquals("location permission revoked", IosMappers.authorizationLoss(kCLAuthorizationStatusRestricted, servicesEnabled = true))
        assertEquals("location turned off", IosMappers.authorizationLoss(kCLAuthorizationStatusDenied, servicesEnabled = false))
        assertEquals("location turned off", IosMappers.authorizationLoss(kCLAuthorizationStatusAuthorizedWhenInUse, servicesEnabled = false))
    }

    @Test
    fun onlyDeniedLocationErrorIsALoss() {
        assertFalse(IosMappers.isLocationErrorLoss(kCLErrorLocationUnknown))
        assertFalse(IosMappers.isLocationErrorLoss(kCLErrorNetwork))
        assertFalse(IosMappers.isLocationErrorLoss(kCLErrorHeadingFailure))
        assertTrue(IosMappers.isLocationErrorLoss(kCLErrorDenied))
    }

    @Test
    fun historyIsDedupedByTimeAndClampedToSession() {
        val live = ActivitySample(2_000, ActivityKind.ON_FOOT, Confidence.HIGH)
        val history = listOf(
            ActivitySample(500, ActivityKind.STILL, Confidence.HIGH), // before session start
            ActivitySample(3_000, ActivityKind.VEHICLE, Confidence.HIGH),
            live.copy(confidence = Confidence.LOW), // same time as a live sample: live one wins
            ActivitySample(3_000, ActivityKind.VEHICLE, Confidence.HIGH), // duplicate inside history
            ActivitySample(9_000, ActivityKind.ON_FOOT, Confidence.HIGH), // after stop
        )
        assertEquals(
            listOf(ActivitySample(3_000, ActivityKind.VEHICLE, Confidence.HIGH)),
            IosMappers.newHistorySamples(history, seenTimesMs = setOf(2_000L), fromMs = 1_000, toMs = 5_000),
        )
    }

    @Test
    fun onlyFullAccuracyIsPrecise() {
        assertTrue(IosMappers.isPreciseAccuracy(CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy))
        assertFalse(IosMappers.isPreciseAccuracy(CLAccuracyAuthorization.CLAccuracyAuthorizationReducedAccuracy))
    }
}
