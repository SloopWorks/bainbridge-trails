-- Bainbridge Trails backend schema. Times are epoch milliseconds of the SERVER clock (sync/sign-in moments),
-- never walk times. Privacy: no GPS, no walk start/end/duration; coverage is per-segment tick bitsets;
-- walks are random ids only (dedup of plausibility credit).

CREATE TABLE users (
  id                  TEXT PRIMARY KEY,            -- random UUID; public (leaderboard / report-name)
  provider            TEXT NOT NULL,               -- 'google' | 'apple' | 'dev'
  sub                 TEXT NOT NULL,               -- provider subject; never exposed
  display_name        TEXT,                        -- NULL = use anon_name
  anon_name           TEXT NOT NULL,
  hidden              INTEGER NOT NULL DEFAULT 0,  -- admin: hidden_from_board (leaderboard + community stats)
  island_m            REAL NOT NULL DEFAULT 0,     -- covered metres of included segments
  island_rank         INTEGER NOT NULL DEFAULT 0,  -- floor(island_m * 1000 / island total): ranking key, 0.1 % units
  covered_m           REAL NOT NULL DEFAULT 0,     -- covered metres of all counted segments
  trails_completed    INTEGER NOT NULL DEFAULT 0,  -- size of the sticky completed set
  reached_at          INTEGER,                     -- when island_rank last rose (leaderboard tie-break)
  metrics_version     TEXT,                        -- dataset version the metrics were computed with
  coverage_rev        INTEGER NOT NULL DEFAULT 0,  -- optimistic concurrency for coverage merges
  backfilled          INTEGER NOT NULL DEFAULT 0,  -- 1 after the first accepted upload (larger walk cap before)
  walk_count          INTEGER NOT NULL DEFAULT 0,  -- stored walk ids (capped)
  last_walk_upload_at INTEGER,                     -- server time of the last upload that credited walks
  apple_refresh_token TEXT,                        -- only when Apple revoke credentials are configured
  apple_client_id     TEXT,
  UNIQUE (provider, sub)
);

CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,                     -- hex SHA-256 of the bearer token
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions (user_id);
CREATE INDEX sessions_expiry ON sessions (expires_at);

CREATE TABLE coverage (
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  segment_id TEXT NOT NULL,
  bits       TEXT NOT NULL,                        -- base64, CoverageState encoding
  PRIMARY KEY (user_id, segment_id)
) WITHOUT ROWID;

CREATE TABLE user_trails (
  user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  trail_id TEXT NOT NULL,
  fraction REAL NOT NULL,                          -- 0..1, only rows > 0 stored
  PRIMARY KEY (user_id, trail_id)
) WITHOUT ROWID;

-- Sticky completions (TL-7): union-only, survives dataset changes.
CREATE TABLE user_completed (
  user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  trail_id TEXT NOT NULL,
  PRIMARY KEY (user_id, trail_id)
) WITHOUT ROWID;

-- Walk ids already credited for plausibility (TL-2). Id only: no times, no durations.
CREATE TABLE walks (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  walk_id TEXT NOT NULL,
  PRIMARY KEY (user_id, walk_id)
) WITHOUT ROWID;

CREATE TABLE name_reports (
  reporter_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  INTEGER NOT NULL,                    -- server time of the report (admin triage)
  PRIMARY KEY (reporter_id, target_id)
) WITHOUT ROWID;
CREATE INDEX name_reports_target ON name_reports (target_id);

-- Fixed-window counters. IP keys are SHA-256(daily salt + IP); purged by the cron after 2 days.
CREATE TABLE rate_limits (
  key          TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL
) WITHOUT ROWID;

-- Random per-UTC-day salt for hashing IPs; old days are deleted by the cron (unlinkable afterwards).
CREATE TABLE daily_salt (
  day  TEXT PRIMARY KEY,                           -- yyyy-mm-dd
  salt TEXT NOT NULL
) WITHOUT ROWID;

-- Daily snapshots (TL-11): what others see. Rebuilt by the cron trigger / POST /v1/admin/snapshot.
CREATE TABLE leaderboard_snapshot (
  user_id          TEXT PRIMARY KEY,
  ord              INTEGER NOT NULL,
  display_name     TEXT NOT NULL,                  -- public name; updated live on rename / reset
  island_rank      INTEGER NOT NULL,
  trails_completed INTEGER NOT NULL,
  reached_at       INTEGER NOT NULL
);
CREATE INDEX leaderboard_snapshot_ord ON leaderboard_snapshot (ord);

CREATE TABLE trail_stats_snapshot (
  trail_id        TEXT PRIMARY KEY,
  walkers         INTEGER NOT NULL,
  completed       INTEGER NOT NULL,
  median_fraction REAL
) WITHOUT ROWID;

CREATE TABLE snapshot_meta (
  id              INTEGER PRIMARY KEY CHECK (id = 1),
  snap_id         TEXT NOT NULL,                   -- random; changes on every rebuild or snapshot edit (cache key)
  generated_at    INTEGER NOT NULL,
  dataset_version TEXT NOT NULL,
  total_ranked    INTEGER NOT NULL
);
