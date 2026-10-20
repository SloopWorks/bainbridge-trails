# MVP status (branch `v1/mvp`, 2026-10-19)

Checklist against the MVP backlog in [`product.md` §8](product.md#8-mvp-backlog) (brief item numbers there).
Verified on the Android emulator (Pixel 9 Pro image, API 37) and the iOS simulator (iPhone 17 Pro) against a
local `wrangler dev` backend; screenshots in [`screens/`](screens/). Not yet tested on a production backend or
with real OAuth clients.

Legend: **done** · **partial** · **not done**.

## P0 — must ship

| # | Item | Status | Notes |
|---|---|---|---|
| 1 | App shell, bottom nav, theme light/dark, dataset loaded off Main | done | `dataset.bin`, warm-up at launch |
| 2 | Onboarding, no early permission asks | done | |
| 3 | Map status colours, tap → preview → detail, locate-me, legend | done | |
| 4 | Map style Topo/Light/Dark + dark mode | done | |
| 5 | Trails list: search, filters, sort | done | |
| 6 | Trail detail: mini map, status, length, gain, park, trailheads, official map, Start walk | done | |
| 7 | Community stats on detail | done | counts < 5 hidden (decisions) |
| 8 | Record restyle + live provisional coverage | done | |
| 9 | Results sheet before/after + island % | done | |
| 10 | Celebration incl. reduced-motion and screen-reader variants | done | |
| 11 | Me: island %, trails x/N, miles, in-progress, history | done | |
| 12 | Leaderboard top 100, your rank, empty/offline states | done | |
| 13 | Sign in (Google Android, Apple iOS, dev debug) | partial | code + backend verification done; real Google/Apple OAuth untested (needs owner's client ids / Apple team) |
| 14 | Display name, anon generator, validation, blocklist | done | |
| 15 | Report name (flags for admin review; auto-hide cut per decisions.md) | done | |
| 16 | Delete account (server + optional local) | done | |
| 17 | Settings: units, attribution, privacy, version | partial | in-app privacy summary only; hosted privacy policy URL missing |
| 18 | Sync up/down, union merge, offline queue | done | verified against local backend only |
| 19 | Known fixes (double "Walk saved", recoverAfterDismiss, memoized stats) | done | |
| 20 | Copy via resources, a11y labels, status not by colour alone | done | count strings use `<plurals>` |

## P1 — should ship

| # | Item | Status |
|---|---|---|
| 1 | Leaderboard entry points on Map and Trails | done |
| 2 | Trailhead Directions links | done |
| 3 | Sync "other account" dialog | not done (P0 fallback: always union) |
| 4 | Admin endpoint for blocklist terms without deploy | not done (blocklist is bundled JSON; admin can hide/reset names) |
| 5 | Raw track retention 30 days | done |
| 6 | Walk history detail view | not done |
| 7 | Hide reported rows for the reporter immediately | not done (reports only flag for admin review) |
| 8 | "Hide me from the leaderboard" toggle | not done (API exposes `hiddenFromBoard`; no UI) |
| 9 | Island 100% celebration art | not done |
| 10 | Debug drawer panels (dataset, coverage tools, recording) | done (Android only) |

P2 items (Google sign-in on iOS, release geofences, groups, photos, …) are not started. The trailhead geofence
prototype exists but is compiled only with `-PgeofencePrototype=true` (Android) / `GEOFENCE_PROTOTYPE` (iOS).

## Known issues

- **Debug drawer Apply & Restart** (external `~/workspace/debugdrawer` repo): the backend choice is saved
  asynchronously and the restart can win the race, so a switch may not stick. Workaround in `app/AGENTS.md`
  (delete `shared_prefs/com.sloopworks.debugdrawer.xml` via `run-as` to return to Prod). Fix belongs upstream.
- **Walks "Unverified" on the emulator:** the emulator has no step counter, so the step heartbeat never
  confirms walking. Expected; real phones verify.
- **R8 / minify is off** for release (`isMinifyEnabled = false`); release is also unsigned (no signing config).
  Enable R8 with keep rules for Ktor/kotlinx-serialization/MapLibre and test before store upload.
- **No debug drawer on iOS:** the drawer is Android-only; iOS uses launch arguments (`-apiBaseUrl`, `-btScreen`,
  `-btDevSignIn`) instead.
- **Real OAuth untested:** Google (Credential Manager) and Apple sign-in have only been exercised up to the
  "not configured" / simulator paths; dev sign-in covers the backend flow.
- **Production API URL is a placeholder** (`ApiConfig.PROD_BASE_URL`); no backend is deployed.
- **iOS is simulator-only:** no signing team or provisioning in the Xcode project.
- **Geofence prototype off by default:** a debug APK declaring `ACCESS_BACKGROUND_LOCATION` was blocked by Play
  Protect on the owner's phone; field data collection with geofences resumes only with the opt-in build flag.
- **Map labels at the screen edge** are clipped like any map (e.g. "Bremerton" under the MapLibre logo on the
  wider iOS framing); the logo now sits on its own pill so this no longer reads as a broken credit.

## Next steps (backlog)

1. Owner setup: Cloudflare deploy + D1 + secrets, Google web/Android OAuth clients, Apple team + Sign in with
   Apple, set `PROD_BASE_URL`, privacy policy URL (see top-level `README.md`).
2. End-to-end on real devices with real OAuth against the deployed backend; field walks (location spike
   field-test plan in `docs/spikes/location/notes.md`).
3. Release hardening: R8 with keep rules, release signing, Play FGS-location declaration + data-safety form,
   App Store privacy labels.
4. P1 gaps: walk history detail, hide-me toggle, reporter-side immediate hide, other-account sync dialog,
   island 100% art, admin blocklist endpoint.
5. Upstream debugdrawer fix (await the save before restart), then drop the AGENTS.md workaround.
6. Decide on geofences for release (Play background-location declaration) or keep them as a debug prototype.
