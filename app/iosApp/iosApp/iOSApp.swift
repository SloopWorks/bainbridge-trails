import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        // Opt-in trailhead geofences: install CoreLocation/notification delegates at every launch, including a
        // background relaunch for a region event (no-op unless opted in with Always authorization).
        GeofenceOptIn_iosKt.startTrailheadGeofences()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
