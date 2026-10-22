# Simplification review (round 2) — whole branch

Date: 2026-10-21 · Role: simplification-reviewer · Target: `git diff 2aee859..bfdce9c` (source) · Verdict: **SHIP-AFTER-TWEAKS**.
Round-1 findings not reopened. "Do now" tweaks applied on the branch (backend tweak pass; Kotlin tweak pass); follow-ups recorded.

## Do now (applied)
1. `backend/src/play/trails.ts` `challengeTarget`: drop the dead `string[]` progress branch (union formula P1-A removed from Kotlin;
   climb adds a string count to dm) → `progress: number`, KDoc = the count formula.
2. `PlayClaimQueue.kt` comments: `PlaySettled.walk` and `PlayClaims.settle` now say trails rows settle via `WalkMarks.settle`.
3. `Deps.packs` / `PlayPacks` / `PACKS` seam + the test's `ITEMS`/`MISSING`/`skipIf`: dead since pack 2026.11.3 carries both
   challenges → removed (routes read `CURRENT` / `packByVersion` again, `ctx.current` stays).
4. `challenges.ts` `tablesOf` duplicates `trails.ts` → import.
5. `trails.ts` `kindOf("island:x")` returned `island` (Kotlin returns null) → exact `key === "island"`.
6. `TrailRewardFormat`: `climbProgress` returns `TrailValue` (the pair's second was unread); `distance(stepMi)` unused param;
   `climbSoFar` steps default to `CLIMB_STEPS_M`.
7. `TrailRewardFormat.mileLadder` one-line wrapper with a confusing twin in `TrailMilestonesLogic` → inlined.
8. `TrailMilestonesLogic.model(isRoads)` third roads guard → dropped.
9. The 30-key `TRAILS_V1_1` list duplicated in two tests → one shared source.
10. Stale comments (`LogCopy.kt` "v2 kinds have none yet", `Logbook.kt` v2 note, `TRAIL_ONLY` "(v2) W-PARKC") → reworded.

## Follow-up (not merge-blocking)
11. One `TrailSnapshot(tables, input)` type: `WalkMarks.TrailWalk` duplicates `PlayRepository.trailSnapshot()`; `MeScreen` makes two
    suspend reads that can straddle a coverage write.
12. Fold `WalkMarks.settle` into `PlayClaims.settle` (one arm) — deferred because `PlayClaimQueue.kt` is pinned unchanged by the
    privacy pass; do it with the next privacy-touching change and re-capture the golden.
13. `ChallengesLogic.trailOccurrences` builds a 13-field fake `Occurrence` for upcoming items → a `(start, end)` window parameter.
14. `challenges.ts` `uploadAmounts` re-implements Kotlin `TrailProgress.of` → `trailProgress()` in `trails.ts`.
15. Kotlin/TS naming drift (`featurePark` vs `parkOf`, `TrailRewardInput` vs `TrailInputs`, `qualifying`/`owed`/`inputs` vs
    `*Keys`/`trailInputs`, `KINDS` vs `TRAIL_KINDS`) → rename TS to the Kotlin/golden names.
16. One `PlayAgenda.withoutHidden(trail)` used by the chip, What's On and Challenges.

## Kept as is
`mileCases`, `startGap` in both validators, `TestEnv` expect/actual, `golden-writer.mjs`, `MAX_PAID` plumbing, `contributionOccurrence`
on ch-sweep, `updateIf`/generation/epoch, no placeholder arms left, `_v2` notes gone.
