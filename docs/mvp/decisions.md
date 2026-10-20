# MVP tech-lead decisions (spec review round 1)

Date: 2026-10-19. Resolves the adversarial review of `product.md` and `ux.md`.

**Precedence:** this file > `ux.md` (visuals, layout, interaction) > `product.md` (behavior, copy, math) > the brief.
Where `product.md` and `ux.md` disagree on behavior and this file is silent, `product.md` wins; on visuals, `ux.md` wins.

## P0
- **TL-1 Map status rendering.** Status, not-counted, selected-halo and dim layers move out of the style
  templates (`scripts/build-map-styles.py`) into Compose `LineLayer`s declared over
  `getBaseSource("trails")` (source-layer `segments`), one layer per status bucket with
  `filter = id in literal(segmentIds)`. Changing coverage or selection only changes layer params; the base
  style never reloads. Segments are colored by their **own** covered fraction (0.75 / 0.85 thresholds).
  No feature-state, no tile rebuild.
- **TL-2 Sync carries walk durations.** Client queues one upload per finished walk and one backfill on
  first sign-in: `PUT /v1/coverage { datasetVersion, tickM, segments, walks: [{ id, durationS }] }`.
  `id` is a random UUID per walk (no timestamps). Server dedups walk ids and checks
  `newCoveredM <= 4.0 m/s × Σ durationS(new walks) + 500 m`. The backfill upload carries every local walk.
  Reject → `422 implausible`; client keeps local progress, shows `sync_rejected_notice`.
- **TL-3 Privacy copy.** `onboarding_3_body` and all privacy text: "We sync which trail sections you've
  covered — never your GPS track or walk times." Play Data Safety declares approximate location-derived
  data (covered trail sections) as collected, linked to account, not shared.

## P1
- **TL-4 Contradictions:**
  - Leaderboard ranks are **unique** (product §7 order, user id last).
  - Several completions → **one** celebration screen with plural variant (D15).
  - Finish → **confirmation dialog** (UX).
  - Notification **Stop** finalises silently (existing behavior); results open on next app open.
  - Crash recovery → **Count it / Discard** dialog (existing UI).
  - Anon names generated **on device** with Shuffle; server validates only. Names are not unique; no
    "Name is taken" error.
  - Community stats are **public** (visible signed out, when online).
  - Community counts below **5** are hidden ("Be one of the first to finish this trail").
  - Delete product.md's Theme notes; `ux.md` tokens rule. "You" row uses primary, not status colors.
  - Map style: radio **Auto / Topo / Light / Dark** (Auto = Topo light, Dark in system dark mode).
  - Trailhead names come from `trailheads.json` (32 named entries); D21 is void.
  - AC F2-1 replaced: each segment colors by its own covered fraction.
- **TL-5 Round down** to display precision everywhere (client and server `islandPct`). Never show 100%
  before Complete or 85% before Complete.
- **TL-6 Named non-counted trails** (6 fully, 8 partly) are tappable, listed, can be completed, drawn with
  status colors in a dashed pattern, and their detail says "Doesn't count toward your island %".
- **TL-7 Sticky completion.** Locally persist `completedTrailIds` (union-only); server keeps a sticky
  per-user completed set. Trails completed = |sticky set ∪ currently ≥ threshold|. Sync sends
  `datasetVersion`; server computes with its bundled dataset version.
- **TL-8 Live coverage while recording**: recompute over the trailing 10 min window merged into the
  pre-walk state, every 30 s, only while the Record screen is visible and app foreground. Never in the
  background. Final numbers come from `Finalizer` as today.
- **TL-9 Names**: Latin script only (`[A-Za-z0-9 À-ÖØ-öø-ÿ'._-]`), ≥2 letters, 3–24 chars. Check allowlist
  first, then severe tier (substring after leetspeak folding), then mild tier (whole word). Drop anon
  words that can exceed 24 chars ("Wandering Thimbleberry 99").
- **TL-10 Store**: release iOS build removes `NSLocationAlwaysAndWhenInUseUsageDescription` and the
  `requestAlwaysAuthorization` path (debug-only source set / build setting). Account deletion revokes the
  Apple token when Apple credentials are configured (no-op otherwise). Backend serves a static
  `GET /delete-account` web page explaining in-app deletion and an email fallback (Play requirement).
- **TL-11 Inference resistance**: leaderboard and community stats for others come from a snapshot
  rebuilt by a daily Cron Trigger (plus on deploy); the caller's own row is live.

## P2 / cuts
- Lengths under 0.1 mi show in feet (or metres); no "~" prefix. `agency_only_length_mi` ignored in MVP.
- "Trail miles walked" is labelled "on this phone" (local history only).
- No iOS process-death state restore requirement.
- Leaderboard entry points from Map/Trails: P1 (Me is P0).
- Partway map line gets a dark casing like Nearly done (non-text contrast).
- **Cut from MVP:** admin blocklist endpoint (list is a file in `backend/`), 3-report auto-hide (reports
  just flag for admin), other-account dialog, legend launch counting, coach mark, off-island snackbar,
  elevation tiers + loss tile, live leaderboard-row preview, 7-day sign-in prompt throttle.
