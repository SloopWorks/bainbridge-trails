-- Bainbridge Trails backend schema. Times are epoch milliseconds.
-- Privacy: no GPS, no track times; coverage is per-segment tick bitsets only.

CREATE TABLE users (
  id               TEXT PRIMARY KEY,            -- random UUID; public (leaderboard / report-name)
  provider         TEXT NOT NULL,               -- 'google' | 'apple' | 'dev'
  sub              TEXT NOT NULL,               -- provider subject; never exposed
  display_name     TEXT,                        -- NULL = use anon_name
  anon_name        TEXT NOT NULL,
  name_suspended   INTEGER NOT NULL DEFAULT 0,  -- 1 = reported 3x; anon_name shown until admin review
  hidden           INTEGER NOT NULL DEFAULT 0,  -- admin-removed from leaderboard / stats (hidden_from_board)
  created_at       INTEGER NOT NULL,
  island_m         REAL NOT NULL DEFAULT 0,     -- covered metres of included segments
  covered_m        REAL NOT NULL DEFAULT 0,     -- covered metres of all counted segments
  trails_completed INTEGER NOT NULL DEFAULT 0,
  reached_at       INTEGER,                     -- when current metrics were reached (leaderboard tie-break)
  coverage_rev     INTEGER NOT NULL DEFAULT 0,  -- optimistic concurrency for coverage merges
  last_upload_at   INTEGER,                     -- last accepted coverage upload with growth
  burst_day        TEXT,                        -- UTC yyyy-mm-dd of burst_used_m
  burst_used_m     REAL NOT NULL DEFAULT 0,
  imported         INTEGER NOT NULL DEFAULT 0,  -- 1 once the one-off initial-import allowance is used
  UNIQUE (provider, sub)
);
CREATE INDEX users_rank ON users (hidden, island_m DESC, trails_completed DESC, reached_at ASC, id ASC);

CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,                  -- hex SHA-256 of the bearer token
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX sessions_user ON sessions (user_id);

CREATE TABLE coverage (
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  segment_id TEXT NOT NULL,
  bits       TEXT NOT NULL,                     -- base64, CoverageState encoding
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, segment_id)
) WITHOUT ROWID;

CREATE TABLE user_trails (
  user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  trail_id TEXT NOT NULL,
  fraction REAL NOT NULL,                       -- 0..1, only rows > 0 stored
  PRIMARY KEY (user_id, trail_id)
) WITHOUT ROWID;
CREATE INDEX user_trails_trail ON user_trails (trail_id, fraction);

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
