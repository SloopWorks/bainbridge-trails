package com.bainbridgetrails.tracking

import com.bainbridgetrails.coverage.Fix
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLActivityTypeFitness
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * CoreLocation, best accuracy, 2 m distance filter, fitness activity type, background updates on (the app declares
 * UIBackgroundModes=location; the blue status-bar indicator shows while backgrounded). Never logs coordinates.
 * Create, start and stop on the main thread: the manager delivers to the run loop it was created on.
 *
 * R5 analogue: authorisation denied/restricted or location services switched off mid-recording calls
 * [onUnavailable] once. Only kCLErrorDenied from didFailWithError is a loss; other errors (location unknown,
 * network, ...) are transient and updates continue.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationSource : LocationSource {
    private var manager: CLLocationManager? = null
    private var delegate: Delegate? = null // CLLocationManager.delegate is weak: keep it alive here

    override fun start(onFix: (Fix) -> Unit, onUnavailable: (reason: String) -> Unit) {
        if (manager != null) return
        val m = CLLocationManager()
        if (!isAuthorized(m.authorizationStatus)) return onUnavailable("location permission missing")
        var lost = false
        fun lose(reason: String) {
            if (lost) return
            lost = true
            onUnavailable(reason)
        }
        val d = Delegate(
            onLocations = { locations -> locations.forEach { onFix(it.toFix()) } },
            onAuthorization = { status ->
                // locationServicesEnabled() is only consulted when the app looks denied (services off reports as denied).
                val suspicious = status == kCLAuthorizationStatusDenied || status == kCLAuthorizationStatusRestricted
                val servicesEnabled = !suspicious || CLLocationManager.locationServicesEnabled()
                IosMappers.authorizationLoss(status, servicesEnabled)?.let(::lose)
            },
            onError = { error ->
                if (IosMappers.isLocationErrorLoss(error.code)) {
                    lose(IosMappers.authorizationLoss(m.authorizationStatus, CLLocationManager.locationServicesEnabled()) ?: "location permission revoked")
                }
            },
        )
        m.delegate = d
        m.activityType = CLActivityTypeFitness
        m.desiredAccuracy = kCLLocationAccuracyBest
        m.distanceFilter = 2.0
        m.pausesLocationUpdatesAutomatically = false
        m.allowsBackgroundLocationUpdates = true
        m.showsBackgroundLocationIndicator = true
        manager = m
        delegate = d
        m.startUpdatingLocation()
    }

    override fun stop() {
        val m = manager ?: return
        m.stopUpdatingLocation()
        m.delegate = null
        manager = null
        delegate = null
    }

    private fun CLLocation.toFix(): Fix {
        val (lat, lon) = coordinate.useContents { latitude to longitude }
        return IosMappers.toFix(
            timeMs = IosMappers.epochMs(timestamp),
            lon = lon,
            lat = lat,
            horizontalAccuracy = horizontalAccuracy,
            simulatedBySoftware = sourceInformation?.isSimulatedBySoftware,
        )
    }

    private class Delegate(
        val onLocations: (List<CLLocation>) -> Unit,
        val onAuthorization: (Int) -> Unit,
        val onError: (NSError) -> Unit,
    ) : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            onLocations(didUpdateLocations.filterIsInstance<CLLocation>())
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            onAuthorization(manager.authorizationStatus)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            onError(didFailWithError)
        }
    }

    companion object {
        fun isAuthorized(status: Int): Boolean =
            status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways
    }
}
