import SwiftUI
import ComposeApp
#if DEBUG && GEOFENCE_PROTOTYPE
import CoreLocation
#endif

@main
struct iOSApp: App {
    init() {
        #if DEBUG && GEOFENCE_PROTOTYPE
        // TL-10: the Always request exists only in debug builds that opt into the geofence prototype
        // (SWIFT_ACTIVE_COMPILATION_CONDITIONS="DEBUG GEOFENCE_PROTOTYPE"; default off). Without it the Kotlin side
        // has no requester, so the opt-in is hidden; release has no requestAlwaysAuthorization call at all.
        IosGeofences.shared.alwaysRequester = DebugAlwaysAuthorization()
        #endif
        // Opt-in trailhead geofences: install CoreLocation/notification delegates at every launch, including a
        // background relaunch for a region event (no-op unless opted in with Always authorization).
        GeofenceOptIn_iosKt.startTrailheadGeofences()
        // Sign in with Apple lives in Swift (AppleSignIn.swift); debug binaries also offer dev sign-in.
        IosAuth.shared.configure(apple: appleSignIn)
    }

    private let appleSignIn = AppleSignIn()

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

#if DEBUG && GEOFENCE_PROTOTYPE
/// Debug-only geofence prototype (R11), compiled only with the GEOFENCE_PROTOTYPE condition: asks for Always through the Kotlin side's retained manager.
final class DebugAlwaysAuthorization: NSObject, AlwaysAuthorizationRequester {
    func requestAlways(manager: CLLocationManager) {
        manager.requestAlwaysAuthorization()
    }
}
#endif
