# privacy-security-reviewer: client sync + state (d8652f6, c496299, afe382a)

VERDICT: **FIX-FIRST** — the wire payload is clean; two on-device promises are missed.

- **P0-1 Wipe keeps the Logbook.** `AppGraph.wipeLocalProgress()` (AppGraph.kt:305-310) clears `playClaims` only;
  `play/<goal>-state.json` survives and `PlayStateStore` has no `clear()` (in-memory `_state` would rewrite it).
  product.md §5.5 promises "Also delete … on this phone clears the local logbook too". Fix: `PlayStateStore.clear()`
  (mutex, `_state = PlayState()`, delete file); call from the wipe path even with Play off (delete `play/*-state.json`
  directly when no repo exists); clear `PlayRepository._claimResults`; delete the claim queue for both goals'
  store names. Proof: `PlayWipeTest`.
- **P1-2 State file is backed up.** `appDataDir(context)+"/play"` is `filesDir` (Android backup rules exclude only
  profile/sync/leaderboard) and Application Support on iOS (no exclude-from-backup). It holds every `LogEntry.walkId`
  (= local session id `s<epochMs>-hex` → exact start time of every Play walk, contradicting "never a time of day"),
  island dates with memorial/cemetery stamps, and restored `occ` keys. Fix: move the state file under
  `privateNoBackupDir(context)+"/play"` (content/tide caches may stay); optional opaque per-walk id instead of the
  session id in `LogEntry.walkId`. Proof: path test + backup-rules test.
- **P2-3** Occurrence keys carry the occurrence day/window (allowed by contract; qualify "no days" wording with OD-21).
- **P2-4** No body size cap on content/tides fetch (cap ~2 MB → rejected, last good kept; test) and the claim queue
  keeps walk-less elements forever (bound by age/count).

Checked clean: payload fields `{id, kind, weekId, band}` + `dayKey` = week id + hints; byte-identical body when off;
sensing in memory only; claim queue in no-backup dir, idempotent, cleared on wipe; debug overrides gated on
debuggable/debug binary; analytics only while on, no offer events, release NoOp; content fetch unauthenticated with
ETag, invalid pack never cached; restore cannot raise totals; Finalizer hook isolated and lock-free.
Posture (hub): on-device Logbook retention + memorial stamps; backup exclusion; occurrence-key wording with OD-21.
