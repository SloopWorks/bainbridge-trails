# Spike 4: location + activity verification (notes)

Branch `spike4/location-activity`. Dev build published to channel `dev-spike4-location-activity`: build id 01a15444-1b88-7993-a72d-37b3f0bbc71c (built from e60558d, clean tree; expires 2026-10-21T13:05Z).

## Architecture
- `:tracking` (common): `SessionStore` (append-only JSONL, `<dir>/sessions/<id>.jsonl`, one flushed line per event), `Gating` (activity + step checks), `Finalizer` (gate, then coverage), `RecordingController` (state machine), `Geofences`, `Diagnostics`.
- Platform recorders are the only writers (R4): `RecordingService` (Android FGS, type `location`) and `IosRecorder` push events into one channel; a single coroutine appends then emits on `RecordingBus`. The controller never writes a live session.
- Every 5 min the recorder writes a `diag` event (battery %, fixes and mean accuracy since the last diag). It does not affect gating or coverage. Verified at runtime on the Android emulator (below); iOS diag is unverified at runtime (compiled only).
- Crash recovery: an unfinished session (no `stop`) is offered for finishing on next launch (verified, Android 6.2%).
- Every Stop is finalised. A Stop the controller did not request (the notification's **Stop** action, or the service stopping itself) finalises the current session and shows the results. On launch, sessions that have a `stop` but are not in `coverage.json`'s `verified` map (process death while finalising, a stop the UI never saw, the 10 s stop-timeout path) are finalised automatically (merging is idempotent); the most recent summary is shown, with "N walks saved" when there were several. A `stop` line that lost its trailing newline in a crash counts as finished (the scan repairs the newline). Verified on the emulator: notification Stop -> results ([android-notification-stop.png](screens/android-notification-stop.png)), see below.
- Raw tracks stay on the phone: `noBackupFilesDir/tracking` (Android), Application Support/tracking (iOS). Nothing is logged with coordinates.

## Permission matrix
| | Asked | When |
|---|---|---|
| Android | FINE + COARSE location | first Start |
| | POST_NOTIFICATIONS (33+) | first Start (FGS notification) |
| | ACTIVITY_RECOGNITION (+ `com.google.android.gms.permission.ACTIVITY_RECOGNITION`, API <= 28) | first Start; optional, denial = unverified walk |
| | ACCESS_BACKGROUND_LOCATION | **debug manifest only** (R2), geofence opt-in |
| | Precise (FINE) required | approximate-only shows "Precise location is off — needed to match trails" and asks for precise once; if the user keeps approximate, "Open settings" |
| iOS | When-in-use location | first Start |
| | Temporary full accuracy (`TrailMatching` purpose key) | at Start, only if the user turned Precise off; still reduced = recorded but flagged ("Approximate location" chip, warning in results) |
| | Motion and Fitness | first Start; optional |
| | Always | only in the optional geofence opt-in, **debug builds only** (R11) |

## Store review
- **Play.** FGS `location` type: justified as a user-started, visible walk recording (ongoing notification with Stop). Background location is declared in the debug manifest only, so release has no geofence surface (receivers and `RECEIVE_BOOT_COMPLETED` are debug-only too). Targeting SDK 34+, the FGS `location` type also needs the Play Console foreground-service declaration (with a video), and background location needs a privacy policy. To ship geofences: (1) move the permission, receivers and boot receiver to the main manifest; (2) complete the Play Console background-location declaration; (3) record a video showing the in-app prominent disclosure and the permission flow; (4) justify the core feature (trailhead arrival prompt) and why foreground-only is insufficient; (5) keep the opt-in disclosure text current.
- **App Store.** When-in-use plus `UIBackgroundModes: location` (walk recording with the screen off). Motion string explains bike/car filtering. Geofences are debug-only on iOS too (ruling R11, mirroring Android R2): `IosGeofences.supported` requires `Platform.isDebugBinary`, so release builds show no toggle, never request Always and never monitor regions. The `NSLocationAlwaysAndWhenInUseUsageDescription` key stays in Info.plist (harmless without a request); shipping geofences means dropping the debug guard and justifying Always for the opt-in trailhead feature in review.

## Gating rules and limits
- Activity: a fix is dropped if the latest activity sample at or before it is VEHICLE or BICYCLE with confidence >= MEDIUM. A sample governs until the next one, capped at **10 min** (was 120 s): iOS reports transitions only, so one VEHICLE/BICYCLE sample may be all there is for a whole ride. Labels lag, so a short vehicle stretch can pass; a road walk beside a trail is gated only if the OS labels it.
- Steps: per 60 s window, if movement > 60 m but steps x 2.5 m cannot cover it, the window is dropped. Cumulative steps at the window edges are interpolated between the nearest samples and **held after the last sample** (no new steps). Skipped when steps are unavailable (some devices, or permission denied), when no step sample exists at or before the window start, or when the counter reset inside the window.
- Zero-step / transition fix: Android's step counter reports only on change, so a ride produced no step samples and the window was "outside the sample range" (skipped); with transition-only iOS activity a slow bike ride was credited. Now the recorder writes a step **heartbeat** every 30 s through the single writer (Android: last known count; iOS: `CMPedometer` query from session start, also at Stop, authoritative across suspension), and gating holds the last count, so a ride with no steps is dropped.
- Missing sources never drop fixes; the walk is then "Unverified". Mock fixes are always dropped.
- Step counts restart at 0 on resume; gating treats a decrease as a no-data window.

## Observed behaviour (emulator / simulator)
- Android emulator (Pixel_9_Pro clone): Gazzam Lake Main Trail 0% -> **11.8%** ([results](screens/android-results.png), [recording](screens/android-recording.png)). FGS survived a 60 s screen lock. After force-stop, recovery finished the walk at **6.2%** ([recover](screens/android-recover.png), [results](screens/android-recover-results.png)). Permission flows: [rationale](screens/android-permission-rationale.png), [activity](screens/android-permission-activity.png), [denied](screens/android-permission-denied-settings.png), [needs location](screens/android-needs-location.png).
- iOS simulator: `simctl` fixes are flagged simulated, so default result is 0% (all dropped as mock; [mock-filtered](screens/ios-results-mock-filtered.png)). With debug-only `-acceptSimulatedLocations`: Gazzam **8.5%** ([results](screens/ios-results.png), [recording](screens/ios-recording.png), [background](screens/ios-background.png)). The simulator has no CoreMotion, so Unverified.
- Geofences, Android: opt-in worked ([disclosure](screens/android-geofence-disclosure.png), [allow all the time](screens/android-geofence-allow-all-the-time.png)); **1 of 6** entries fired, ~2 min 15 s late (GMS stationary throttling), notification in [android-geofence.png](screens/android-geofence.png). iOS: region entry logged ~3 s after the inside fix; banner not shown because the notification alert needs a tap ([prompt](screens/ios-geofence-notification-prompt.png)). Accepted gap (R10).

- Notification Stop on Android emulator (Pixel_9_Pro_bt4): started a recording in the app, replayed Gazzam for ~2 min (`scripts/emu-route.sh trail-gazzam-lake-main-trail 2 130`), pressed Home, expanded the shade (`cmd statusbar expand-notifications`), tapped the notification's **Stop** (bounds from `uiautomator dump`), reopened the app: results sheet shown (Gazzam Lake Main Trail 0% -> 8.4%, Peter's Trail 0% -> 4.0%; [android-notification-stop.png](screens/android-notification-stop.png)). The session file ends with `stop` and its id is in `coverage.json`'s `verified` map. The emulator has no step counter, so the step heartbeat was not exercised there (unit-tested only).

- Diag on Android emulator (Pixel_9_Pro_bt4, ~5.5 min replay): one line appeared at the 5 min mark, read via `run-as`: `{"t":"diag","timeMs":...,"batteryPct":100,"fixCount":142,"meanAccuracyM":4.64}` (emulator battery is constant 100%).

## Open questions for v1
- iOS diag (UIDevice battery) unverified at runtime.
- Live provisional % during the walk (today: results only at Finish).
- Raw-track retention: propose keep 30 days after finalise, then delete (coverage store keeps the result).
- `Dataset.parse` takes 5-8.6 s on a debug emulator; measure on release/device, consider a binary format.
- R8 stale-fix filter compares `Location.time` to the wall clock; add a few seconds of skew tolerance.
- Steps restart at 0 on resume.
- The step heartbeat asserts "unchanged" every 30 s. If a step counter delivers late (sensor batching with the screen off) or the iOS pedometer query fails while live updates are withheld, a genuine walking window could be dropped; check the field-test step samples for this.
- A session finalised by the stop-timeout path (before its Stop was written) is merged again on the next launch and its summary shown a second time.
- iOS needs a device: CoreMotion history, pedometer, geofence banner, the blue background indicator, taps through the opt-in UI.
- Android geofence latency on a real device; fences do not alert if already inside at opt-in.

## Owner field test (Pixel, dev build `com.bainbridgetrails.app.dev`)
Install the dev-channel build before 2026-10-21T13:05Z (it expires after 48 h); otherwise republish from the branch: `scripts/sync-app-assets.sh`, `cd app && ./gradlew -PshipyardDeployPluginRepository=file://$HOME/.m2/repository :androidApp:assembleDebug`, then `shipyard-deploy publish androidApp/build/outputs/apk/debug/androidApp-debug.apk` (with `SHIPYARD_DEPLOY_SERVER` and `SHIPYARD_DEPLOY_TOKEN` set). Grant all prompts. Record each as its own session.
1. **60 min walk, screen off**, Gazzam or Grand Forest. Start, lock phone, pocket, Finish at the end.
2. **Slow bike ride** (under 14 km/h, at least 10 min, ending the session while still riding): the real activity-gating test. Expect bike fixes dropped (BICYCLE activity and/or too few steps).
3. **5 min car ride** with recording on (drive, then Finish). This mostly exercises the coverage speed filter (`maxSpeedMps` 4 m/s = 14.4 km/h drops faster fixes regardless of activity); vehicle labels are a bonus. A slow bike ride stays under that filter, which is why step 2 is the real gating test.
4. **Walk along a road beside a trail** (about 10 min). Note whether road fixes credit the trail.
5. **Trailhead arrival with geofence on**: enable it in Record > Settings, leave 500 m+ away, drive or walk in. Note latency from arrival to notification.
6. **Pull the session files** (debuggable build):
   ```
   adb shell run-as com.bainbridgetrails.app.dev ls no_backup/tracking/sessions
   adb shell run-as com.bainbridgetrails.app.dev cat no_backup/tracking/sessions/<id>.jsonl > /tmp/<id>.jsonl
   python3 scripts/session-summary.py /tmp/<id>.jsonl
   rm /tmp/<id>.jsonl   # raw tracks are private; delete from the laptop afterwards
   ```
   The summary prints duration, fix count, battery %/h, activity mix and dropped counts, never coordinates.
7. **Report**: battery %/h per walk (from the `diag` events; first to last reading), fix count, activity mix, dropped counts, and the geofence latency.
