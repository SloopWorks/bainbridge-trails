# Adversarial code review — final branch pass, play/trails-v1-1 @ 063539d

Date: 2026-10-21 · Role: adversarial-reviewer · Target: `git diff 2aee859..063539d` (source) · Verdict: **SHIP** (no P0/P1; four P2,
applied in the last fix pass on the branch).

Gates run on the final tree: `RoadsGuardTest` 2/2, `PlayOffNothingCreatedTest` 2/2, `PlayOffRequestsTest` 1/1, `PlayOfferTest` 6/6,
`UploadContractTest` 4/4 (0 failures); backend `play-roads-guard` 2/2.

## P2 findings (fixed)
- **P2-1 (PLAUSIBLE)** `ChallengesLogic` mixed server progress with the local target → "Done" shown while nothing is claimed after a
  late-synced walk crosses a window boundary → progress and target taken from the same side (dto pair, else local status pair,
  else pack default).
- **P2-2 (CONFIRMED)** `TrailChallengeStatus.reached` unused; a card never reads `found` → after a reinstall restore a claimed
  challenge shows Active/unclaimed (display only; the `found` guard blocks a second claim) → `done = occ in found || progress ≥ target`.
- **P2-3 (CONFIRMED, tampered/corrupt state only)** `trailValue` accepts any positive step from `contentId`; `whole()` wraps on
  overflow → accept only ladder steps (`CLIMB_STEPS_M` / `MILE_STEPS`), else keep the persisted what; clamp `whole()`.
- **P2-4 (CONFIRMED, harmless)** the `hasPending` probe in `recordWalkMarks` runs `next()` outside the update and sets
  `trailStartDeferred` as a side effect → set the flag only inside the update.

## Prior fixes verified IN
Round-1 code P1-A, P1-B, P2-A, P2-B (P2-C not re-derived); privacy P2-1, P2-2, P2-3, P3-1..3; UI medallion cap, chip hold, key in
guard sets; simplification 1–10.

## Checked clean
UI formatting (floor/round/clamp; no locale parse-back; plurals with strings; `contentId` rejects ≤ 0/blank/non-numeric/other kinds;
collapse ≤ 8 → 7 + more; Logbook order; strip always 4; sheet counts from tables — 19 parks all with a non-quiet member, 10 high
ground, climb top 1,250; park names incl. straight apostrophes byte-identical in `dataset.bin`; reduced motion; formatting off
composition; no recomposition storm). Integration (`WalkMarks.settle` on every production settle; TrailStart inside the walk update;
deferred start → restore → `creditTrails(generation)` safe across wipe/sign-out; restorable total algebra; Finalizer merge order).
Roads/PF0 null-safe everywhere; no double pay (Logbook dedupe, ledger, server ON CONFLICT + SUM in one statement).
Read less closely: backend internals beyond the diff, `build-play-content.mjs`, goldens, `LogKindCompatTest`.
