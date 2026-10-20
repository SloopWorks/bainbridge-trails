# Google Play — Data safety form answers

Release build `com.bainbridgetrails.app`, versionCode 1. Source of truth for behavior:
[`docs/legal/privacy-policy.md`](../legal/privacy-policy.md) (served at `https://<host>/privacy`). Verified against
`backend/migrations/0001_init.sql`, `backend/src` (auth, ratelimit, `routes/me.ts` `deleteUser`, `apple.ts`),
`app/androidApp/src/main/AndroidManifest.xml` + the merged release manifest, `data_extraction_rules.xml`,
`NoOpAnalytics` (release) and `GetSignInWithGoogleOption` (Android sign-in). Re-check this file whenever the
backend schema, sign-in, or SDK list changes.

"Collected" in Play's sense = sent off the device to us. Raw GPS tracks, step counts, motion activity and walk
history are processed **only on the device** and are therefore not declared.

## Overview section

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | Covered trail sections, names, ids when signed in. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | HTTPS only. Release has no cleartext config (the cleartext `network_security_config_debug.xml` is in `src/debug` only); Workers serve HTTPS. Map glyphs come from `https://protomaps.github.io`. |
| Do you provide a way for users to request that their data is deleted? | **Yes** | In-app: Me > Account > Delete account. Web: `https://<host>/delete-account`. |
| Account creation | **Users can create an account via OAuth** (Sign in with Google). Optional. | |
| Delete account URL | `https://<host>/delete-account` | Served by the Worker (TL-10). |
| Can users request deletion of some data without deleting the account? | **No** | Only names can be changed/cleared; coverage is deleted with the account. |
| Independent security review | No | |
| UPI / Families / etc. | Not applicable | |

## Data types

Columns: **Coll.** collected · **Shared** with third parties · **Eph.** processed ephemerally · **Req.** required or
optional · purposes. Everything here is collected **only after the user signs in**, which is optional, so every
type is **Optional**.

| Play data type | Coll. | Shared | Eph. | Req. | Purposes | What exactly |
|---|---|---|---|---|---|---|
| Location > **Precise location** | Yes | No | No | Optional | App functionality | Covered trail sections as 5 m tick bitsets per segment (`coverage`), per-trail fractions, completed trails. No coordinates, no timestamps. See note 1. |
| Location > Approximate location | No | — | — | — | — | Not collected separately (precise already declared). |
| Personal info > **Name** | Yes | No | No | Optional | App functionality | User-chosen display name or generated anon name ("Misty Heron 42"), shown on the public leaderboard. See note 2. |
| Personal info > **Email address** | Yes | No | **Yes** | Optional | Account management | The Google ID token carries the email; the server verifies the token, keeps only `sub`, and stores no email. See note 3. |
| Personal info > **User IDs** | Yes | No | No | Optional | Account management, App functionality | Random account UUID (public on the leaderboard), provider + provider subject id (never exposed), hashed session token. |
| Health and fitness > **Fitness info** | Yes | No | No | Optional | App functionality, Fraud prevention, security and compliance | Covered distance totals (`island_m`, `covered_m`) stored; walk durations sent with each sync for the plausibility check, then discarded. |
| App activity > **Other actions** | Yes | No | No | Optional | Fraud prevention, security and compliance | Name reports (reporter id, reported user id, server time) for moderation. |
| App activity > App interactions, search history, installed apps, other UGC | No | | | | | |
| Personal info > address, phone, race, political, sexual orientation, other | No | | | | | |
| Financial info, Health info, Messages, Photos and videos, Audio, Files and docs, Calendar, Contacts | No | | | | | |
| Web browsing | No | | | | | |
| App info and performance (crash logs, diagnostics, other) | No | | | | | No crash reporting or analytics SDK in release (`NoOpAnalytics`). Server-side request logs (Cloudflare) are not app-collected diagnostics; disclosed in the privacy policy anyway. |
| Device or other IDs | No | | | | | No advertising id, Android id or device id. (`deviceId` exists only for debug dev-auth.) |

### Notes

1. **Precise, not approximate (deviation from TL-3 wording, flag to tech lead).** TL-3 says to declare
   "approximate location-derived data". Play defines approximate location as an area of at least 3 km². Covered
   sections resolve to 5 m ticks on named trails, which is much finer, so declaring **Precise location** is the
   defensible answer and under-declaring is a policy-strike risk. The in-app copy ("We sync which trail sections
   you've covered — never your GPS track or walk times") is still accurate and need not change.
2. **Leaderboard visibility is not "sharing".** Play's sharing means transfer to a third party. Showing the name,
   island % and trails completed to other users of the same app is the core feature the user signs in for
   (user-initiated). If Play review pushes back, the conservative alternative is to mark Name as shared for
   "App functionality".
3. **Email (ephemeral).** `GetSignInWithGoogleOption` returns an ID token containing email and name claims. The
   server reads only `sub`/`aud`/`iss`/`exp`/`nonce` (`backend/src/auth.ts`) and stores neither. Play still
   counts data that leaves the device as collected, so declare it with "processed ephemerally". The same applies
   to the name claim in the token: if the form asks, mark **Name** also as ephemeral for the Google token (the
   stored Name above is the display name). Apple sign-in is iOS-only and requests no scopes.
4. **IP addresses.** Not a Play data type. Stored only as `SHA-256(daily salt + IP)` rate-limit keys, salt
   deleted daily, rows within 2 days (daily cron deletes rows whose window, at most 24 h, has ended). Explained in
   the privacy policy.
5. **Third parties that receive data.** Cloudflare (hosting, processor on our behalf: not "sharing" under Play's
   service-provider exemption), Google (sign-in, initiated by the user). No SDKs that send data: MapLibre
   (no telemetry), Ktor, kotlinx, AndroidX, Play services location / activity recognition / Credential Manager
   (on-device APIs; Google's own handling is under Google's policy).
6. **Before sign-in** the app calls only public endpoints (`/v1/leaderboard`, `/v1/trails/stats`) with no
   identifiers, and downloads map glyphs. Nothing to declare there.

## Privacy policy URL

`https://<host>/privacy` (Play Console > App content > Privacy policy, and Store listing). The in-app Settings >
Privacy policy row should open the same URL (STATUS P0-17).
