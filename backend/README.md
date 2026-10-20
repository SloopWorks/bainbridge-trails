# Bainbridge Trails backend

Cloudflare Worker (TypeScript) + D1 (SQLite). Runs on the Cloudflare **free tier**: one Worker, one D1
database, Cache API. No paid features (no Durable Objects, Queues, KV writes, or paid plans).

API contract for the app: [`docs/mvp/api.md`](../docs/mvp/api.md).

## Layout

| path | what |
|---|---|
| `src/index.ts` | entry + router (`/v1/...`), JSON error envelope |
| `src/auth.ts`, `src/deps.ts` | Google/Apple ID-token verification (JWKS via `jose`), sessions, dev auth |
| `src/coverage.ts`, `src/routes/coverage.ts` | bitset encode/decode/OR, metrics (mirrors Kotlin `Progress`), sync |
| `src/plausibility.ts`, `src/ratelimit.ts` | cheat limits, D1 fixed-window rate limits |
| `src/users.ts` | user columns, rank key, lazy metric recompute |
| `src/routes/board.ts`, `src/snapshot.ts` | leaderboard, community trail stats (daily snapshots), report-name |
| `src/routes/me.ts`, `src/routes/admin.ts`, `src/apple.ts` | profile, deletion (+ Apple revoke), moderation/admin |
| `src/routes/pages.ts` | public HTML pages: `/privacy`, `/terms`, `/support`, `/delete-account` |
| `src/data/legal-pages.ts` | generated from `docs/legal/*.md` (see below) |
| `src/moderation/blocklist.json`, `src/moderation/names.ts` | name rules (product §5 + TL-9), anon names (product §4) |
| `src/data/segments.json` | generated segment metadata (see below) |
| `migrations/` | D1 schema |
| `test/` | vitest in the Workers runtime (`@cloudflare/vitest-plugin`, local D1) |

## Local development

```sh
cd backend
npm install                        # also generates worker-configuration.d.ts (wrangler types)
cp .dev.vars.example .dev.vars    # enables /v1/auth/dev and a local ADMIN_TOKEN
npm run dev                        # applies migrations to local D1, then wrangler dev on 0.0.0.0:8787
curl localhost:8787/v1/health
curl -XPOST localhost:8787/v1/auth/dev -d '{"deviceId":"emulator-1"}'
```

Android emulator reaches it at `http://10.0.2.2:8787`, the iOS simulator at `http://localhost:8787`.
Local D1 state lives in `.wrangler/state` (gitignored); delete it to reset.

```sh
npm test            # vitest (Workers runtime, fresh local D1 with migrations)
npm run typecheck   # tsc --noEmit
npm run types       # regenerate worker-configuration.d.ts after editing wrangler.jsonc
```

## Segment metadata

The server needs per-segment length, tick count, tick length and `included`, plus trail -> segments, to
recompute metrics. `scripts/build-segment-meta.mjs` builds one committed file per dataset (RD-7):

| Dataset | Source | Output |
|---|---|---|
| `trails` | `data/dist/{trails,coverage_segments}.json` | `src/data/segments.json` (~80 KB) |
| `roads` | `data/roads/dist/{roads,coverage_segments}.json` | `src/data/segments.roads.json` (~100 KB) |

```sh
npm run build:segments              # trails, reads ../data/dist
npm run build:segments:roads        # roads, reads ../data/roads/dist
node scripts/build-segment-meta.mjs [--dataset roads] /path/to/dist   # e.g. the main checkout's dist from a worktree
```

Trails: only segments that count are kept (included, or on a named trail). Roads (`docs/roads/product.md`):
only **counted** roads (`counted == true`) and only **included** `rseg-*` segments; a counted road keeps only
its included segment ids, so its fraction is measured over its public part. The script fails if a segment id
has the wrong prefix (`seg-` for trails, `rseg-` for roads). **Regenerate the roads file whenever the roads
dataset is rebuilt** (segment re-splits mint new `rseg` ids), run `npm test`, and commit it with the app's
roads dataset bump. Tick count/length replicate the Kotlin
`SegmentIndex`/`resample` (same projection, `tickM = 5`). `data/dist` is gitignored; when the dataset
changes, rebuild and commit the JSON together with the app's dataset bump. Uploads with another
`datasetVersion` are still accepted (TL-7) and computed with the bundled dataset; ticks beyond a segment's
current tick count are dropped.

## Datasets (RD-7: trails and roads)

One account can play two goals. Requests carry `dataset` = `trails` (default, so pre-RD-7 clients are
unchanged) or `roads`: `PUT /v1/coverage` body field (or `?dataset=`), `GET /v1/coverage?dataset=`,
`GET /v1/leaderboard?dataset=`, `GET /v1/stats?dataset=` (`/v1/trails/stats` = trails). Scoping:

- `coverage` rows stay keyed by segment id alone; a dataset only accepts its own prefix (`seg-` / `rseg-`), and
  reads use the prefix range, so the datasets never mix.
- `user_metrics (user_id, dataset)` holds island metres/rank, completed count, `reached_at`, `metrics_version`,
  `coverage_rev` (CAS) and walk state. The matching `users` columns are legacy (migration 0003 backfilled them
  into `trails` rows; no longer read or written).
- `user_trails` / `user_completed` / `walks` carry `dataset`; walk ids are credited per dataset.
- Snapshots (`leaderboard_snapshot`, `trail_stats_snapshot`, `snapshot_meta`) are per dataset; the cron and
  `POST /v1/admin/snapshot` rebuild both. Name, hide-me, admin hide, reports and deletion are per user (all
  datasets). `GET /v1/me` metrics stay the trails dataset's.

## Public pages (privacy policy, terms, support)

`GET /privacy`, `/terms`, `/support` and `/delete-account` (also `HEAD`) are static HTML with no scripts or external
resources (`content-security-policy: default-src 'none'`), sharing one style and footer nav. The first three are
rendered at build time from `docs/legal/privacy-policy.md`, `terms.md` and `support.md` by
`scripts/build-legal-pages.mjs` into `src/data/legal-pages.ts` (committed; no Markdown parser at runtime). After
editing a doc:

```sh
npm run build:legal                 # regenerate src/data/legal-pages.ts, then commit both
```

`npm test` first runs `build-legal-pages.mjs --check` and fails if the module is stale. The script supports only
the Markdown the docs use (headings, paragraphs, lists, pipe tables, bold, code, links) and errors on anything
else. Store URLs: privacy policy `https://<host>/privacy`, App Store support `https://<host>/support`, Play
account deletion `https://<host>/delete-account`.

## Plausibility (cheat limits: decisions TL-2, review P0-2/P1-3)

Each `PUT /v1/coverage` carries `walks: [{id, durationS}]` (random UUID per walk). The server stores only the
walk **id** (to credit it once); durations are used for the check and discarded. Rules:

```
newly covered metres <= 4.0 m/s x sum(durationS of walks not credited before) + 500 m   (slack only if >= 1 new walk)
each durationS is clamped to 6 h
after the first accepted upload: sum(new durationS) <= seconds since the last walk-crediting upload + 15 min
walks per upload: 2000 until the first accepted upload (sign-in backfill), then 200; 10,000 walk ids per account
```

Speed and slack are `PLAUSIBLE_SPEED_MPS` / `PLAUSIBLE_SLACK_M` in `wrangler.jsonc`. A violation rejects the
whole upload (`422 implausible`); nothing is stored and walk ids stay uncredited. Also rejected: malformed
segment ids, bits beyond a segment's tick count (same dataset), `tickM != 5`, bodies > 512 KB; 30 uploads/hour/user.
Known limits: a cheater can still invent walk ids/durations within these bounds (basic protection by design;
Play Integrity / App Attest later).

## Snapshots and caching (TL-11, review P1-6, P2-16)

Others' leaderboard rows and community trail stats come from snapshot tables rebuilt by the daily Cron Trigger
(`17 9 * * *` UTC), on demand via `POST /v1/admin/snapshot`, and automatically when missing or built with an
older dataset. Renames, admin hide/reset and deletions edit the snapshot in place. Each isolate caches the
snapshot in memory keyed by a random `snap_id`, so a public read costs one D1 row read. Public endpoints have a
per-isolate in-memory IP limit (120 / 10 min). The caller's own `you` row is always live.

Snapshot-diff inference: daily snapshots make it hard to tie a jump to a specific walk; community counts below 5
are hidden and larger ones rounded to the nearest 5, so diffing two days rarely reveals a single walker.

The cron also purges rate-limit rows older than 2 days, expired sessions and old IP salts, and recomputes
metrics for users computed with an older dataset (P1-7; also done lazily when a user reads their own data).

Locally: `curl -XPOST -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8787/v1/admin/snapshot`, or
`wrangler dev --test-scheduled` then `curl localhost:8787/__scheduled`.

## Known limits

- **CPU (free tier: 10 ms CPU/request).** Both segment-meta files (~180 KB JSON) are parsed once per isolate at
  startup, not per request. A coverage upload decodes and ORs at most ~1,600 counted segments and
  recomputes metrics in one pass (not profiled yet; check with `wrangler tail` CPU time after deploy). A whole-island backfill with 2,000 walks is the worst case;
  if it ever exceeds the limit, clients retry with smaller walk batches. Snapshot rebuilds run in the cron (15 min CPU).
- **Nonce.** `nonce` is optional, so a leaked, still-valid ID token (≤ 1 h) could be replayed to sign in.
  Clients should always send a nonce (Apple: SHA-256 nonce; Google Credential Manager: `setNonce`); the server
  can make it mandatory once both clients do.
- **Dev auth** needs `ALLOW_DEV_AUTH=true` **and** a request host of `localhost`, `127.0.0.1` or `10.0.2.2`.
  A physical phone on the LAN can't use it; use an emulator or simulator, or `adb reverse tcp:8787 tcp:8787`.

## Temporary deployment (2026-10-20)

To unblock sign-in and sync integration before the final domain exists, the Worker runs on the owner's
Cloudflare account at **https://bainbridge-trails-api.patjackson52.workers.dev** (D1 `bainbridge-trails`,
id in `wrangler.jsonc`; migrations 0001–0002 applied; `ADMIN_TOKEN` secret set, local copy in
`~/.config/bainbridge-trails/admin-token`, 0600). Dev auth is off there (host check). Redeploy with `npm run deploy`.
`GOOGLE_CLIENT_IDS` holds the web client id (configured 2026-10-20, see `docs/setup/google-sign-in.md`); `APPLE_CLIENT_IDS` is empty until the owner sets up Sign in with Apple.
Before publishing: add a custom domain (Workers route), switch `ApiConfig.PROD_BASE_URL` and the iOS metadata URLs.

## Deploy (owner only — agents never deploy)

Needs the owner's Cloudflare account. All free tier.

```sh
cd backend
npm install                        # also generates worker-configuration.d.ts (wrangler types)
npx wrangler login
npx wrangler d1 create bainbridge-trails          # copy the printed database_id into wrangler.jsonc
npm run migrate:remote                             # wrangler d1 migrations apply DB --remote
npx wrangler secret put ADMIN_TOKEN                # long random string (>= 16 chars); admin API is off without it
```

Edit `wrangler.jsonc` `vars`:
- `GOOGLE_CLIENT_IDS`: comma list of accepted Google audiences (the **web** client id used by Android
  Credential Manager as `serverClientId`; add the iOS client id if Google sign-in ships on iOS).
- `APPLE_CLIENT_IDS`: comma list (iOS bundle id, e.g. `com.sloopworks.bainbridgetrails`; Services ID if web).
- Optional, for Apple token revocation on account deletion (TL-10): `APPLE_TEAM_ID`, `APPLE_KEY_ID` vars and
  `npx wrangler secret put APPLE_PRIVATE_KEY` (contents of the Sign in with Apple `.p8` key). Without them,
  revocation is a no-op.
- `SUPPORT_EMAIL`: shown on the public `GET /delete-account` page (Play Store account-deletion URL). Preset to
  `privacy@sloopworks.com`, the address in the privacy policy (`docs/legal/*.md`). **TODO(owner):** route that
  address (Cloudflare Email Routing) before publishing, or change it here and in `docs/legal/*.md` together.

Then `npm run deploy`. It applies pending D1 migrations to the remote database first
(`wrangler d1 migrations apply DB --remote`), then deploys the Worker, so new code never runs against an old
schema. Migrations must stay backward compatible with the Worker version still serving during the deploy
(additive columns/tables only). The Worker is served at `https://bainbridge-trails-api.<account>.workers.dev`
(Cache API is a no-op on `workers.dev`; stats still send `Cache-Control: public, max-age=300`).
Never set `ALLOW_DEV_AUTH` in `wrangler.jsonc` or as a production secret.

Admin (moderation):

```sh
curl -H "Authorization: Bearer $ADMIN_TOKEN" https://.../v1/admin/reports
curl -XPOST -H "Authorization: Bearer $ADMIN_TOKEN" https://.../v1/admin/users/<userId>/hide
curl -XPOST -H "Authorization: Bearer $ADMIN_TOKEN" -d '{"hidden":false}' https://.../v1/admin/users/<userId>/hide
curl -XPOST -H "Authorization: Bearer $ADMIN_TOKEN" https://.../v1/admin/users/<userId>/reset-name
curl -XPOST -H "Authorization: Bearer $ADMIN_TOKEN" https://.../v1/admin/users/<userId>/dismiss-reports
curl -XPOST -H "Authorization: Bearer $ADMIN_TOKEN" https://.../v1/admin/snapshot
```

Free-tier budget (D1: 5M rows read, 100k rows written per day; Workers: 100k requests/day) is ample for
an island-sized audience: an upload writes only changed segments + changed trail fractions.

## Privacy

Server stores: provider + subject (no email/name from the token), display/anon name, per-segment tick bitsets
(no timestamps), per-trail fractions, sticky completed trail ids, credited walk ids (no times, no durations),
session token hashes with expiry, and (only if Apple revoke is configured) the Apple refresh token.
Server-clock timestamps kept: `reached_at` (leaderboard tie-break, never returned), `last_walk_upload_at` (P1-3
check), report time (admin triage). IPs are only stored as SHA-256(random daily salt + IP) in rate-limit keys;
salts are deleted daily, rows after 2 days. No GPS, routes or walk times. Workers Logs (`observability` in `wrangler.jsonc`) keep request metadata, which can
include the client IP, for a few days; the privacy policy discloses this (max 7 days). Turning invocation logs off
would remove it. `DELETE /v1/me` deletes every row for
the user (including their leaderboard snapshot row); aggregate per-trail counts drop them at the next rebuild.

The public privacy policy (`docs/legal/privacy-policy.md`, served at `/privacy`) must stay in line with this
section; update both together.
