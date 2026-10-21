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
- Datasets (RD-7): see [Datasets](#datasets-trails-and-roads). Everything defaults to `trails`; the roads app
  adds `dataset=roads`.

## Error codes

| HTTP | code | when |
|---|---|---|
| 400 | `bad_request` | missing/invalid field |
| 400 | `invalid_json` | body is not JSON / empty |
| 400 | `invalid_dataset` | `dataset` not `trails`/`roads`, or body and query disagree |
| 400 | `invalid_coverage` | bad segment id (incl. the other dataset's prefix), bad base64, bits beyond tick count, `tickM != 5`, bad walk id/duration, too many segments |
| 400 | `too_many_walks` | more walks than allowed in one upload (`maxWalks`: 2000 before the first accepted upload, then 200) |
| 400 | `invalid_play` | malformed Play fields on `PUT /v1/coverage` (`play` not an array, bad `walkId`, two elements for one walk, claim without string `id`/`kind`/`weekId`, bad `hints`/`dayKey`/`playConfig`) |
| 400 | `too_many_claims` | more than 50 Play claims in one upload (`maxClaims`: 50) |
| 400 | `name_invalid` | display name breaks a format rule, or bad `anonName` |
| 400 | `name_rejected` | display name hits the blocklist |
| 401 | `unauthorized` | no/invalid/expired session token |
| 401 | `invalid_token` | Google/Apple ID token rejected (signature, `iss`, `aud`, `exp`, nonce) |
| 403 | `forbidden` | admin token wrong |
| 404 | `not_found` | unknown route; `/v1/auth/dev` when dev auth is off; unknown user; Play tide year not bundled |
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
  "hiddenByModeration": false,
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
{ "hiddenFromBoard": true }         // P1-8 "Hide me from the leaderboard" (boolean)
```
`200` → same body as `GET /v1/me`. Errors: `name_invalid`, `name_rejected`, `name_rate_limited`, `bad_request`,
`rate_limited` (429 with `retry-after`: more than 10 `hiddenFromBoard` changes per 24 h; re-sending the current
value doesn't count).

`hiddenFromBoard` is the user's own choice (column `self_hidden`), separate from an admin hide (`hidden`), so
turning it off never undoes moderation; an admin hide is reported only as `hiddenByModeration` (P2-10: the app
shows a neutral note so the user knows why the toggle doesn't bring the row back). Hiding removes the row from
the leaderboard snapshot at once (`you` becomes null). Un-hiding brings back the caller's live `you` row at once;
others see the row again at the next daily snapshot (TL-11). Self-hidden users still count in the anonymous
community trail stats (counts < 5 hidden, rounded to 5); admin-hidden users don't.

Name rules (server authoritative, TL-9): NFC, trim, collapse spaces; 3–24 characters; only
`[A-Za-z0-9 À-ÖØ-öø-ÿ'._-]`; ≥ 2 letters; no `@`, `www`, `http`, `.tld`; allowlist first, then severe
substring (leetspeak folded), then mild whole-word. Re-sending the current name doesn't count toward 5/day.

**Anon word lists** = product §4 minus `Wandering` and `Thimbleberry` (TL-9: the only words that can make
a name longer than 24 chars) → 47 adjectives × 47 nouns × NN 10–99. The client lists must match exactly
(`backend/src/moderation/names.ts`).

### `DELETE /v1/me`

Deletes the account and **all** server data in every dataset (coverage, trail/road fractions, completions,
metrics, walk ids, Play claims / week rows / totals, sessions, reports by/against, leaderboard rows); revokes the Apple token when configured. `204`. Local data is the
client's decision (D12). Signing in again creates a fresh account.

Public web page for store listings: `GET /delete-account` (HTML, no `/v1`).

## Coverage sync

Encoding = Kotlin `CoverageState.encode()`: `{"seg-000123": "<base64>"}`; tick *i* is bit *(i % 8)* of
byte *(i / 8)*; standard base64 with padding; trailing zero bytes dropped; empty segments omitted;
keys sorted. Server responses are always normalized this way.

### `PUT /v1/coverage`

```json
{
  "dataset": "trails",
  "datasetVersion": "2026-10-18+aad9d16e",
  "tickM": 5,
  "segments": { "seg-000099": "Hw==", "seg-006755": "/////w8=" },
  "walks": [ { "id": "6f1c2a7e-9a43-4c1e-8f0b-2d1e5b7c9a10", "durationS": 2712 } ]
}
```

- `dataset`: `trails` (default when absent) or `roads`; `?dataset=` works too. Roads sends only `rseg-*` ids.
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
  "dataset": "trails",
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
  and listed in `ignoredSegments` (not stored, not returned). Ids not matching `seg-NNNNNN` (roads:
  `rseg-NNNNNN`) → `400`.
- `429 rate_limited`: 30 uploads/hour per account and dataset. Upload per finished walk + once on
  sign-in, not continuously.
- Play (optional fields `playConfig`, `play`; see [Claims on `PUT /v1/coverage`](#claims-on-put-v1coverage)): absent
  = this exact request and response; present = the same `200` plus a trailing `play` object.

### `GET /v1/coverage?dataset=trails|roads`

Bearer required. `200`: same shape without `accepted`/`newlyCoveredM`/`ignoredSegments`. `dataset` defaults to
`trails`.

## Leaderboard

### `GET /v1/leaderboard?limit=100&dataset=trails|roads`

`dataset` defaults to `trails`; each dataset has its own board (same rules). The response echoes `"dataset"`.
Auth optional (send the bearer to get `isYou` and `you`; an invalid token → `401`). `limit` 1–100, default 100.
Eligible: not hidden, island % > 0. Order: island % **as displayed** (floored to 0.1 %) desc → trails
completed desc → server time the user reached that displayed % asc → user id. Ranks are unique 1..n.

TL-11: `entries` come from a **daily snapshot** (names, hides and deletions apply immediately). `you` is
**live**: the caller's current numbers ranked against the snapshot, so it can differ from the caller's own
row inside `entries`. Show `you` for the caller.

```json
{
  "dataset": "trails",
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

### `GET /v1/stats?dataset=trails|roads` (alias: `GET /v1/trails/stats` = trails)

No auth (public, TL-4). From the daily snapshot. `Cache-Control: public, max-age=300`. `dataset` defaults to
`trails`; for roads, `trails` is keyed by counted road id.

```json
{
  "dataset": "trails",
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
| `POST /v1/admin/snapshot` | rebuild leaderboard + stats snapshots of every dataset now (dev/tests); `{ok, ranked, trails, datasets: {trails: {ranked, trails}, roads: {…}}}` |

## Datasets (trails and roads)

RD-7 (`docs/roads/decisions.md`): one account, two goals. `dataset` = `trails` (default) | `roads`.

| | trails | roads |
|---|---|---|
| segment ids | `seg-NNNNNN` | `rseg-NNNNNN` |
| `completedTrailIds`, stats keys | `trail-*` | `road-*` (counted roads only) |
| `metrics.trailsCompleted` / `trailsTotal` | trails | **roads** (same field names) |
| `metrics.islandTotalM` | trails `island_total_shippable_m` | roads `island_total_shippable_m` |

- Per dataset: coverage, metrics, sticky completions, walk-id crediting and plausibility state, leaderboard,
  community stats. A road's fraction is measured over its counted (included) segments only; completion
  threshold 0.85, sticky (TL-7).
- Per account (all datasets): sign-in, name, `hiddenFromBoard`, admin hide, reports, deletion. (The 30/h upload limit is per dataset.)
- `GET /v1/me` `metrics` stay the **trails** dataset's; the roads app reads its metrics from
  `GET /v1/coverage?dataset=roads` (and every `PUT` response).
- Client changes for the roads build: send `"dataset": "roads"` in `PUT /v1/coverage`, add `?dataset=roads` to
  `GET /v1/coverage`, `GET /v1/leaderboard`, and use `GET /v1/stats?dataset=roads`; compare `datasetVersion`
  against `datasets.roads.datasetVersion` from `/v1/health`. The trails build needs no change.

## Play (opt-in gamification)

Specs: `docs/play/schedule.md` § 4.8, § 7 (endpoints § 7.5), `docs/play/product.md` § 3.5, § 3.6, § 3.10 and
`docs/play/decisions.md` PD-2, PD-3, PD-10, PD-11. `GET /v1/play/content` and `GET /v1/play/tides/{year}` are public
(no auth) and share the public per-IP limit (120 requests / 10 min, `429 rate_limited`); claims ride
`PUT /v1/coverage` and `GET /v1/play/me` needs the bearer token. `GET /v1/play/challenges` is public and fills the
caller's own progress when a bearer token is sent.

### `GET /v1/play/content?v=<cachedVersion>`

The newest content pack (`data/play/play-content.json`, schema 1, bundled into the Worker at deploy).

- `200` body = the pack JSON. Headers: `ETag: "pc-<version>-<sha12>"` (`sha12` = first 12 hex chars of the SHA-256
  of the body), `Cache-Control: public, max-age=300`.
- `304` (empty body, same `ETag` and `Cache-Control`) when `If-None-Match` names the current ETag (weak `W/` or a
  comma list accepted; `*` matches), **or** when `v` equals the current `version`, so a client without an ETag can
  skip the body. Any other `v` (older, newer, empty, malformed) gets the `200`.
- The client refreshes at most once per hour of Play-on foreground and keeps `max(bundled, cached)` by `version`
  among valid packs; it never applies a pack it cannot validate.
- The Worker keeps the last ≤ 8 published packs to evaluate claims made against older versions; only the newest
  is served here.

### `GET /v1/play/tides/{year}`

NOAA CO-OPS high/low predictions for Eagle Harbor (station 9445882), as generated by `scripts/build-tides.mjs`
(not for navigation). `200` for a bundled year (today 2026–2029), `Cache-Control: public, max-age=31536000,
immutable`; any other year or a malformed one is `404 not_found`. The client fetches a year only when its bundled
tables do not cover today + 60 days.

```json
{"station":"9445882","datum":"MLLW","units":"cm","year":2027,"t0":"2027-01-01T00:00Z","source":"noaa-coops","fetched":"2026-10-21","e":[[115,55,"L"],[414,253,"H"]]}
```

`e` = `[minutes since the previous event (the first since t0), height in cm, "H"|"L"]`.

### Island time and occurrence keys (for later Play calls)

All Play dates are island-local (`America/Los_Angeles`). A week is [Monday 00:00, next Monday 00:00) and its id is
the Monday's date (`2027-01-04`). An occurrence key is `<itemId>@<yyyy-mm-dd>[#n]`, the date being the occurrence's
first island-local date (the Monday for weekly items, the Thursday for the kraken window Thu 06:00 → Sun 20:00).
The server evaluates the date-level recurrence rules and `HH:MM` windows only; `data/play/golden/date-rules-2027.json`
is the parity golden shared with the app.

Placement index `#n` (multi-placement items only), zero-padded to the digits of the largest index:
- weekly treasure: `#00`…`#11` (trails) / `#00`…`#19` (roads). Placement `n` takes the pack's `tiers` in order,
  each repeated `count` times (trails `#00`–`#06` sea glass, `#07`–`#09` agate, `#10` glass float, `#11` ship's
  bell); the `everyNthWeek: 4` tier pays as its `else` tier except in weeks whose index since the pack epoch's
  week (2026-10-26 = week 0) is a multiple of 4.
- kraken: `kraken-<goal>@<Thursday>#n` = stretch `n` of that week's coast (`rotate[weeks since epoch mod len]`), in
  the order of the coast pool's `members` (= `derived-<goal>.json` `coasts.<pool>.stretches`).
- every other item: no `#n`.

### Claims on `PUT /v1/coverage`

Only when signed in **and** Play is on. Two optional body fields next to the coverage fields:

```json
"playConfig": "2026.11.0",
"play": [ { "walkId": "6f1c2a7e-9a43-4c1e-8f0b-2d1e5b7c9a10",
            "claims": [ { "id": "treasure-weekly-roads@2026-10-26#07", "kind": "treasure", "weekId": "2026-10-26" },
                        { "id": "kraken-roads@2026-10-29#0", "kind": "ink", "weekId": "2026-10-26", "band": 95 } ],
            "dayKey": "2026-10-26",
            "hints": { "riddle-madrone": 1 } } ]
```

- One element per walk, tied by `walkId` to a `walks[]` entry of the **same** upload; at most 50 claims per upload
  (`400 too_many_claims`). Walks over the walk cap keep their element queued for the next upload.
- An element may have `claims: []` and no `dayKey`: the app sends one for a walk that earned only walk-derived
  marks (W-COV / W-DONE / W-SQUARE) so the server derives them now and answers with `play.total`; until that answer
  the phone shows those Logbook lines as provisional.
- `kind` ∈ `treasure`, `egg`, `riddle`, `ink` (kraken stretch), `lore`, `challenge`; `band` (60 / 80 / 95) only on
  `ink`. `weekId` = the week id the claim belongs to. `dayKey` = the week id, sent once per island day on the walk
  that earned W-DAY (server: ≤ 7 per week). `hints` = hints used per riddle item claimed on this walk (0–2 count).
  `playConfig` = the pack version the phone evaluated the claims against.
- No marks, times, days or coordinates: any other field (a client `marks`, `total`, `tier`) is ignored. The server
  derives every mark from the pack named by `playConfig` and the accepted coverage.
- Evaluated only after the coverage passes plausibility, in the same batch that credits the walks: a `422` rejects
  the coverage **and** its claims (mark them `local_only`), and a retried upload never double counts.

`200` = the usual coverage body plus, last:

```json
"play": {
  "accepted": ["treasure-weekly-roads@2026-10-26#07"],
  "rejected": [ { "id": "kraken-roads@2026-10-29#0", "code": "no_bits" } ],
  "capped": [],
  "total": 4180,
  "week": 140
}
```

- `accepted`: claim ids now stored, including re-sent ones (a repeat is a no-op that still reports `accepted`) and
  `capped` ones. `rejected`: the others with a code; keep them `local_only`, do not resend. `capped`: accepted
  with 0 marks because the week id's found marks reached 2,800 (`week_cap`).
- `total`: the server's marks total for this goal (W-COV, W-DONE, W-SQUARE, finds, W-DAY, W-WEEK, challenges,
  F-RETREAT). `week`: this server week's finds + W-DAY marks. Show the server values; client totals are display only.
- Rejection codes (per claim, checked in this order after a repeat check):

| code | when |
|---|---|
| `stale_config` | `playConfig` missing or not one of the Worker's last 8 packs |
| `unknown_item` | no enabled, non-killed item with that id for this goal, or `kind` does not match its type; a `challenge` claim for an island challenge (`ch-krakenweek`: credited by the server, never claimed) |
| `bad_occurrence` | malformed key, no occurrence starts on that date, `#n` missing / out of range / not zero-padded, `#n` on a single item, `weekId` not a Monday or the occurrence not active in it, `band` the item does not pay |
| `not_this_walk` | `walkId` is not a walk this upload credits (new in `walks[]`) |
| `too_early` | occurrence starts more than 1 h after server now, or `weekId` is after the server's current week |
| `too_late` | `weekId` before the server's previous week, or the occurrence ended before the server island date − 2 days; `challenge`: after the week's end + 7 days |
| `no_bits` | `ink`: less than `band` % of the stretch's length carries your coverage bits after the merge; feature riddle: less than 100 m of the answer feature does; `challenge` (`ch-sweep`): your contributions (below) have not reached the target |

- Walk-derived marks need no claim: whenever `play` is present (even `[]`), the server recomputes W-COV
  (`floor(non-quiet covered m / 10)` trails, `/ 25` roads), W-DONE (+100 / +25 per completed non-quiet feature) and
  W-SQUARE (+25 per chart square) from the merged coverage and credits `max(previous, new)` (this is also the
  opt-in starting balance).

### `GET /v1/play/me?dataset=trails|roads`

Bearer required (`401` without). The Play chip summary and the Logbook restore after a reinstall (product § 3.6.5).
No side effects.

```json
{
  "dataset": "roads",
  "weekId": "2026-10-26",
  "total": 4180,
  "week": 140,
  "perKind": { "treasure": 3, "egg": 1, "riddle": 1, "ink": 1, "lore": 2, "challenge": 0 },
  "occ": ["egg-madrone@2026-10-26", "kraken-roads@2026-10-29#0", "treasure-weekly-roads@2026-10-26#07"]
}
```

`weekId` = the server's current week; `week` = that week's finds + W-DAY marks; `perKind` = claim counts;
`occ` = every claimed occurrence key, sorted (re-mark them found; never claim them again). It includes the rows the
daily cron credits: `kraken-<goal>@<Thursday>` (kind `ink`, no `#n`) = F-RETREAT for that attack and
`ch-krakenweek@<Thursday>` (kind `challenge`) = C-ISLAND.

### Challenges (`challenge` claims, contributions, cron)

Nothing is timed (PD-4): a challenge has a server-authoritative window (the Worker's current pack, island time),
your progress, and `{band, count}` island results. Two kinds in v1 (product § 4.5):

- **`ch-sweep`** (solo, weekly Sat 00:00 → Mon 00:00, target 3 trails / 5 roads, 100 marks). Your contribution =
  trails / roads (never quiet trails) newly completed by **Play uploads** (the body carried `play`, even `[]`)
  received from the window start until the server island date is more than 2 days past the window end. Claim it on
  the walk that reaches the target: `{ "id": "ch-sweep@<Saturday>", "kind": "challenge", "weekId": "<its Monday>" }`;
  accepted when the stored contributions plus this upload's reach the target (else `no_bits`), until the week's
  end + 7 days.
- **`ch-krakenweek`** (island, the kraken window Thu 06:00 → Sun 20:00). Never claimed by the client. Each accepted
  new `ink` claim counts its stretch once per user (`repelled_by`) and adds 1 to your contribution.

Daily cron (the existing `17 9 * * *` trigger; idempotent, running twice credits once):
- **W-WEEK**: a week with ≥ 2 accepted `dayKey`s is kept once it ended: +40, +10 for each consecutive kept week
  before it, max +100 (a week without 2 walking days resets the run). A week below 2 closes as not kept only after
  its `dayKey` grace (week end + 7 days).
- **Kraken attacks**, once their claims can no longer arrive (server island date > Sunday + 2 days): if every
  stretch was repelled by ≥ 3 players, every contributor gets F-RETREAT +50 and C-ISLAND +75 in the attack's week
  (weekly found cap applies). Otherwise the kraken slipped away; nothing is paid or lost.
- Stretch aggregates and contributions older than 180 days are deleted.

### `GET /v1/play/challenges?dataset=trails|roads`

Public (per-IP limit as above); with a bearer token `progress` and `you` are the caller's. Every challenge
occurrence of the goal whose window has started and still takes contributions (window end + 2 days), sorted by
`occ`. `Cache-Control: public, max-age=300` without a token, `private, no-store` with one. No side effects.

```json
{
  "dataset": "trails",
  "minCount": 5,
  "current": [
    {
      "id": "ch-krakenweek",
      "occ": "ch-krakenweek@2026-10-29",
      "scope": "island",
      "windowStart": "2026-10-29T06:00-07:00",
      "windowEnd": "2026-11-01T20:00-08:00",
      "open": true,
      "progress": 1,
      "target": 2,
      "you": { "band": "middle" },
      "bands": [ { "band": "top", "count": 10 }, { "band": "middle", "count": null }, { "band": "starting", "count": null } ],
      "island": { "stretches": 2, "repelled": 1 }
    },
    {
      "id": "ch-sweep",
      "occ": "ch-sweep@2026-10-31",
      "scope": "solo",
      "windowStart": "2026-10-31T00:00-07:00",
      "windowEnd": "2026-11-02T00:00-08:00",
      "open": true,
      "progress": 1,
      "target": 3,
      "you": { "band": "starting" },
      "bands": [ { "band": "top", "count": 5 }, { "band": "middle", "count": 15 }, { "band": "starting", "count": 20 } ]
    }
  ]
}
```

- `windowStart` / `windowEnd`: the schedule's window in island wall time with its offset (the same for everyone; no
  per-user time is ever returned). `open`: false after the window end while late contributions are still accepted.
- `progress`: your contributions (ch-sweep: completions counted as above; ch-krakenweek: stretches of this attack
  you repelled); `null` without a token. `target`: the goal's target / the attack's stretch count.
- Bands by contribution against `target`: `top` = reached, `middle` = at least half, `starting` = any. `you` = your
  band, `null` without a contribution or a token. `bands[].count` = participants in the band (admin-hidden users
  excluded); **below `minCount` (5) it is `null`** ("fewer than 5"), larger counts are rounded to the nearest 5.
  No names, user ids, scores, marks, ranks or timestamps.
- `island` (island challenges only): the attack's stretches and how many have been repelled by ≥ 3 players.

### Dev clock (local only)

`DEV_CLOCK_OFFSET_MS` in `backend/.dev.vars` shifts the server clock by that many milliseconds so an e2e run at a
debug build's Play clock passes the time rules. It is honoured only when `ALLOW_DEV_AUTH=true` **and** the request
host is `localhost`, `127.0.0.1` or `10.0.2.2`; anywhere else it is ignored (tested).

## Other

`GET /v1/health` → `{"ok": true, "datasetVersion": "…", "tickM": 5, "datasets": {"trails": {"datasetVersion": "…", "tickM": 5}, "roads": {…}}}`
(top-level `datasetVersion` is the trails dataset's).

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
