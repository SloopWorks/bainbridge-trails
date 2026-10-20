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
| `src/routes/board.ts`, `src/snapshot.ts` | leaderboard, community trail stats (daily snapshots), report-name |
| `src/routes/me.ts`, `src/routes/admin.ts`, `src/apple.ts` | profile, deletion (+ Apple revoke), moderation/admin |
| `src/routes/pages.ts` | public `GET /delete-account` page |
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
recompute metrics. `scripts/build-segment-meta.mjs` builds `src/data/segments.json` (~80 KB, committed) from
`data/dist/coverage_segments.json` + `data/dist/trails.json`:

```sh
npm run build:segments              # reads ../data/dist
node scripts/build-segment-meta.mjs /path/to/dist
```

Only segments that count are kept (included, or on a named trail). Tick count/length replicate the Kotlin
`SegmentIndex`/`resample` (same projection, `tickM = 5`). `data/dist` is gitignored; when the dataset
changes, rebuild and commit the JSON together with the app's dataset bump. Uploads with another
`datasetVersion` are still accepted (TL-7) and computed with the bundled dataset; ticks beyond a segment's
current tick count are dropped.

## Plausibility (cheat limits, decisions TL-2)

Each `PUT /v1/coverage` carries `walks: [{id, durationS}]` (random UUID per walk, no timestamps). The server
credits each walk id once per user (`walks` table) and requires

```
newly covered metres  <=  PLAUSIBLE_SPEED_MPS (4.0) x sum(durationS of walks not seen before)  +  PLAUSIBLE_SLACK_M (500)
```

Newly covered metres are measured after the OR-merge over counted segments. Over the limit -> the whole upload
is rejected (`422 implausible`), nothing is stored and the walk ids stay uncredited. Durations are capped at
24 h per walk. Also rejected: malformed segment ids, bits beyond a segment's tick count (same dataset),
`tickM != 5`, bodies > 512 KB; 30 uploads/hour/user.

## Snapshots (decision TL-11)

Other users' leaderboard rows and the community trail stats come from snapshot tables rebuilt by a daily
Cron Trigger (`17 9 * * *` UTC) and on demand via `POST /v1/admin/snapshot`. The caller's own `you` row is
live. Names, hidden flags and deletions are joined live. The snapshot is also built lazily the first time it
is needed (fresh deploy / local dev). Locally: `curl -XPOST -H "Authorization: Bearer $ADMIN_TOKEN"
localhost:8787/v1/admin/snapshot`, or `wrangler dev --test-scheduled` and `curl localhost:8787/__scheduled`.

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
- `SUPPORT_EMAIL`: shown on the public `GET /delete-account` page (Play Store account-deletion URL).

Then `npm run deploy`. The Worker is served at `https://bainbridge-trails-api.<account>.workers.dev`
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

Server stores: provider + subject (no email/name from the token), display/anon name, per-segment tick
bitsets, per-trail fractions, sticky completed trail ids, walk ids + durations (no start/end times), session
token hashes, and (only if Apple revoke is configured) the Apple refresh token. No GPS, routes or walk times;
the only timestamps are server-side (account creation, `reached_at` tie-break), and none are returned by
public endpoints. `DELETE /v1/me` deletes every row for the user (including their leaderboard snapshot row);
the aggregate per-trail counts drop them at the next daily rebuild.
