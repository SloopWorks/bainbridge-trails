import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
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
