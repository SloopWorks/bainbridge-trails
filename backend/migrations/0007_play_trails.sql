-- Play trails v1.1 (docs/superpowers/plans/2026-10-21-play-trails-v1-1.md § 1 D2; docs/play/trails/brief.md § 2).
-- Strictly additive: one new table and one defaulted column; nothing 0004_cleanup.sql.pending drops or clears is
-- referenced, and the Worker still serving during the deploy keeps working (its play_totals upsert names no new column).
--
-- play_trail_paid: the server ledger of the six trails rules (W-CLIMB, W-STEEP, W-MILE, W-PARK1, W-PARKC, W-ISLAND),
-- one row per key paid, stored by value (`climb:<m>`, `high:<trailId>`, `mile:<mi>`, `park1:<park>`, `parkc:<park>`,
-- `island`) so a dataset update can neither double-pay nor claw back. Every row derives from coverage bits and sticky
-- completions the server already holds: never a time, a day, a position or a walk id.
-- play_totals.trail_marks: SUM(marks) of the ledger, folded into `total`.
-- No per-park key table (plan D12 reversed by the privacy review): ch-parkhop progress is a count in play_progress
-- (0006), the distinct Play parks of each upload's new completions; see src/play/challenges.ts.
-- Rollback: Worker revert is enough (old Workers never read these); the table and column are inert without it.
-- DELETE /v1/me deletes a user's rows (src/routes/me.ts), on top of ON DELETE CASCADE.

CREATE TABLE play_trail_paid (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL CHECK (dataset IN ('trails','roads')),
  key     TEXT NOT NULL,          -- rule key by value, e.g. "climb:250", "park1:Gazzam Lake Preserve", "island"
  marks   INTEGER NOT NULL,       -- what the key paid when it was first inserted (the rule's marks at that time)
  PRIMARY KEY (user_id, dataset, key)
) WITHOUT ROWID;

ALTER TABLE play_totals ADD COLUMN trail_marks INTEGER NOT NULL DEFAULT 0;
