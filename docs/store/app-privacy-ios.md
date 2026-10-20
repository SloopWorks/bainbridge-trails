# App Store — App Privacy ("nutrition label") answers

Release build, bundle id `com.bainbridgetrails.app` (pbxproj; org-facts expects `com.sloopworks.*`, see owner
TODOs). Release Info.plist: `app/iosApp/iosApp/Info.plist` (Release config uses it; Debug uses `Info-Debug.plist`
and adds `NSLocationAlwaysAndWhenInUseUsageDescription` via build setting, so the **release** build has no Always
key and never calls `requestAlwaysAuthorization`, TL-10). Behavior source: `docs/legal/privacy-policy.md`.

Apple counts data as **collected** when it leaves the device and is kept longer than needed to answer the request
in real time. Raw GPS, CoreMotion activity, pedometer steps and walk history stay on the device: not collected.
Walk durations sent with a sync are used for that request only and discarded: not collected.

## Data Used to Track You

**None.** No tracking, no ad network, no data broker, no IDFA. `NSPrivacyTracking = false`.

## Data Linked to You

All of it is collected only after the optional Sign in with Apple.

| Apple category > type | Purposes | What |
|---|---|---|
| Location > **Precise Location** | App Functionality | Covered trail sections (5 m ticks per segment, no timestamps), per-trail progress, completed trails. Finer than Apple's ~3-decimal coarse threshold, so precise. |
| Identifiers > **User ID** | App Functionality | Random account id, Apple subject id, and the display / anonymous name (a "screen name" in Apple's terms), shown on the leaderboard. |
| Health & Fitness > **Fitness** | App Functionality | Covered distance totals used for island % and leaderboard. Conservative: it is walking data tied to the account. |
| User Content > **Other User Content** | App Functionality | Name reports (reporter, reported user, time) for moderation. |

Optional: Apple refresh token (only when revoke credentials are configured) is an auth artefact, not a data type.

## Data Not Linked to You

None.

## Not collected

Contact Info (no email: Sign in with Apple requests **no scopes**, `requestedScopes = []`; Google sign-in is not
in the iOS build), Health, Financial, Contacts, User Content (photos, audio, messages, gameplay), Browsing,
Search, Usage Data (no analytics), Diagnostics (no crash SDK), Sensitive Info, Purchases, Other Data.
Coarse Location: not separately (precise covers it).

## Permission strings (release Info.plist)

| Key | Text |
|---|---|
| `NSLocationWhenInUseUsageDescription` | Used only while you record a walk; your route stays on your phone. |
| `NSLocationTemporaryUsageDescriptionDictionary.TrailMatching` | Precise location is needed to match your walk to trails. |
| `NSMotionUsageDescription` | Keeps bike and car rides from counting as walks. |
| `UIBackgroundModes` | `location` |

## App Review notes (paste into "Notes")

> Bainbridge Trails records walks the user starts. Location is "When In Use" only. `UIBackgroundModes: location`
> lets an active recording continue with the screen locked (the blue location indicator shows); recording stops
> when the user finishes. The app never requests Always authorization. Raw GPS stays on the device; when signed
> in (Sign in with Apple, optional) the server receives only which trail sections were covered. Account deletion:
> Me > Account > Delete account (also revokes the Apple token). The app works fully without signing in.

## Privacy manifest

`app/iosApp/iosApp/PrivacyInfo.xcprivacy` (in the iosApp target's Resources phase): `NSPrivacyTracking` false, no
tracking domains, collected types = the table above (Precise Location, User ID, Fitness, Other User Content; all
linked, App Functionality, not tracking). Required-reason APIs: UserDefaults `CA92.1` (launch args / app defaults),
File timestamp `C617.1` (kotlinx-io `stat` of files in the app container), System boot time `35F9.1`
(Kotlin/Native and Compose monotonic clocks). Before submission, compare with Xcode's privacy report on an archive
(Product > Archive > Generate Privacy Report) and add any category it lists. MapLibre's manifest ships in its
framework.

## Privacy policy URL

`https://<host>/privacy` (also in `metadata/ios/en-US/privacy_url.txt`).
