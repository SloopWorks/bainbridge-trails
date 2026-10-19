# Spike 4: location + activity verification (notes)

Branch `spike4/location-activity`. Build published to channel `dev-spike4-location-activity`: build id PENDING_BUILD_ID.

## Architecture
- `:tracking` (common): `SessionStore` (append-only JSONL, `<dir>/sessions/<id>.jsonl`, one flushed line per event), `Gating` (activity + step checks), `Finalizer` (gate, then coverage), `RecordingController` (state machine), `Geofences`, `Diagnostics`.
- Platform recorders are the only writers (R4): `RecordingService` (Android FGS, type `location`) and `IosRecorder` push events into one channel; a single coroutine appends then emits on `RecordingBus`. The controller never writes a live session.
- Every 5 min the recorder writes a `diag` event (battery %, fixes and mean accuracy since the last diag). It does not affect gating or coverage.
- Crash recovery: an unfinished session (no `stop`) is offered for finishing on next launch (verified, Android 6.2%).
- Raw tracks stay on the phone: `noBackupFilesDir/tracking` (Android), Application Support/tracking (iOS). Nothing is logged with coordinates.

## Permission matrix
| | Asked | When |
|---|---|---|
| Android | FINE + COARSE location | first Start |
| | POST_NOTIFICATIONS (33+) | first Start (FGS notification) |
| | ACTIVITY_RECOGNITION (+ `com.google.android.gms.permission.ACTIVITY_RECOGNITION`, API <= 28) | first Start; optional, denial = unverified walk |
| | ACCESS_BACKGROUND_LOCATION | **debug manifest only** (R2), geofence opt-in |
| iOS | When-in-use location | first Start |
| | Motion and Fitness | first Start; optional |
| | Always | only in the optional geofence opt-in |

## Store review
- **Play.** FGS `location` type: justified as a user-started, visible walk recording (ongoing notification with Stop). Background location is declared in the debug manifest only, so release has no geofence surface (receivers and `RECEIVE_BOOT_COMPLETED` are debug-only too). To ship geofences: (1) move the permission, receivers and boot receiver to the main manifest; (2) complete the Play Console background-location declaration; (3) record a video showing the in-app prominent disclosure and the permission flow; (4) justify the core feature (trailhead arrival prompt) and why foreground-only is insufficient; (5) keep the opt-in disclosure text current.
- **App Store.** When-in-use plus `UIBackgroundModes: location` (walk recording with the screen off). Always only for the opt-in trailhead feature, with `NSLocationAlwaysAndWhenInUseUsageDescription` stating the purpose. Motion string explains bike/car filtering.

## Gating rules and limits
- Activity: a fix is dropped if the latest activity sample (<= 120 s old) is VEHICLE or BICYCLE with confidence >= MEDIUM. Labels lag, so a short vehicle stretch can pass; a road walk beside a trail is gated only if the OS labels it.
- Steps: per 60 s window, if movement > 60 m but steps x 2.5 m cannot cover it, the window is dropped. Skipped when steps are absent (some devices, or permission denied) or outside the sample range.
- Missing sources never drop fixes; the walk is then "Unverified". Mock fixes are always dropped.
- Step counts restart at 0 on resume; gating treats a decrease as a no-data window.

## Observed behaviour (emulator / simulator)
- Android emulator (Pixel_9_Pro clone): Gazzam Lake Main Trail 0% -> **11.8%** ([results](screens/android-results.png), [recording](screens/android-recording.png)). FGS survived a 60 s screen lock. After force-stop, recovery finished the walk at **6.2%** ([recover](screens/android-recover.png), [results](screens/android-recover-results.png)). Permission flows: [rationale](screens/android-permission-rationale.png), [activity](screens/android-permission-activity.png), [denied](screens/android-permission-denied-settings.png), [needs location](screens/android-needs-location.png).
- iOS simulator: `simctl` fixes are flagged simulated, so default result is 0% (all dropped as mock; [mock-filtered](screens/ios-results-mock-filtered.png)). With debug-only `-acceptSimulatedLocations`: Gazzam **8.5%** ([results](screens/ios-results.png), [recording](screens/ios-recording.png), [background](screens/ios-background.png)). The simulator has no CoreMotion, so Unverified.
- Geofences, Android: opt-in worked ([disclosure](screens/android-geofence-disclosure.png), [allow all the time](screens/android-geofence-allow-all-the-time.png)); **1 of 6** entries fired, ~2 min 15 s late (GMS stationary throttling), notification in [android-geofence.png](screens/android-geofence.png). iOS: region entry logged ~3 s after the inside fix; banner not shown because the notification alert needs a tap ([prompt](screens/ios-geofence-notification-prompt.png)). Accepted gap (R10).

## Open questions for v1
- Live provisional % during the walk (today: results only at Finish).
- Raw-track retention: propose keep 30 days after finalise, then delete (coverage store keeps the result).
- `Dataset.parse` takes 5-8.6 s on a debug emulator; measure on release/device, consider a binary format.
- R8 stale-fix filter compares `Location.time` to the wall clock; add a few seconds of skew tolerance.
- Steps restart at 0 on resume.
- iOS needs a device: CoreMotion history, pedometer, geofence banner, the blue background indicator, taps through the opt-in UI.
- Android geofence latency on a real device; fences do not alert if already inside at opt-in.

## Owner field test (Pixel, dev build `com.bainbridgetrails.app.dev`)
Install the dev-channel build. Grant all prompts. Record each as its own session.
1. **60 min walk, screen off**, Gazzam or Grand Forest. Start, lock phone, pocket, Finish at the end.
2. **5 min car ride** with recording on (drive, then Finish). Expect vehicle fixes dropped.
3. **Walk along a road beside a trail** (about 10 min). Note whether road fixes credit the trail.
4. **Trailhead arrival with geofence on**: enable it in Record > Settings, leave 500 m+ away, drive or walk in. Note latency from arrival to notification.
5. **Pull the session files** (debuggable build):
   ```
   adb shell run-as com.bainbridgetrails.app.dev ls no_backup/tracking/sessions
   adb shell run-as com.bainbridgetrails.app.dev cat no_backup/tracking/sessions/<id>.jsonl > /tmp/<id>.jsonl
   python3 scripts/session-summary.py /tmp/<id>.jsonl
   rm /tmp/<id>.jsonl   # raw tracks are private; delete from the laptop afterwards
   ```
   The summary prints duration, fix count, battery %/h, activity mix and dropped counts, never coordinates.
6. **Report**: battery %/h per walk (from the `diag` events; first to last reading), fix count, activity mix, dropped counts, and the geofence latency.
