# Privacy + security review — final pass, play/trails-v1-1 @ 063539d

Date: 2026-10-21 · Role: privacy-security-reviewer · Verdict: **code SHIP; ESCALATE-TO-OPERATOR on the published policy text**.
The gate sits on the owner-only Worker deploy (`npm run deploy` → `migrate:remote` + `wrangler deploy`), not on the merge; CI has
no deploy step. Merging is safe.

## Earlier fixes confirmed in the final tree
P2-1 (`/me` 2 MiB cap; `accountPaid` kind/suffix/length ≤ 96/≤ 1,000, trails only), P2-2 (`PlayStateStore.generation` + `updateIf`,
`accountEpoch`, `signedIn()` re-checked inside the update), P3-2 (`forgetAccount` on sign-out and wipe), P3-3 (class-name-only
logging), P2-3 (`ApiDtos.kt` source span pinned), P3-1 (`RoadsGuardTest` refuses capture under `CI`). Fresh runs: ApiClientTest 14,
UploadContractTest 4, PlayRestoreTest 14, PlayStatePrivacyTest 6, RoadsGuardTest 2, SyncRepositoryTest 21 — 0 failures; backend
266/266.

## After the second pass
UI stages: no new log line, no persisted state, no network call; park/trail names only in on-device semantics (public dataset
names already on screen). Backend tweaks: test seam removed, `challengeTarget` count-only, `kindOf` stricter; `/v1/play/me`
unchanged (`requireUser`, user filter, 60/10 min). Roads/PF0: no `paid` on roads, Me strip gated, chip gets an empty map, server
`tablesOf` null; byte-identity and upload schema tests pass. Working tree: the e2e script keeps the dev token local.

## Findings
- **P2-A (owner)** the next Worker deploy would publish the OD-19 *draft* clause (already rendered into `legal-pages.ts`) without an
  effective-date bump → sign off / edit, bump the date, drop the draft comment, `npm run build:legal`, before any deploy.
- **P3-A** policy delete bullet lacked "trail rewards" → draft wording added on the branch (still OD-19).
- **P3-B** `kindOf` prototype lookup (`constructor:x` → `Object`) — hardening only → `Object.hasOwn`, tests added on the branch.
- **P3-C** policy says "week" but weekend challenges are keyed by their Saturday (v1 `ch-sweep` already) → draft wording "week (or,
  for a weekend challenge, the weekend)" added on the branch (still OD-19).
- **P2-4 (owner)** `WalkDone.endedAt` epoch-ms for 15 days in the no-backup file (round-1 fix) → coarsen to the island date or
  record the exception (PD-22 + hub).

## Location-sharing posture (PR text)
When you're signed in with Play on, our server learns only what it already worked out from the trail sections you've covered:
which trail rewards you've earned (like a park's first visit or a climb step) and which challenge windows you counted toward,
never your route, GPS points or the time you walked. Other people see only rounded group counts with no names; your GPS track,
walk times and the per-walk details behind each reward stay on your phone.

## Owner checklist
1. OD-19: sign off / edit the trail-rewards clause (+ P3-A, P3-C wording), bump the effective date, `npm run build:legal`, commit.
2. Migration 0007 `migrate:remote` (also run by `npm run deploy`) — after item 1.
3. Compliance hub row (`sloopworks/compliance/data-privacy.md`): `play_trail_paid`, account lifetime, OD-21 status.
4. P2-4 decision.
