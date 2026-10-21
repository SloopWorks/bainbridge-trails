# simplification-reviewer: branch gamification/v1 (round 2, HEAD 335fe22)

VERDICT: **SHIP-AFTER-TWEAKS** (do 1, 2, 3, 5 before merge; the rest are small)

1. Two `GeoPoint` types with opposite argument order (`play/fx/FxModel.kt:6` lon,lat vs `play/schedule/PlaceIndex.kt:7`
   lat,lon; plus `LatLon` in PlayConfig.kt:231); all positional call sites → one `GeoPoint(lat, lon)` in `play.geo` with named
   args (or at least rename the fx one `LonLat`).
2. `FxRenderer` overload pairs (`writeGlobalState` ± `view`, `Layers` ± `source`) with forwarders → keep only the full
   signatures; mechanical edit in 13 files.
3. Third sun/recurrence implementation in `scripts/build-play-content.mjs:620-850` duplicating `backend/src/play/recurrence.ts`
   → import the TS evaluation (Node 22 type stripping / tsx) or add a date-level evaluation parity test.
4. `derived-<goal>.json` parsed five ways (`PlayDerived.parse` ×2 in AppGraph, `DatasetPlaceIndex`, `DerivedGoal.decode`,
   `SnapshotMarks.of`) → parse once into `PlayDerived` and pass it along.
5. Logbook copy as English literals in six places (`PlayClaims.why`/`DAY_*`, `SERVER_COPY`/`RESTORED_*`, WalkMarks
   constants, StartBalance, RiddleLogic) bypassing GoalVocabulary → one `LogCopy` table keyed by `LogKind` now; better, store
   kind + contentId and resolve at render via resources.
6. `PlayGatedHook`/`playHook` single-implementation indirection → delete; `gatedHook` calls `claimWalk`.
7. Dead/over-exposed `PlayRepository` members: `creditStartBalance()` (no callers), `recordWalkMarks` (make private),
   `reload()` (= refresh), `me()` (verify, delete).
8. v2 `salt` always null (`PlayStateStore.kt:48`; `salt ?: 0uL` at PlayRepository 313/382) → drop.
9. Solo challenges need a second evaluator call + manual merge (`PlayRepository.kt:386-393`; `WalkDone` vs `WalkCompletions`)
   → `evaluate(..., completedThisWalk, earlier)` returns challenges in `ClaimResult`; one `WalkDone` type in :play.
10. Debug Play clock UI exists twice (`DebugPlugins.kt PlayClockPlugin` vs `PlayClockDebugScreen.kt`; `PlayFxPlugin` stub says
    the showcase "arrives with L2-4/L2-5") → shared `PlayClockDebugBody`; delete or wire `PlayFxPlugin`; move
    `agendaDump`/`benchmark` to a debug-only extension.
11. Duplicate test helper: `PlayStringsXml.kt` vs `EnglishPlayStrings.kt` → keep `PlayStringsXml`.
12. Template filling hand-rolled (`ChallengesLogic`, `PlayNearbyStrip`, `LogbookLogic`, `PlayFormat`) vs `PlayText.fmt` →
    one `PlayFormat.fill`.
13. `PlayRepository.ferry()` JSON round-trip → `FerryTimetable.of(config.ferry)`.
14. Backend caps/`addDays` declared twice (`claims.ts:85` vs `challenges.ts:45-62`) → one `capsOf` + `addDays`.
15. FX showcase options on `SpikeLaunch` (optional `FxLaunch`); DebugLaunch KDoc still says "MapFx spike".

Keep as is: pure `:play` with Gated* adapters; Kotlin+TS sun/recurrence pair with the golden; `PlayClaimQueue` settle
matrix; `cappedKeys` probe; `FxRendererRegistry`/`FxKindSources`; `PlayGate`; opaque walk ids; CI fixture/goldens; the
`*Logic.kt` seams. Not reviewed (turn limit): PlayScheduler internals, validator pair, PlayContentRepository, FxHost/FxLayers
lifecycle, ui/play screens, MapTab wiring, backend bodies, unused strings/DTO fields, iOS.
