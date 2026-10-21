-- Play challenges (docs/play/schedule.md § 7.3, § 7.4; product § 4.5; decisions PD-3, PD-4). Additive: one new table.
-- Per user and challenge occurrence, a count of contributions only: `ch-sweep@<Saturday>` = trails/roads newly
-- completed by Play uploads received inside [window start, window end + 2 days]; `ch-krakenweek@<Thursday>` = kraken
-- stretches this user repelled in that attack. Never a time, a day, a position or a walk id. Public results are
-- `{band, count}` aggregates of it (counts below 5 hidden). The 0005 tables could not hold it: a challenge window
-- (Sat 00:00 → Wed 00:00) is not a week id, and play_claims rows are returned to the client by GET /v1/play/me.
-- Rollback: Worker revert is enough (old Workers never read it); the table is inert without it.
-- DELETE /v1/me deletes a user's rows (src/routes/me.ts), on top of ON DELETE CASCADE; the daily cron purges rows
-- of occurrences older than 180 days.

CREATE TABLE play_progress (
  user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset  TEXT NOT NULL CHECK (dataset IN ('trails','roads')),
  occ      TEXT NOT NULL,          -- challenge occurrence key "<challengeId>@<yyyy-mm-dd>"
  progress INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, dataset, occ)
) WITHOUT ROWID;
CREATE INDEX play_progress_occ ON play_progress (dataset, occ, progress);
