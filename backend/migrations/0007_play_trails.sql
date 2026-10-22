-- Play trails v1.1 (docs/superpowers/plans/2026-10-21-play-trails-v1-1.md § 1 D2, D12; docs/play/trails/brief.md § 2).
-- Strictly additive: two new tables and one defaulted column; nothing 0004_cleanup.sql.pending drops or clears is
-- referenced, and the Worker still serving during the deploy keeps working (its play_totals upsert names no new column).
--
-- play_trail_paid: the server ledger of the six trails rules (W-CLIMB, W-STEEP, W-MILE, W-PARK1, W-PARKC, W-ISLAND),
-- one row per key paid, stored by value (`climb:<m>`, `high:<trailId>`, `mile:<mi>`, `park1:<park>`, `parkc:<park>`,
-- `island`) so a dataset update can neither double-pay nor claw back. Every row derives from coverage bits and sticky
-- completions the server already holds: never a time, a day, a position or a walk id.
-- play_totals.trail_marks: SUM(marks) of the ledger, folded into `total`.
-- play_progress_keys: per challenge occurrence, the distinct keys (park names) a user contributed (ch-parkhop, D12);
-- coarser than the occurrence keys play_claims already keeps; purged by the daily cron once the occurrence's grace
-- ends; deleted with the account.
-- Rollback: Worker revert is enough (old Workers never read these); the tables and column are inert without it.
-- DELETE /v1/me deletes a user's rows (src/routes/me.ts), on top of ON DELETE CASCADE.

CREATE TABLE play_trail_paid (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL CHECK (dataset IN ('trails','roads')),
  key     TEXT NOT NULL,          -- rule key by value, e.g. "climb:250", "park1:Gazzam Lake Preserve", "island"
  marks   INTEGER NOT NULL,       -- what the key paid when it was first inserted (the rule's marks at that time)
  PRIMARY KEY (user_id, dataset, key)
) WITHOUT ROWID;

ALTER TABLE play_totals ADD COLUMN trail_marks INTEGER NOT NULL DEFAULT 0;

CREATE TABLE play_progress_keys (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL CHECK (dataset IN ('trails','roads')),
  occ     TEXT NOT NULL,          -- challenge occurrence key "<challengeId>@<yyyy-mm-dd>"
  key     TEXT NOT NULL,          -- contribution key (ch-parkhop: a Play park name)
  PRIMARY KEY (user_id, dataset, occ, key)
) WITHOUT ROWID;
