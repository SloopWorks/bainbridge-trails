package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLErrorDenied
import platform.CoreMotion.CMMotionActivityConfidenceHigh
import platform.CoreMotion.CMMotionActivityConfidenceMedium
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** Pure CoreLocation/CoreMotion -> session model mapping (no managers, no callbacks), unit-tested in iosTest. */
object IosMappers {
    /**
     * CMMotionActivity sets several flags at once (e.g. automotive + stationary at a red light). The most specific
     * mode of travel wins: vehicle > bicycle > running > walking > stationary.
     */
    fun activityKind(automotive: Boolean, cycling: Boolean, running: Boolean, walking: Boolean, stationary: Boolean): ActivityKind = when {
        automotive -> ActivityKind.VEHICLE
        cycling -> ActivityKind.BICYCLE
        running -> ActivityKind.RUNNING
        walking -> ActivityKind.ON_FOOT
        stationary -> ActivityKind.STILL
        else -> ActivityKind.UNKNOWN
    }

    /** CMMotionActivityConfidence raw value; anything unrecognised is LOW (least trust). */
    fun confidence(raw: Long): Confidence = when (raw) {
        CMMotionActivityConfidenceHigh -> Confidence.HIGH
        CMMotionActivityConfidenceMedium -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    /** `CLLocation.sourceInformation?.isSimulatedBySoftware == true`; no source information means not mock. */
    fun isMock(simulatedBySoftware: Boolean?): Boolean = simulatedBySoftware == true

    fun epochMs(date: NSDate): Long = epochMs(date.timeIntervalSince1970)

    /** NSDate seconds since 1970 -> epoch ms, truncated. The one place this happens. */
    fun epochMs(secondsSince1970: Double): Long = (secondsSince1970 * 1000.0).toLong()

    /** A negative [horizontalAccuracy] (invalid fix) passes through; filterFixes drops it. */
    fun toFix(timeMs: Long, lon: Double, lat: Double, horizontalAccuracy: Double, simulatedBySoftware: Boolean?): Fix =
        Fix(timeMs = timeMs, lon = lon, lat = lat, accuracyM = horizontalAccuracy, isMock = isMock(simulatedBySoftware))

    /** Ruling R8: CLLocationManager may first deliver a cached location from before the session; it is not part of the walk. */
    fun isSessionFix(fixTimeMs: Long, sessionStartMs: Long): Boolean = fixTimeMs >= sessionStartMs

    /**
     * R5 analogue: the lost reason when authorisation or system location services went away mid-recording, else null.
     * notDetermined is not a loss (the recorder only starts after a grant; a reset is followed by a real status).
     */
    fun authorizationLoss(status: CLAuthorizationStatus, servicesEnabled: Boolean): String? = when {
        !servicesEnabled -> "location turned off"
        status == kCLAuthorizationStatusDenied || status == kCLAuthorizationStatusRestricted -> "location permission revoked"
        else -> null
    }

    /**
     * Only kCLErrorDenied (authorisation or location services gone; the authorisation callback reports the same) is a
     * loss. Everything else from didFailWithError (kCLErrorLocationUnknown, kCLErrorNetwork, ...) is transient.
     */
    fun isLocationErrorLoss(code: Long): Boolean = code == kCLErrorDenied

    /**
     * CMMotionActivity history queried at Stop fills gaps while live updates were suspended (backgrounded).
     * Keeps samples inside [fromMs, toMs] whose time no live sample already has, one per time, in time order.
     */
    fun newHistorySamples(history: List<ActivitySample>, seenTimesMs: Set<Long>, fromMs: Long, toMs: Long): List<ActivitySample> {
        val seen = seenTimesMs.toMutableSet()
        return history.sortedBy { it.timeMs }.filter { it.timeMs in fromMs..toMs && seen.add(it.timeMs) }
    }
}
