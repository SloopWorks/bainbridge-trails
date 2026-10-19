package com.bainbridgetrails.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bainbridgetrails.map.excludeFromBackup
import com.bainbridgetrails.tracking.IosActivitySource
import com.bainbridgetrails.tracking.IosLocationSource
import com.bainbridgetrails.tracking.IosRecorder
import com.bainbridgetrails.tracking.Perm
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMAuthorizationStatusNotDetermined
import platform.CoreMotion.CMMotionActivityManager
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dateByAddingTimeInterval
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject

/** iOS has no Context; a process-wide singleton stands in. */
actual abstract class PlatformContext private constructor() {
    companion object INSTANCE : PlatformContext()
}

@Composable
actual fun rememberPlatformContext(): PlatformContext = PlatformContext.INSTANCE

/**
 * iOS permission model: a system prompt is shown once per app install; after a refusal only Settings can grant it,
 * so denied/restricted is [PermStatus.PERMANENTLY_DENIED] and not-yet-asked is [PermStatus.DENIED].
 * NOTIFICATIONS is not needed on iOS (the blue location indicator shows a background recording): always granted.
 * ACTIVITY on hardware without motion activity (the simulator) is PERMANENTLY_DENIED, so the flow never offers a
 * prompt that cannot appear, and the walk is recorded as Unverified.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosPermissionRequester : PermissionRequester {
    private val locationManager by lazy { CLLocationManager() }
    private var locationDelegate: AuthorizationDelegate? = null

    override fun status(perm: Perm): PermStatus = when (perm) {
        Perm.LOCATION_WHILE_IN_USE -> {
            val s = locationManager.authorizationStatus
            when {
                IosLocationSource.isAuthorized(s) -> PermStatus.GRANTED
                s == kCLAuthorizationStatusNotDetermined -> PermStatus.DENIED
                else -> PermStatus.PERMANENTLY_DENIED
            }
        }
        Perm.NOTIFICATIONS -> PermStatus.GRANTED
        Perm.ACTIVITY -> when {
            !CMMotionActivityManager.isActivityAvailable() -> PermStatus.PERMANENTLY_DENIED
            else -> when (CMMotionActivityManager.authorizationStatus()) {
                CMAuthorizationStatusAuthorized -> PermStatus.GRANTED
                CMAuthorizationStatusNotDetermined -> PermStatus.DENIED
                else -> PermStatus.PERMANENTLY_DENIED
            }
        }
    }

    override suspend fun request(perm: Perm): PermStatus {
        val current = status(perm)
        if (current != PermStatus.DENIED) return current // granted, or only Settings can change it
        when (perm) {
            Perm.LOCATION_WHILE_IN_USE -> {
                val answered = CompletableDeferred<Unit>()
                val d = AuthorizationDelegate { status -> if (status != kCLAuthorizationStatusNotDetermined) answered.complete(Unit) }
                locationDelegate = d // the manager's delegate is weak
                locationManager.delegate = d
                locationManager.requestWhenInUseAuthorization() // when-in-use only; Always is a Task 7 opt-in
                answered.await()
                locationManager.delegate = null
                locationDelegate = null
            }
            Perm.ACTIVITY -> {
                // CoreMotion has no explicit request: the first query shows the prompt and answers after it.
                val answered = CompletableDeferred<Unit>()
                val now = NSDate()
                CMMotionActivityManager().queryActivityStartingFromDate(
                    now.dateByAddingTimeInterval(-60.0),
                    toDate = now,
                    toQueue = NSOperationQueue.mainQueue,
                ) { _, _ -> answered.complete(Unit) }
                answered.await()
            }
            Perm.NOTIFICATIONS -> Unit
        }
        return status(perm)
    }

    private class AuthorizationDelegate(val onChange: (Int) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = onChange(manager.authorizationStatus)
    }
}

@Composable
actual fun rememberPermissionRequester(): PermissionRequester = remember { IosPermissionRequester }

actual fun startRecordingService(context: PlatformContext, sessionId: String, resume: Boolean) =
    IosRecorder.start(trackingDir(context), sessionId, resume)

actual fun stopRecordingService(context: PlatformContext) = IosRecorder.stop()

/** Application Support/tracking, created on first use and excluded from iCloud/device backup (raw tracks are private). */
@OptIn(ExperimentalForeignApi::class)
actual fun trackingDir(context: PlatformContext): String {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    val dir = "$base/tracking"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    excludeFromBackup(dir)
    return dir
}

actual fun openAppSettings(context: PlatformContext) {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}
