-- Bainbridge Trails backend schema. Times are epoch milliseconds (server clock only).
-- Privacy: no GPS, no walk times; coverage is per-segment tick bitsets; walks are random ids + durations.

CREATE TABLE users (
  id                  TEXT PRIMARY KEY,            -- random UUID; public (leaderboard / report-name)
  provider            TEXT NOT NULL,               -- 'google' | 'apple' | 'dev'
  sub                 TEXT NOT NULL,               -- provider subject; never exposed
  display_name        TEXT,                        -- NULL = use anon_name
  anon_name           TEXT NOT NULL,
  hidden              INTEGER NOT NULL DEFAULT 0,  -- admin: hidden_from_board (leaderboard + community stats)
  created_at          INTEGER NOT NULL,
  island_m            REAL NOT NULL DEFAULT 0,     -- covered metres of included segments
  covered_m           REAL NOT NULL DEFAULT 0,     -- covered metres of all counted segments
  trails_completed    INTEGER NOT NULL DEFAULT 0,  -- size of the sticky completed set
  reached_at          INTEGER,                     -- when island_m last increased (leaderboard tie-break)
  coverage_rev        INTEGER NOT NULL DEFAULT 0,  -- optimistic concurrency for coverage merges
  apple_refresh_token TEXT,                        -- only when Apple revoke credentials are configured
  apple_client_id     TEXT,
  UNIQUE (provider, sub)
);

CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,                     -- hex SHA-256 of the bearer token
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions (user_id);

CREATE TABLE coverage (
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  segment_id TEXT NOT NULL,
  bits       TEXT NOT NULL,                        -- base64, CoverageState encoding
  updated_at INTEGER NOT NULL,
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

-- Walk ids already credited for plausibility (TL-2). No timestamps.
CREATE TABLE walks (
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  walk_id    TEXT NOT NULL,
  duration_s INTEGER NOT NULL,
  PRIMARY KEY (user_id, walk_id)
) WITHOUT ROWID;

CREATE TABLE name_reports (
  reporter_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (reporter_id, target_id)
) WITHOUT ROWID;
CREATE INDEX name_reports_target ON name_reports (target_id);

CREATE TABLE rate_limits (
  key          TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL
) WITHOUT ROWID;

-- Daily snapshots (TL-11): what others see. Rebuilt by the cron trigger / POST /v1/admin/snapshot.
CREATE TABLE leaderboard_snapshot (
  user_id          TEXT PRIMARY KEY,               -- names/hidden/deletion are joined live from users
  ord              INTEGER NOT NULL,
  island_m         REAL NOT NULL,
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
  id           INTEGER PRIMARY KEY CHECK (id = 1),
  generated_at INTEGER NOT NULL
);
