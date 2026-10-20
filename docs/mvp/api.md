# Bainbridge Trails API v1

Backend: Cloudflare Worker in `backend/` (see `backend/README.md`). This is the contract for the KMP client.
Follows `docs/mvp/decisions.md` (TL-*) over `product.md`.

- Base URL: local `http://10.0.2.2:8787` (Android emulator) / `http://localhost:8787` (iOS simulator);
  prod `https://bainbridge-trails-api.<account>.workers.dev` (owner deploys).
- All API paths are under `/v1`. JSON in, JSON out (`content-type: application/json`). The public HTML pages
  (`/privacy`, `/terms`, `/support`, `/delete-account`) are listed at the end.
- Auth: `Authorization: Bearer <token>` with the session token from an `/v1/auth/*` call.
  Tokens are opaque, valid 90 days. On `401` drop the token and sign in again.
- Errors: always `{"error": {"code": "...", "message": "..."}}`, sometimes with extra fields
  (e.g. `retryAfterSeconds`). Switch on `code`, never on `message`.
- Request size limits: coverage 512 KB, auth 16 KB, other bodies 1–4 KB (`413 payload_too_large`).
- No CORS (mobile only). No timestamps, locations or walk data in any public response.
- Percentages (`islandPct`, `medianPct`) are 0–100 with 1 decimal, **rounded down** (TL-5).

## Error codes

| HTTP | code | when |
|---|---|---|
| 400 | `bad_request` | missing/invalid field |
| 400 | `invalid_json` | body is not JSON / empty |
| 400 | `invalid_coverage` | bad segment id, bad base64, bits beyond tick count, `tickM != 5`, bad walk id/duration, too many segments |
| 400 | `too_many_walks` | more walks than allowed in one upload (`maxWalks`: 2000 before the first accepted upload, then 200) |
| 400 | `name_invalid` | display name breaks a format rule, or bad `anonName` |
| 400 | `name_rejected` | display name hits the blocklist |
| 401 | `unauthorized` | no/invalid/expired session token |
| 401 | `invalid_token` | Google/Apple ID token rejected (signature, `iss`, `aud`, `exp`, nonce) |
| 403 | `forbidden` | admin token wrong |
| 404 | `not_found` | unknown route; `/v1/auth/dev` when dev auth is off; unknown user |
| 405 | `method_not_allowed` | wrong method on a known route (checked before auth; `Allow` header) |
| 413 | `payload_too_large` | body too big |
| 422 | `implausible` | coverage growth exceeds what the new walks allow, or new walk time exceeds the time since the last upload; body has `newlyCoveredM`, `allowedM` |
| 422 | `walk_limit` | account has 10,000 credited walk ids |
| 429 | `rate_limited` | too many requests; `retryAfterSeconds` + `Retry-After` header |
| 429 | `name_rate_limited` | > 5 display-name changes in 24 h (client copy `name_err_rate`) |
| 502 | `provider_unavailable` | Google/Apple keys could not be fetched; retry |
| 503 | `busy` | concurrent syncs of the same account collided; retry after `retryAfterSeconds` |
| 503 | `provider_not_configured` | server has no client ids for that provider |
| 500 | `internal` | bug; retry with backoff |

## Auth

### `POST /v1/auth/google` and `POST /v1/auth/apple`

```json
{ "idToken": "<JWT from Google Credential Manager / Sign in with Apple>",
  "nonce": "optional: exact `nonce` claim expected in the token",
  "anonName": "optional: the device's anon name, e.g. \"Mossy Heron 42\"",
  "authorizationCode": "Apple only, optional but recommended: the one-time authorizationCode" }
```

The server verifies the RS256 signature against the provider JWKS, `iss`
(`https://accounts.google.com`/`accounts.google.com`, or `https://appleid.apple.com`), `aud` (configured
client ids) and `exp`. It stores only provider + `sub` (no email). `anonName` is used when the account is
created, if it is `Adjective Noun NN` from the shared lists; otherwise the server generates one (send your
local anon name with `PUT /v1/me` afterwards to keep them in sync). Apple: if you pass a SHA-256 nonce to
Apple, send that same hashed value as `nonce`. Send Apple's `authorizationCode` so the server can revoke the
Apple token on account deletion (TL-10; ignored when the server has no Apple key configured).

`200`:
```json
{
  "token": "u0h2kQ…43 chars base64url",
  "expiresAt": 1800232000000,
  "isNewUser": true,
  "user": { …same as GET /v1/me… }
}
```

### `POST /v1/auth/dev` (local/debug only)

Exists only when the server runs with `ALLOW_DEV_AUTH=true` (local `.dev.vars`) **and** the request host is
`localhost`, `127.0.0.1` or `10.0.2.2`; otherwise `404`.
`{"deviceId": "emulator-pixel8", "anonName": "Calm Otter 77"}` — `deviceId` 1–128 chars `[A-Za-z0-9._:-]`.
Same response as above. Same `deviceId` → same account.

### `POST /v1/auth/logout`

Bearer required. Revokes that token. `204`, no body. Local progress stays (D11).

## Profile

### `GET /v1/me`

```json
{
  "userId": "8a3f…-uuid",
  "provider": "google",
  "displayName": "Trail Fox",
  "customName": "Trail Fox",
  "anonName": "Mossy Heron 42",
  "isAnonymous": false,
  "nameStatus": "ok",
  "hiddenFromBoard": false,
  "metrics": {
    "islandPct": 12.3,
    "islandCoveredM": 13011.4,
    "islandTotalM": 105396,
    "coveredM": 13120.9,
    "trailsCompleted": 7,
    "trailsTotal": 114
  }
}
```

- `displayName` is what the board shows. `customName` is the user's chosen name (null = anonymous).
- `nameStatus`: `anonymous` | `ok` | `blocked` (custom name matches a newly added blocklist term; the anon
  name is shown instead → show `name_reset_notice`).
- `metrics` are the server-accepted values (same math as `Progress`; `trailsCompleted` = sticky set size).
  The device stays the source of truth for its own UI.

### `PUT /v1/me`

Any subset of:
```json
{ "displayName": "Trail Fox" }      // set custom name
{ "displayName": null }             // go anonymous
{ "anonName": "Sleepy Newt 31" }    // after Shuffle / after sign-in; must use the shared word lists
```
`200` → same body as `GET /v1/me`. Errors: `name_invalid`, `name_rejected`, `name_rate_limited`.

Name rules (server authoritative, TL-9): NFC, trim, collapse spaces; 3–24 characters; only
`[A-Za-z0-9 À-ÖØ-öø-ÿ'._-]`; ≥ 2 letters; no `@`, `www`, `http`, `.tld`; allowlist first, then severe
substring (leetspeak folded), then mild whole-word. Re-sending the current name doesn't count toward 5/day.

**Anon word lists** = product §4 minus `Wandering` and `Thimbleberry` (TL-9: the only words that can make
a name longer than 24 chars) → 47 adjectives × 47 nouns × NN 10–99. The client lists must match exactly
(`backend/src/moderation/names.ts`).

### `DELETE /v1/me`

Deletes the account and **all** server data (coverage, trail fractions, completions, walk ids, sessions,
reports by/against, leaderboard row); revokes the Apple token when configured. `204`. Local data is the
client's decision (D12). Signing in again creates a fresh account.

Public web page for store listings: `GET /delete-account` (HTML, no `/v1`).

## Coverage sync

Encoding = Kotlin `CoverageState.encode()`: `{"seg-000123": "<base64>"}`; tick *i* is bit *(i % 8)* of
byte *(i / 8)*; standard base64 with padding; trailing zero bytes dropped; empty segments omitted;
keys sorted. Server responses are always normalized this way.

### `PUT /v1/coverage`

```json
{
  "datasetVersion": "2026-10-18+aad9d16e",
  "tickM": 5,
  "segments": { "seg-000099": "Hw==", "seg-006755": "/////w8=" },
  "walks": [ { "id": "6f1c2a7e-9a43-4c1e-8f0b-2d1e5b7c9a10", "durationS": 2712 } ]
}
```

- Send the whole local `CoverageState` (or any subset — the server ORs, never removes bits).
- `walks` (TL-2): **every** finished walk not yet acknowledged by a `200` (send them all in each upload, not
  one per request), `id` = random UUID per walk, `durationS` = walk duration. The **first upload after sign-in
  (backfill) carries every local walk** (max 2000; later uploads max 200). Re-sending an already-credited id is
  harmless. The server stores only the id (no durations, no times).
  Client: never split walks across uploads — after the first accepted upload a second batch sent right away
  fails the elapsed-time check below. Each sync sends one upload (≤ 2000 walks for the backfill, ≤ 200 later,
  also after `too_many_walks`); walks over the cap (longest kept) are dropped uncredited, their coverage still
  goes. Known MVP limit: > 2000 local walks at first sign-in.
- Plausibility: newly covered metres (after merge) ≤ **4.0 m/s × Σ durationS of new walk ids + 500 m**;
  the 500 m slack applies only when at least one new walk is sent, so coverage growth without a new walk is
  rejected. Each duration counts at most 6 h. After the first accepted upload, Σ new durations must fit in the
  time since the last walk upload (+ 15 min). Otherwise `422 implausible`: nothing is stored, walk ids are not
  credited. Keep local progress and show `sync_rejected_notice`; don't retry the same payload in a loop.
- Any `datasetVersion` is accepted (TL-7); the server computes with its bundled dataset (its version is
  returned). With a different version, ticks beyond the server's tick count are dropped.

`200`:
```json
{
  "datasetVersion": "2026-10-18+aad9d16e",
  "tickM": 5,
  "segments": { "seg-000099": "Hw==", "seg-000412": "Aw==", "seg-006755": "/////w8=" },
  "completedTrailIds": ["trail-arbor-trail", "trail-rotary-park-trails"],
  "metrics": { "islandPct": 0.0, "islandCoveredM": 73.8, "islandTotalM": 105396, "coveredM": 73.8, "trailsCompleted": 2, "trailsTotal": 114 },
  "accepted": true,
  "newlyCoveredM": 52.1,
  "ignoredSegments": ["seg-000001"]
}
```

- Union the returned `segments` into local state (this is how a new device gets history) and union
  `completedTrailIds` into the local sticky `completedTrailIds` (TL-7). The server's set is union-only too.
- Only segments that count (included, or on a named trail) are stored. Other well-formed ids are accepted
  and listed in `ignoredSegments` (not stored, not returned). Ids not matching `seg-NNNNNN` → `400`.
- `429 rate_limited`: 30 uploads/hour. Upload per finished walk + once on sign-in, not continuously.

### `GET /v1/coverage`

Bearer required. `200`: same shape without `accepted`/`newlyCoveredM`/`ignoredSegments`.

## Leaderboard

### `GET /v1/leaderboard?limit=100`

Auth optional (send the bearer to get `isYou` and `you`; an invalid token → `401`). `limit` 1–100, default 100.
Eligible: not hidden, island % > 0. Order: island % **as displayed** (floored to 0.1 %) desc → trails
completed desc → server time the user reached that displayed % asc → user id. Ranks are unique 1..n.

TL-11: `entries` come from a **daily snapshot** (names, hides and deletions apply immediately). `you` is
**live**: the caller's current numbers ranked against the snapshot, so it can differ from the caller's own
row inside `entries`. Show `you` for the caller.

```json
{
  "entries": [
    { "rank": 1, "userId": "8a3f…", "displayName": "Trail Fox", "islandPct": 41.2, "trailsCompleted": 30, "isYou": false },
    { "rank": 2, "userId": "c1d2…", "displayName": "Mossy Heron 42", "islandPct": 40.9, "trailsCompleted": 33, "isYou": true }
  ],
  "you": { "rank": 2, "userId": "c1d2…", "displayName": "Mossy Heron 42", "islandPct": 41.0, "trailsCompleted": 33, "isYou": true },
  "totalRanked": 57
}
```

`you` is null when signed out, hidden, or at 0%. `userId` is only for `report-name`.

## Community trail stats

### `GET /v1/trails/stats`

No auth (public, TL-4). From the daily snapshot. `Cache-Control: public, max-age=300`.

```json
{
  "datasetVersion": "2026-10-18+aad9d16e",
  "completionThreshold": 0.85,
  "minCount": 5,
  "trails": {
    "trail-arbor-trail": { "walkers": 12, "completedCount": 5, "medianPct": 63.5 },
    "trail-wagon-wheel": { "walkers": null, "completedCount": null, "medianPct": null }
  }
}
```

Every trail in the dataset is present. `walkers` = non-hidden users with fraction > 0; `completedCount` =
users with the trail in their completed set; `medianPct` = median fraction among walkers. A count below
`minCount` (5) is `null` ("Be one of the first to finish this trail"); counts ≥ 5 are **rounded to the
nearest 5** (8 → 10); `medianPct` is `null` when `walkers` is. Public endpoints allow 120 requests / 10 min
per IP.

## Moderation

### `POST /v1/report-name`

Bearer required. `{"userId": "<from leaderboard>"}` → `202 {"ok": true}`. Flags the name for admin
review only (auto-hide was cut). Duplicate reports by the same user are no-ops. Can't report yourself
(`400`); unknown user `404`; 20 reports/day.

### Admin (`Authorization: Bearer <ADMIN_TOKEN>`, not for the app)

| endpoint | effect |
|---|---|
| `GET /v1/admin/reports` | reported users with counts |
| `POST /v1/admin/users/{id}/hide` (`{"hidden": false}` to unhide) | remove from leaderboard (immediately) + community stats (next snapshot) |
| `POST /v1/admin/users/{id}/reset-name` | clear custom name (anon shown), clear reports |
| `POST /v1/admin/users/{id}/dismiss-reports` | clear reports, keep name |
| `POST /v1/admin/snapshot` | rebuild leaderboard + stats snapshots now (dev/tests) |

## Other

`GET /v1/health` → `{"ok": true, "datasetVersion": "…", "tickM": 5}`.

## Public web pages (not JSON, no `/v1`)

Static HTML for store listings and the in-app Settings links (`GET` and `HEAD`; other methods `405`). No auth,
no scripts, `cache-control: public, max-age=3600`.

| path | content | used by |
|---|---|---|
| `/privacy` | privacy policy (`docs/legal/privacy-policy.md`) | Settings > Privacy policy; Play + App Store privacy policy URL |
| `/terms` | terms of use (`docs/legal/terms.md`) | store listings, footer links |
| `/support` | contact + FAQ (`docs/legal/support.md`) | App Store support URL |
| `/delete-account` | how to delete the account; email fallback (`SUPPORT_EMAIL`) | Play account-deletion URL (TL-10) |

The Markdown is rendered into the Worker at build time (`npm run build:legal`, see `backend/README.md`).
