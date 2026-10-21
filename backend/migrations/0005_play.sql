-- Play (opt-in gamification): claims and server-derived marks (docs/play/schedule.md § 7.2, decisions PD-3, PD-11).
-- Strictly additive: new tables only, nothing that 0004_cleanup.sql.pending drops or clears is referenced, so this
-- applies correctly whether 0004 is applied before or after it (B0 ruling: 0004 stays pending).
-- Stores occurrence keys, week ids (the Monday date) and marks only: never a day, a time, a position or a walk id.
-- Rollback: Worker revert is enough (old Workers never read these tables); the tables are inert without it.
-- DELETE /v1/me deletes every row of a user (src/routes/me.ts), on top of ON DELETE CASCADE.

CREATE TABLE play_claims (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL CHECK (dataset IN ('trails','roads')),
  occ     TEXT NOT NULL,          -- occurrence key "<itemId>@<yyyy-mm-dd>[#n]" (immutable item ids)
  kind    TEXT NOT NULL,          -- treasure | egg | riddle | ink | lore | challenge
  week    TEXT NOT NULL,          -- week id = Monday date; never a day
  config  TEXT NOT NULL,          -- pack version the claim was evaluated against
  marks   INTEGER NOT NULL,       -- derived by the server from the content table
  PRIMARY KEY (user_id, dataset, occ)
) WITHOUT ROWID;
CREATE INDEX play_claims_week ON play_claims (user_id, dataset, week);

CREATE TABLE play_weeks (          -- W-DAY / W-WEEK inputs and the weekly found cap
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL, week TEXT NOT NULL,
  day_keys INTEGER NOT NULL DEFAULT 0, found_marks INTEGER NOT NULL DEFAULT 0, kept INTEGER,
  PRIMARY KEY (user_id, dataset, week)
) WITHOUT ROWID;

CREATE TABLE play_totals (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL,
  cov_marks INTEGER NOT NULL DEFAULT 0,   -- max(previous, new): monotone W-COV
  derived_marks INTEGER NOT NULL DEFAULT 0, found_marks INTEGER NOT NULL DEFAULT 0,
  week_marks INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, dataset)
) WITHOUT ROWID;

CREATE TABLE play_attack_stretches ( -- island aggregate for F-RETREAT / ch-krakenweek; counts only
  dataset TEXT NOT NULL, occ TEXT NOT NULL, repelled_by INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (dataset, occ)
) WITHOUT ROWID;
