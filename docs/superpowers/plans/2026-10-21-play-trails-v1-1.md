# Play — Trails edition (v1.1): staged implementation plan

Date: 2026-10-21 · Role: plan-author · Branch `play/trails-v1-1` (cut from `origin/main` 2aee859).
**Rev 2:** plan review round 1 (FIX-FIRST) applied — `docs/play/trails/reviews/2026-10-21-round1-plan.md`; finding
ids in brackets. Inputs: `docs/play/trails/{brief,ux,lore-facts}.md` (brief Q1–Q5 defaults accepted; ux ACCEPTED,
§ 10 binding), `docs/play/plan.md` (stage format, § 3 e2e), `docs/play/decisions.md` PD-1…PD-25, `product.md` § 3,
`schedule.md` § 4–§ 7. Specs win over this plan except the deviations in § 1, each recorded in PD-26.

```
PLAN: Play trails v1.1 — six trails rules, two challenges, trails content, Shipyard trails build
      (acceptance criteria satisfied: 1–10; AC 5 content by the concurrent lane C0, validated here)
PRECONDITIONS:
  - C0 (concurrent content lane) committed on this branch: play-content.json 2026.11.2 with ≥ 12 trails-only items,
    build-play-content.mjs writing trails.json `featurePark` / `featureGainM` / `parks`, regenerated copies,
    `node --test scripts/*.test.mjs` green. No stage below edits C0's files until C0 is committed.
  - ux.md ACCEPTED (gate design_before_build: met). No new ADR: everything rests on PD-1…PD-25 + PD-26 (D1 writes it).
  - Owner-only, NOT in this item: Worker deploy + `migrate:remote` of 0007 (OD-19 first), destructive D1 work.
  - Every implementer branch is cut after `git pull` of origin/main and merged back into play/trails-v1-1.
```

## 1. Decisions this plan makes (record in PD-26)

- **D1 Device ledger.** `PlayState` gains `trailPaid: Set<String> = emptySet()` and `trailsStarted: Boolean = false`
  (old files decode: `ignoreUnknownKeys`, defaults). Keys, by value, never re-derived: `climb:<m>` (100…1250),
  `mile:<mi>` (1, 5 … 60), `high:<trailId>`, `park1:<park>`, `parkc:<park>`, `island`; the key is also the Logbook
  `contentId` (dedupe `(kind, contentId)`; ux § 2.2's `climb:250` / `mile:10` hold). **paid** = `trailPaid` ∪ the
  `contentId`s of Logbook entries of kinds climb, high_ground, milestone, park_first, park_complete, island [P2-2];
  owed = `qualifying(snapshot) − paid`. A dataset that drops a trail lowers nothing; one that adds a trail pays only
  keys not yet paid (AC 2 monotone).
- **D2 Server ledger: additive migration `0007_play_trails.sql`.** One MAX'd `derived_marks` cannot hold "paid by
  value" (a drop in one rule masks a new step in another), per-kind counts or a restore seed. Adds
  `play_trail_paid(user_id, dataset, key, marks, PK(user_id,dataset,key))` WITHOUT ROWID, FK cascade;
  `ALTER TABLE play_totals ADD COLUMN trail_marks INTEGER NOT NULL DEFAULT 0`; (dropped, D12:) `play_progress_keys(user_id, dataset,
  occ, key, PK(all four))` (D12). Ledger inserts `ON CONFLICT DO NOTHING`, before the totals upsert in the same batch.
  Totals SQL [P2-1]: the VALUES list binds `(SELECT COALESCE(SUM(marks),0) FROM play_trail_paid WHERE user_id=?1 AND
  dataset=?2)` as `trail_marks`; ON CONFLICT sets `trail_marks = excluded.trail_marks`, `total = MAX(cov_marks,
  excluded.cov_marks) + MAX(derived_marks, excluded.derived_marks) + found_marks + excluded.found_marks + week_marks +
  excluded.week_marks + excluded.trail_marks`; every other `total =` in `claims.ts`/`challenges.ts` adds `trail_marks`.
  Ledger inserts are allowed on the no-change upload path (idempotent, derived; `assertNoPlayWrites` says so).
  `play_trail_paid` / `trail_marks` hold no time, day or position: they derive from coverage bits + completions.
- **D3 `GET /v1/play/me`, dataset=trails only:** `perKind` gains `climb, high_ground, milestone, park_first,
  park_complete, island` (ledger key counts by prefix); the body gains `paid: string[]` (sorted). Roads body
  byte-identical (guard). **Restore [P0-1, P1-7]:** `restorable` = no `restored` entry and every Logbook entry ∈
  {start_balance, start_trails, island with `walkId == null`}. On the Restored write only: `owed = me.total −
  credited.total + Σ marks(locally start-paid keys ∖ me.paid)` and, in that same update, `trailPaid ∪= me.paid`. A
  restore without a Restored write (foreground, sign-in) never seeds `trailPaid`.
- **D4 Start balance (ux § 6).** `TrailStart.onEnable` (trails only) runs in `creditOnce` after `StartBalance`, on
  every turn-on/start, reading paid **inside** the `store.update`: owed keys → one `start_trails` entry (what "Trail
  rewards so far" when `!trailsStarted`; later catch-ups what "Trail rewards caught up", why "Trail rewards owed from
  earlier walks" [P2-8, ux § 6 copy deviation]), `island` as its own entry; nothing owed → nothing; sets
  `trailsStarted`. **Race [P1-8]:** when `!trailsStarted`, `WalkMarks.apply` runs `TrailStart` on its own `after`
  input inside the same update (writes `start_trails`, sets the flag), then the walk's rows — a walk finalized during
  the turn-on restore pays every key once. `awaitStartBalance` returns start_balance + start_trails + island written
  by this turn-on [P2-5] (one PlayWake number, ux § 6). Server equivalent: the first Play upload inserts every
  qualifying key; uploads without `play` (Play off) touch no Play table.
- **D5 Challenge target (Q3), both sides:** `target = min(default, R)`, `R = remaining(C) + P` after the walk/upload:
  C = sticky completed set, P = occurrence progress. climb in integer **decimetres** (`round(gain_m×10)`): remaining =
  Σ dm of non-quiet trails ∉ C; parks: remaining = |{Play parks with ≥ 1 non-quiet trail ∉ C} ∪ parks in P|. Every
  in-window Play completion moves value from remaining to P, so R = "remaining at window start" (ux § 7). R = 0 → no
  card, no chip count, no What's On row, no claim. **Claim [P2-6]:** climb/parks claim when `P ≥ target > 0` and the
  occurrence is unclaimed (no `prior < target` gate). **Server [P1-1]:** one upload contributes to at most one
  occurrence per challenge item: the one active at `now`, else the latest still in grace; consequence (PD-26): a walk
  synced late, after the next occurrence opened, counts toward the new one, while the client counted it by its
  Logbook date — the two can differ until the next walk. Park-hop progress is mirrored into `play_progress` (+1 per
  new park key, guarded by `NOT EXISTS` on the key, the `incStretch` pattern) so bands and `/challenges` progress =
  distinct parks [P1-9]. `/challenges` returns the personal `target` when signed in (default otherwise); climb
  `progress`/`target` are dm (api.md); `bandSql` binds the dm target for climb and `you.band` uses the personal target
  [P2-7]. Client P = this walk + Logbook `complete` entries dated inside the window (validator forces whole-day
  windows for climb/parks; no new timestamps persisted).
- **D6 Numbers and inputs.** Climb compares integer dm (99.9 m = 999 < 1000). Miles: `nonQuietCoveredM / 1609.344` on
  the `Milestones` ladder (1, then every 5; EPS 1e-9); AC 2's "1,609 m → 1 mi" is read as one statute mile (1,609.344
  m → `mile:1`, 1,609.0 → none; deviation). W-ISLAND = island fraction ≥ 1 − 1e-9 (`AppGraph.Celebration.WHOLE`;
  server `islandM ≥ islandTotalM × (1 − 1e-9)`). **Quiet [P1-2, ruling]:** the new rules take quiet segments from the
  derived JSON `quietSegments` on both sides (server `trails.ts` reads it; v1/roads `walkDerivedMarks` untouched for
  AC 1). Inputs from raw coverage `{segId: coveredM}`: `nonQuietCoveredM` (included segments ∉ quietSegments),
  `touchedTrails` / `touchedParks` (a covered included segment ∉ quietSegments), completed (sticky, ∉ quietFeatures).
  Park members = counted features (Kotlin `Progress.counts`, server `METAS.trails`); Pritchard via `excludedParks`.
- **D7 Downgrade: local Play state lost on downgrade (refused, documented) [P2-2].** New `LogKind`s append (names are
  the stored keywords). A v1 build cannot decode a v1.1 file → `JsonFile` null → empty Play state on that phone and
  overwritten on its next write; server total intact; a signed-in v1.1 turn-on restores (D3). Shipyard moves forward;
  a rollback is the owner's call. Tests: v1 state (G0) decodes in v1.1; every kind round-trips; v1.1 → v1 → v1.1 never
  pays a key twice.
- **D8 Upload contract unchanged (AC 7).** No edit to `net/ApiDtos.kt`, `PlayWalkClaimsDto`, `PlayClaimDto`; ledger
  keys never leave the phone. Test [P1-6]: (a) recursive `SerialDescriptor` element names of CoverageUpload, WalkDto,
  PlayWalkClaimsDto, PlayClaimDto = G0's names; (b) one fixed upload fixture serialized byte-equal to G0's 2aee859
  bytes; (c) `git diff --exit-code 2aee859 -- app/composeApp/src/commonMain/kotlin/com/bainbridgetrails/net/ApiDtos.kt`
  + a source-span check that the two DTOs in `PlayDtos.kt` equal 2aee859's.
- **D9 Roads guard of `roads.json`:** it embeds `"pack"`, so main's sha `ad2c1435…` cannot survive a pack bump. Guard =
  sha256 of roads.json **without the `"pack":` line** = `4ea0afa8ea4c134b…f44b9` (2aee859, equal on the branch today).
  `agenda-roads.tsv` likewise compared without its header line (`tail -n +2`) [P1-4].
- **D10 Pack version.** `published/2026.11.2.json` exists, so ch-climb/ch-parkhop ship in **2026.11.3** (L4-2).
- **D11 Before the owner deploys the Worker [P2-4].** The client runs max(bundled, cached) = 2026.11.3, so a deployed
  v1 Worker answers `stale_config` for every claim naming 2026.11.3 (→ `local_only`); its `play` block still settles
  the walk rows though it paid no trails key. A pre-Play Worker returns no `play` block: rows stay `pending`. After
  deploy + 0007 the first Play upload inserts every qualifying key and totals converge.
- **D12 `play_progress_keys` — REVERSED 2026-10-21 [P1-5].** The privacy-security-reviewer's early pass on L3-1
  (`docs/play/trails/reviews/2026-10-21-privacy-l3-1.md`) escalated the table (place + sub-week window). Ruling: dropped
  from 0007 before any apply; `ch-parkhop` progress = distinct parks of one upload's new completions counted into
  `play_progress` (bounded overcount accepted). L3-2 applies it; no cron purge needed.