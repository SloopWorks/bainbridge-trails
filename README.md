# Bainbridge Trails

Walk every public trail on Bainbridge Island. The app maps the island's trails, matches recorded walks to the
trail sections you cover, and tracks completion per trail and for the whole island, with an optional leaderboard.

| Path | What |
|---|---|
| `app/` | Kotlin Multiplatform app (Compose Multiplatform UI, Android + iOS). See `app/README.md`, `app/AGENTS.md`. |
| `backend/` | Cloudflare Worker + D1 API (auth, coverage sync, leaderboard, moderation). See `backend/README.md`. |
| `pipeline/`, `data/` | Trail data pipeline (sources → canonical trails → PMTiles / dataset in `data/dist/`). |
| `scripts/` | Asset sync, tile/terrain builds, emulator/simulator route replay, session summaries. |
| `docs/` | Product/UX specs and decisions (`docs/mvp/`), spikes, research. |
| `data/roads/`, `pipeline/src/bitrails/roads/` | Second dataset for a **roads version** ("walk/bike every road"): 264.3 km / 164.2 mi of counted public road. Data only, no app work. See [`docs/roads/plan.md`](docs/roads/plan.md), [`docs/roads/data-spec.md`](docs/roads/data-spec.md). |

## MVP status

P0 scope implemented on branch `v1/mvp` (some P1 items open), verified on the Android emulator (API 37) and the iOS simulator against
a local backend. Detailed checklist, known issues and backlog: [`docs/mvp/STATUS.md`](docs/mvp/STATUS.md).

**Works today:** onboarding; offline island map (Topo/Light/Dark) with per-trail status colours, preview and
Trail detail (community stats, directions); Trails list with search/filters/sort; walk recording (foreground service with
a Stop notification), live provisional coverage, results and celebration; Me stats
and walk history; sign in (Google on Android, Apple on iOS, dev sign-in in debug), display names with generator,
validation and reporting; leaderboard; delete account; settings (units, map style, attribution, privacy); coverage
sync with an offline queue. Debug builds add the SloopWorks debug drawer on Android (backend switch, logs, data
and coverage tools).

### Run it

```sh
scripts/sync-app-assets.sh                         # copy map data + dataset from data/dist/ into the app
cd backend && npm install && cp .dev.vars.example .dev.vars && npm run dev   # local API on :8787
cd app && ./gradlew :androidApp:installDebug       # Android (debug id com.bainbridgetrails.app.dev)
xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' build   # iOS simulator
```

Point a debug build at the local backend: Android emulator via the debug drawer > Backend > Local
(`10.0.2.2:8787`; Android 17 asks for the local-network permission); iOS simulator by launching with
`-apiBaseUrl http://localhost:8787`. Build flags, tests and device notes: `app/AGENTS.md`.

### What the owner must supply before release

1. **Google sign-in (Android):** a Google Cloud OAuth *web* client id → build with `-PgoogleWebClientId=…`
   and list it in the backend's `GOOGLE_CLIENT_IDS`; an Android OAuth client for the release signing key.
2. **Sign in with Apple (iOS):** an Apple Developer team, the Sign in with Apple capability on the bundle id
   (`com.bainbridgetrails.app`, entitlement already in the project), signing team in Xcode; bundle id in the
   backend's `APPLE_CLIENT_IDS` (optional `APPLE_TEAM_ID` / `APPLE_KEY_ID` / `APPLE_PRIVATE_KEY` for revocation).
3. **Backend:** a Cloudflare account; create the D1 database, apply migrations, set `ADMIN_TOKEN` and the vars
   above, deploy — step by step in [`backend/README.md`](backend/README.md#deploy-owner-only--agents-never-deploy).
   **Done (temporary):** deployed 2026-10-20 to `https://bainbridge-trails-api.patjackson52.workers.dev` (D1 `bainbridge-trails`, migrations
   applied, `ADMIN_TOKEN` set; local copy in `~/.config/bainbridge-trails/admin-token`). `ApiConfig.PROD_BASE_URL` and
   the iOS metadata URLs point there. Before publishing: move to a custom domain and update both.
4. **Privacy policy URL** (hosted page; the in-app Privacy screen is a summary) and the account-deletion URL
   (`GET /delete-account` on the Worker, `SUPPORT_EMAIL` var) for the store listings.
5. **Store listings:** Play Console app + release signing key, foreground-service (location) declaration with
   video, data-safety form; App Store Connect app, privacy nutrition labels, screenshots and copy.
