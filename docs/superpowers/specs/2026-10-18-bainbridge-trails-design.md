# Bainbridge Island Trails — Design

Date: 2026-10-18
Status: Draft, awaiting owner approval

## 1. Intent

A mobile app (iOS + Android) that maps every public trail on Bainbridge Island, WA, and lets people track how much of each trail — and of the island as a whole — they have walked. The goal it serves: **cover all public trail ground on the island on foot.**

The flagship feature is a **public leaderboard**: each user's percentage of all island trail miles walked, shown under a chosen display name or an anonymous name.

Why it's special: no existing app does per-trail, cumulative, multi-session coverage tracking for a small, bounded trail network, with a community board around it.

## 2. Core experience

### On the trail
- Phone GPS records the walk, including in the background with the screen off.
- The app recognizes which trail(s) the user is on. Where trails overlap or share segments, all matching trails are shown.
- Each trail has a status, color-coded on the map: **Not started / Partway / Nearly done / Complete**.
- Optional notification on arriving at a trailhead ("You're at Grand Forest East — 3 of 5 trails here complete").
- When a trail crosses the completion threshold: a full-screen celebration (accessible: screen-reader text, reduced-motion variant), showing the trail, the user's new island %, and community stats for that trail.

### Completion rules (owner decisions)
- A trail is **complete** when a "reasonable percentage" of its length has been covered on foot. Starting value ~90%; final threshold set by Spike 3.
- Coverage **accumulates across sessions**. A long trail can be finished over many outings, in any order or direction.
- Partial progress is shown as a percentage per trail.
- Walking speed only (builder's call: reject segments above a walking/running speed threshold so bike/car travel doesn't count).

### Stats
- Personal: island % of miles, trails completed, miles walked on trails, per-trail %.
- Community aggregates: "N users have completed this trail", average/median coverage, total users.

### Leaderboard (flagship, v1)
- **One public list** for all users. Groups/friends/family are explicitly **not v1**.
- Rank by % of total island trail miles covered (builder's call). Tiebreak: trails completed. Board shows both.
- Each entry shows display name or anonymous name, %, trails completed.

### Accounts & identity
- Sign in with Apple + Sign in with Google (builder's call; Apple sign-in is required by App Store rules when Google is offered).
- User picks a public display name, or uses an auto-generated anonymous name (e.g. "Mossy Heron 42"). Can switch any time.
- Tracking works offline and signed out; sign-in is needed only to appear on the leaderboard and sync.

## 3. Trail data (first-class deliverable)

Collect **all public trails** on Bainbridge Island with: name, managing agency, length/mileage, geometry (coordinates), elevation profile/topography, surface/difficulty if available, trailhead locations, official map links, allowed uses, accessibility notes.

### Sources to check (all, then reconcile)
- Bainbridge Island Metro Park & Recreation District (parks + trail maps, GIS if published)
- Bainbridge Island Land Trust (preserves, trail maps)
- City of Bainbridge Island (GIS / open data, Sound to Olympics trail, non-motorized plan)
- Kitsap County GIS open data
- OpenStreetMap (Overpass API) — likely best geometry baseline; ODbL license
- WA DNR LiDAR portal / USGS 3DEP — elevation, hillshade, contours
- Washington State Parks (Fay Bainbridge, Fort Ward)
- Community sources (e.g. Bainbridge trail guides, AllTrails/Trailforks for cross-checking names/mileage only — not for copying geometry; licensing forbids)

**Public map data comes first** (owner). Owner may later record test walks and help with field checks; agency permission requests are not on the critical path.

Every source's **license** is recorded. Only use data whose license allows redistribution in an app; attribution shown in-app.

### Scope: capture everything, then classify (owner decision)
Capture **all available trails and paths** from every source — do not pre-filter. Each segment gets classification attributes, and the app decides what counts by filtering on them, so scope can be adjusted later without re-collecting data:
- `category`: e.g. agency trail, preserve trail, multi-use path (e.g. Sound to Olympics), road-shoulder/sidewalk connector, beach/street-end access, easement-over-private-land, informal (OSM-only), seasonal/closed
- `manager`, `sources[]` (which sources list it), `confidence`, `public_access` (yes/permit/unknown), `allowed_uses`
- An `included_in_island_total` rule set in config, not baked into data. Initial default (builder's call): agency/preserve trails + multi-use paths + named connectors count; road shoulders, street ends, informal and closed paths are shown but don't count. Owner reviews the classified list from the data spike and can change the rule.

### Format
- Canonical store: **GeoJSON in the repo** (versioned, human-diffable), one feature per trail segment, with **stable, add-only segment IDs** (a segment is never renumbered; splits/changes create new IDs and keep a mapping from old ones), and trails defined as ordered lists of segment IDs (so shared segments count once toward island %, and toward each trail that uses them).
- Distribution: build step produces **vector tiles (PMTiles)** + a compact trail-metadata JSON. Client styles them at runtime, so map look can change without re-processing data.
- Elevation: contours + hillshade derived from LiDAR, packaged as separate tile layers.
- Data pipeline is a reproducible script (fetch → normalize → reconcile → validate → build tiles). A report lists conflicts between sources (names, lengths, missing trails) for human review.

### "Island total"
Island % denominator = total length of unique public trail segments in the dataset. Changes to the dataset or to the inclusion rule must not silently drop users' completion. Because segment IDs are stable and add-only, and the server stores per-segment coverage, progress survives phone switches, reinstalls and dataset updates without needing raw tracks.

## 4. Maps & visual design
- Vector map rendering with **MapLibre** (native SDKs on both platforms; free, no API key fees).
- Stylish, polished, distinctive look; multiple map styles explored by the design agent (e.g. topo/contour, minimal light, dark, satellite-free). Trail status colors work across all styles and for color-blind users.
- Basemap tiles: self-hosted PMTiles of the island area (Protomaps/OpenMapTiles-style extract) to avoid per-request fees.
- Offline: island is small; whole map + trail data should be downloadable for offline use (builder's call, strongly recommended — tree cover and poor signal are common).

## 5. Technical constraints (owner decisions)
- **Kotlin Multiplatform**, sharing code across iOS and Android.
- **Compose Multiplatform** for shared UI on both platforms. Platform-native code only where required (map view, location services, sign-in), wrapped behind shared interfaces.
- **$0/month hosting for now** (owner). No paid tiers.

### Builder's calls (proposed defaults)
- Backend: **Cloudflare Workers + D1 (SQLite) free tier** — no project pausing (Supabase free tier pauses after ~1 week idle, ruled out). Worker verifies Apple/Google ID tokens and issues its own session tokens; D1 holds users, per-segment coverage, leaderboard.
- Static data (PMTiles, trail JSON): Cloudflare R2 free tier (no egress fees), GitHub Pages as fallback.
- Shared modules: domain (trails, coverage math), data (local DB via SQLDelight, sync), GPS map-matching, UI.
- Coverage computed **on device** from GPS tracks; server receives per-segment coverage summaries. Leaderboard computed server-side from those summaries.

### Cheat protection (owner: basic now, complex later)
v1: on-device speed/plausibility filtering (drop points/segments above walking-running speed, reject large GPS jumps, ignore mock-location-flagged points on Android); server sanity limits (coverage can't grow faster than plausible on-foot distance per time window; reject impossible jumps); admin can remove a leaderboard entry. Later: Play Integrity / App Attest, track evidence.

### Privacy
- Raw GPS tracks stay on device by default. Server stores per-segment coverage only (optionally encrypted track backup later).
- Leaderboard never shows locations, routes, or times of day.
- Account deletion removes all server data (also required by store policy).

### Store readiness (owner: prepare for Play Store)
- v1 is built to pass **Google Play** review: background-location declaration + justification video plan, privacy policy, data-safety form answers, in-app account deletion. App Store readiness follows the same pattern (Always-location justification, Sign in with Apple).
- Display-name moderation for a public board: profanity/blocklist filter on names, "report name" action, admin removal. Anonymous names come from a curated word list.
- **App name, store title, descriptions and listing text live in one editable config/metadata file** (e.g. fastlane-style `metadata/`), so they can be changed without code changes. For now use the builder's recommended names and descriptions (working title: "Bainbridge Trails").

## 6. Delivery approach — spikes first

Spikes are throwaway builds that answer questions and inform the plan for the rest. Order:

1. **Data spike** — scrape/collect all sources, reconcile, produce first canonical GeoJSON + conflict report + license table. Output: trail list with mileage, gap analysis.
2. **Map render spike** — KMP + Compose Multiplatform app shell showing island basemap + trail layers via MapLibre on iOS and Android, with 2–3 style variants. Output: screenshots on both platforms, style recommendation.
3. **Coverage spike** — map-match recorded/simulated GPS tracks to segments, compute per-trail %, pick buffer width and completion threshold. Output: algorithm + parameters, tested on real tracks where possible.
4. **Background location spike** — iOS + Android background tracking, battery cost, permission flows, trailhead geofence notifications.
5. **Backend spike** — Cloudflare Worker + D1: Apple/Google token verification, coverage sync, leaderboard query, sanity limits; confirm free-tier limits.

After spikes: full implementation plan for v1.

## 7. Team (agent roles)
- **Tech lead** — coordinates, owns architecture and plan, integrates spike results.
- **Geography & maps expert** — data sources, reconciliation, licensing, elevation, tile pipeline.
- **Mobile design expert** — UI, map styles, celebration screen, leaderboard, accessibility.
- **Mobile APIs expert** — location/background, notifications, sign-in, MapLibre interop, KMP platform glue.
- **Review agent** — adversarial review of specs, plans, and branches.
- **Verification** — run and screenshot on iOS simulator and Android emulator; nothing claimed done without both.

## 8. Goals / non-goals / anti-goals

**Goals (v1):** complete, classified trail map; Play-Store-ready build; GPS tracking with cumulative per-trail coverage; completion celebration; personal stats; per-trail community stats; public leaderboard with name/anon choice; iOS + Android from shared KMP code; ~$0/month hosting.

**Non-goals (v1):** groups/friends/family boards; social feed; photos; routing/turn-by-turn; trails outside Bainbridge Island; bikes/horses as completion modes; web app; monetization.

**Anti-goals (failures even if it works):**
- Missing or wrong trails — the dataset must be trustworthy.
- Credit given without actually walking (driving past, GPS spoofing obvious cases).
- Exposing where/when users walk.
- Hosting bills that grow with users.
- Battery drain that makes people stop tracking.
- A map that looks generic.

## 9. Left to the builder
Exact module layout, DI, local DB choice, map-matching algorithm, buffer width, completion threshold (~90% start), dataset-version migration, offline download UX, anon name generator, analytics (none by default), CI setup, app IDs/bundle names.
