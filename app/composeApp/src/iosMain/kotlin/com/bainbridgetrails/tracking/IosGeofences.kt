package com.bainbridgetrails.tracking

import com.bainbridgetrails.record.PlatformContext
import com.bainbridgetrails.record.RecordingGraph
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLCircularRegion
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLRegion
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * Opt-in trailhead geofences on iOS (Task 7 prototype): `CLCircularRegion` monitoring for the [Geofences.IOS_MAX]
 * nearest trailheads (the system limit is 20 per app), re-selected on significant-location-change, with a local
 * notification on region entry. Always authorization is requested only from the opt-in toggle, through this
 * object's retained [manager]. The notification only opens the app; it never starts a recording.
 *
 * Call on the main thread. [startIfOptedIn] runs at every launch (including background relaunches for a region
 * event, via iOSApp.init) so the delegate is in place before CoreLocation delivers the event. Never logs coordinates.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosGeofences {
    private const val KEY_OPTED_IN = "geofencesOptedIn"
    private const val ID_PREFIX = "th-"

    /** Fallback centre for the first selection before any fix: roughly the middle of Bainbridge Island. */
    private const val ISLAND_LON = -122.53
    private const val ISLAND_LAT = 47.63

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val manager by lazy { CLLocationManager() }
    private val delegate = Delegate()
    private val notificationDelegate = NotificationDelegate()
    private var authWaiter: CompletableDeferred<Unit>? = null

    val supported: Boolean
        get() = CLCircularRegion.`class`()?.let { CLLocationManager.isMonitoringAvailableForClass(it) } == true

    fun optedIn(): Boolean = NSUserDefaults.standardUserDefaults.boolForKey(KEY_OPTED_IN)

    private fun setOptedIn(value: Boolean) = NSUserDefaults.standardUserDefaults.setBool(value, KEY_OPTED_IN)

    fun alwaysGranted(): Boolean = manager.authorizationStatus == kCLAuthorizationStatusAuthorizedAlways

    /**
     * Always authorization through the retained manager, then notification authorization. iOS shows the Always
     * prompt at most once; when it does not appear (already answered), the app never resigns active, so after a
     * short grace period the current status is returned instead of waiting.
     */
    suspend fun requestAlways(): Boolean {
        val s = manager.authorizationStatus
        if (s != kCLAuthorizationStatusAuthorizedAlways &&
            (s == kCLAuthorizationStatusNotDetermined || s == kCLAuthorizationStatusAuthorizedWhenInUse)
        ) {
            val prompted = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            val center = NSNotificationCenter.defaultCenter
            val resign = center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { _: NSNotification? ->
                prompted.complete(Unit)
            }
            val active = center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _: NSNotification? ->
                returned.complete(Unit)
            }
            val answered = CompletableDeferred<Unit>().also { authWaiter = it }
            try {
                manager.delegate = delegate
                manager.requestAlwaysAuthorization()
                if (withTimeoutOrNull(1_500) { prompted.await() } != null) {
                    // A system prompt is up: wait for the answer (or the app coming back without one).
                    withTimeoutOrNull(120_000) {
                        kotlinx.coroutines.selects.select<Unit> {
                            answered.onAwait { }
                            returned.onAwait { }
                        }
                    }
                }
            } finally {
                center.removeObserver(resign)
                center.removeObserver(active)
                authWaiter = null
            }
        }
        if (alwaysGranted()) requestNotifications()
        return alwaysGranted()
    }

    private suspend fun requestNotifications(): Boolean = suspendCancellableCoroutine { cont ->
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
                scope.launch { if (cont.isActive) cont.resume(granted) }
            }
    }

    fun enable() {
        setOptedIn(true)
        startIfOptedIn()
    }

    fun disable() {
        setOptedIn(false)
        manager.stopMonitoringSignificantLocationChanges()
        ourRegions().forEach { manager.stopMonitoringForRegion(it) }
        println("Geofences: monitoring stopped")
    }

    /** Idempotent: installs delegates and (re)selects regions when opted in with Always authorization. */
    fun startIfOptedIn() {
        UNUserNotificationCenter.currentNotificationCenter().delegate = notificationDelegate
        if (!supported || !optedIn()) return
        manager.delegate = delegate
        if (!alwaysGranted()) return
        manager.startMonitoringSignificantLocationChanges()
        reselect(manager.location)
    }

    private fun ourRegions(): List<CLRegion> =
        manager.monitoredRegions.mapNotNull { it as? CLRegion }.filter { it.identifier.startsWith(ID_PREFIX) }

    /** Monitors the [Geofences.IOS_MAX] trailheads nearest [location] (island centre before the first fix). */
    private fun reselect(location: CLLocation?) {
        val (lon, lat) = location?.coordinate?.useContents { longitude to latitude } ?: (ISLAND_LON to ISLAND_LAT)
        scope.launch {
            val all = runCatching { RecordingGraph.trailheads().await() }.getOrElse {
                println("Geofences: trailheads failed to load (${it::class.simpleName})")
                return@launch
            }
            if (!optedIn() || !alwaysGranted()) return@launch
            val chosen = Geofences.nearest(all, lon, lat, Geofences.IOS_MAX)
            val wanted = chosen.map { it.id }.toSet()
            val current = ourRegions()
            current.filter { it.identifier !in wanted }.forEach { manager.stopMonitoringForRegion(it) }
            val have = current.map { it.identifier }.toSet()
            chosen.filter { it.id !in have }.forEach { th ->
                val region = CLCircularRegion(center = CLLocationCoordinate2DMake(th.lat, th.lon), radius = Geofences.RADIUS_M, identifier = th.id)
                region.setNotifyOnEntry(true)
                region.setNotifyOnExit(false)
                manager.startMonitoringForRegion(region)
            }
            println("Geofences: monitoring ${chosen.size} trailhead regions")
        }
    }

    private fun onEnter(trailheadId: String) {
        val app = UIApplication.sharedApplication
        // A background relaunch for a region event gets ~10 s; ask for a little more to load the dataset.
        var task = UIBackgroundTaskInvalid
        task = app.beginBackgroundTaskWithExpirationHandler { app.endBackgroundTask(task) }
        scope.launch {
            try {
                val text = RecordingGraph.arrivalText(PlatformContext.INSTANCE, trailheadId) ?: return@launch
                val content = UNMutableNotificationContent().apply {
                    setTitle("Trailhead nearby")
                    setBody(text)
                    setSound(UNNotificationSound.defaultSound)
                }
                val request = UNNotificationRequest.requestWithIdentifier(trailheadId, content, null)
                UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error ->
                    if (error != null) println("Geofences: notification failed (${error.code})")
                }
                println("Geofences: arrival notification posted")
            } catch (e: Exception) {
                println("Geofences: arrival failed (${e::class.simpleName})")
            } finally {
                if (task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
            }
        }
    }

    private class Delegate : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (manager.authorizationStatus != kCLAuthorizationStatusNotDetermined) authWaiter?.complete(Unit)
            if (optedIn() && alwaysGranted()) startIfOptedIn()
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            (didUpdateLocations.lastOrNull() as? CLLocation)?.let { reselect(it) }
        }

        override fun locationManager(manager: CLLocationManager, didEnterRegion: CLRegion) {
            if (didEnterRegion.identifier.startsWith(ID_PREFIX)) onEnter(didEnterRegion.identifier)
        }

        override fun locationManager(manager: CLLocationManager, monitoringDidFailForRegion: CLRegion?, withError: NSError) {
            println("Geofences: monitoring failed (${withError.code})")
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            println("Geofences: location error (${didFailWithError.code})")
        }
    }

    /** Shows arrival banners while the app is in the foreground too; a tap just opens the app (Record screen). */
    private class NotificationDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound)
        }

        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            withCompletionHandler()
        }
    }
}
