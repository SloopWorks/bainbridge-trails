# Google Play — permissions and declarations

Release manifest: `app/androidApp/src/main/AndroidManifest.xml`. The merged release manifest (last local
`processReleaseManifest`, versionCode 1, minSdk 26, targetSdk 37) adds only library permissions:
`ACCESS_NETWORK_STATE`, `USE_BIOMETRIC` / `USE_FINGERPRINT` (androidx.credentials / Play services auth, used by the
system sign-in sheet) and the AndroidX `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Re-check the merged manifest
before each upload: `app/androidApp/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`.

| Permission | Release | Runtime prompt | Play declaration | Use |
|---|---|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | yes | no | none | Sync, leaderboard, community stats, map glyphs. |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | yes | yes, with in-app rationale first (`perm_location_*`) | none (foreground only) | Record walks; show "you are here". Precise required (`perm_precise_*`): trails run metres apart. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | yes | no | **Foreground service declaration (location)**, with video | `RecordingService`, `foregroundServiceType="location"`, started by the user's Start walk. |
| `POST_NOTIFICATIONS` | yes | yes (API 33+), rationale `perm_notif_*` | none | The ongoing recording notification with **Stop**; "Walk saved" notice. |
| `ACTIVITY_RECOGNITION` (+ GMS `ACTIVITY_RECOGNITION` ≤ API 28) | yes | yes, optional, rationale `perm_activity_*` with "Continue without" | none | Step counter + activity transitions drop bike/car rides; without it walks are "Unverified". |
| `ACCESS_BACKGROUND_LOCATION`, `RECEIVE_BOOT_COMPLETED` | **no** | — | **not needed** | Only in debug builds made with `-PgeofencePrototype=true` (`src/geofencePrototype/AndroidManifest.xml`). |

**Background location is not requested in release.** Recording continues while the screen is off because the
user started a visible foreground service, which Android treats as while-in-use access. Do **not** fill in the
Play "Location permissions" (background) declaration. If geofences ever ship, follow the steps in
`docs/spikes/location/notes.md` (Store review) first.

## Foreground service declaration (Play Console > App content > Foreground service permissions)

Foreground service type: **Location** (`FOREGROUND_SERVICE_LOCATION`).

Suggested answers (closest Play task type: user-initiated location tracking for a fitness / walk-recording task):

> **Feature description.** Bainbridge Trails records a walk that the user starts by tapping "Start walk". While
> recording, the app uses GPS to match the walk to island trails and fill in the trail sections the user covered.
> A foreground service of type location keeps recording while the phone is locked or in a pocket. A persistent
> notification ("Recording your walk") is shown the whole time and has a Stop button; the user can also finish
> from the app. The service stops when the user stops or finishes the walk.
>
> **Why it must run now and can't be deferred or interrupted.** The walk happens in real time. If the service were
> deferred or killed, the GPS positions for that part of the walk would be lost and the sections walked would not
> be counted. WorkManager or a later job can't capture a live route.
>
> **User impact if the system stops it.** The user loses the rest of the walk they are recording.
>
> **Background location.** Not requested. The app only accesses location while the user is recording (foreground
> service started from a visible activity) or while the map is open.

### Video (≤ 30 s, unlisted YouTube link). Shot list

Record on a real phone with a release (or release-like, R8 on) build, screen recording with touches shown:

1. Launch app → Map tab (2 s).
2. Open a trail → tap **Start walk** (3 s).
3. In-app rationale "Location while you record" → **Allow location** → system dialog → **While using the app**
   (and Precise on) (5 s).
4. Notification rationale → **Allow** → system dialog (3 s).
5. Recording screen visible with live distance (3 s).
6. Press Home / lock the phone; pull down the shade: **"Recording your walk"** notification with **Stop** (5 s).
7. Tap **Stop** in the notification → reopen the app → results sheet; notification is gone (6 s).
8. Optional caption overlay: "Location is used only while recording a walk the user started."

Field-test tip: use `scripts/emu-route.sh` on an emulator with notification shade visible if a real walk is
impractical, but Play prefers a real device.

## Activity recognition

No Play declaration is required. Store listing and in-app rationale already explain it ("Helps keep bike and car
rides from counting"). Activity data stays on the device.

## Notifications

No declaration. Android 13+ runtime prompt is shown just before the first recording, after `perm_notif_*`.

## Other App content forms

| Form | Answer |
|---|---|
| Ads | No ads. |
| App access | All features available without special access. Sign-in is optional and works with any Google account; the leaderboard and community stats are visible signed out. Provide no credentials. |
| Target audience | 13–15, 16–17, 18+ (not designed for children; no Families program). See `content-rating.md`. |
| Data safety | `play-data-safety.md` |
| Account deletion | In-app + `https://<host>/delete-account` |
| Health apps declaration (if shown) | Activity and fitness: records walks (distance, trails); not a medical app; no Health Connect. |
| Government / financial / news / COVID | No |
| Privacy policy | `https://<host>/privacy` |
