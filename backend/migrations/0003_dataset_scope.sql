-- RD-7: per-dataset scoping. One account can play two goals: `trails` (every trail) and `roads` (every public
-- road). Coverage rows stay keyed by segment id alone (the prefixes `seg-` / `rseg-` never overlap and uploads are
-- checked per dataset); everything aggregated moves to (user, dataset).

-- Per-(user, dataset) metrics and sync state. Replaces the metric/sync columns of `users`, which stay in place
-- (no DROP COLUMN on a live table) but are no longer read or written.
CREATE TABLE user_metrics (
  user_id             TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset             TEXT NOT NULL CHECK (dataset IN ('trails', 'roads')),
  island_m            REAL NOT NULL DEFAULT 0,     -- covered metres of included segments
  island_rank         INTEGER NOT NULL DEFAULT 0,  -- floor(island_m * 1000 / island total): ranking key, 0.1 % units
  covered_m           REAL NOT NULL DEFAULT 0,     -- covered metres of all counted segments
  trails_completed    INTEGER NOT NULL DEFAULT 0,  -- size of the sticky completed set (trails or roads)
  reached_at          INTEGER,                     -- when island_rank last rose (leaderboard tie-break)
  metrics_version     TEXT,                        -- dataset version the metrics were computed with
  coverage_rev        INTEGER NOT NULL DEFAULT 0,  -- optimistic concurrency for coverage merges
  backfilled          INTEGER NOT NULL DEFAULT 0,  -- 1 after the first accepted upload (larger walk cap before)
  walk_count          INTEGER NOT NULL DEFAULT 0,  -- stored walk ids (capped)
  last_walk_upload_at INTEGER,                     -- server time of the last upload that credited walks
  PRIMARY KEY (user_id, dataset)
) WITHOUT ROWID;
CREATE INDEX user_metrics_stale ON user_metrics (dataset, metrics_version);

INSERT INTO user_metrics (user_id, dataset, island_m, island_rank, covered_m, trails_completed, reached_at, metrics_version,
                          coverage_rev, backfilled, walk_count, last_walk_upload_at)
-- metrics_version NULL: every backfilled row is recomputed from `coverage` on first read / the next cron, which
-- also picks up any upload the previous Worker accepted (into `users`) between this migration and the deploy.
SELECT id, 'trails', island_m, island_rank, covered_m, trails_completed, reached_at, NULL,
       coverage_rev, backfilled, walk_count, last_walk_upload_at
FROM users;

-- Fractions and sticky completions: ids are prefix-distinct (`trail-` / `road-`) but recompute deletes per
-- dataset, so the rows carry it.
ALTER TABLE user_trails ADD COLUMN dataset TEXT NOT NULL DEFAULT 'trails';
ALTER TABLE user_completed ADD COLUMN dataset TEXT NOT NULL DEFAULT 'trails';

-- Walk ids are credited per dataset (each app keeps its own walks).
CREATE TABLE walks_v3 (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL DEFAULT 'trails',         -- default: the previous Worker still inserts (user_id, walk_id)
  walk_id TEXT NOT NULL,
  PRIMARY KEY (user_id, dataset, walk_id)
) WITHOUT ROWID;
INSERT INTO walks_v3 (user_id, dataset, walk_id) SELECT user_id, 'trails', walk_id FROM walks;
DROP TABLE walks;
ALTER TABLE walks_v3 RENAME TO walks;

-- Snapshots are derived data (rebuilt lazily when a dataset has no snapshot_meta row): recreate them per dataset.
-- Not backward compatible: between `migrate:remote` and the Worker upload (seconds) the previous Worker's
-- leaderboard / stats reads fail with 500; clients retry. Uploads keep working (see walks / user_metrics above).
DROP TABLE leaderboard_snapshot;
DROP TABLE trail_stats_snapshot;
DROP TABLE snapshot_meta;

CREATE TABLE leaderboard_snapshot (
  dataset          TEXT NOT NULL,
  user_id          TEXT NOT NULL,
  ord              INTEGER NOT NULL,
  display_name     TEXT NOT NULL,                  -- public name; updated live on rename / reset
  island_rank      INTEGER NOT NULL,
  trails_completed INTEGER NOT NULL,
  reached_at       INTEGER NOT NULL,
  PRIMARY KEY (dataset, user_id)
) WITHOUT ROWID;
CREATE INDEX leaderboard_snapshot_ord ON leaderboard_snapshot (dataset, ord);
CREATE INDEX leaderboard_snapshot_user ON leaderboard_snapshot (user_id);

CREATE TABLE trail_stats_snapshot (
  dataset         TEXT NOT NULL,
  trail_id        TEXT NOT NULL,                   -- trail or road id
  walkers         INTEGER NOT NULL,
  completed       INTEGER NOT NULL,
  median_fraction REAL,
  PRIMARY KEY (dataset, trail_id)
) WITHOUT ROWID;

CREATE TABLE snapshot_meta (
  dataset         TEXT PRIMARY KEY,
  snap_id         TEXT NOT NULL,                   -- random; changes on every rebuild or snapshot edit (cache key)
  generated_at    INTEGER NOT NULL,
  dataset_version TEXT NOT NULL,
  total_ranked    INTEGER NOT NULL
) WITHOUT ROWID;
