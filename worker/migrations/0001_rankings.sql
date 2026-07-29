CREATE TABLE IF NOT EXISTS weibian_ranking_snapshots (
  user_key TEXT PRIMARY KEY,
  public_name TEXT NOT NULL,
  total_points INTEGER NOT NULL DEFAULT 0
    CHECK (total_points BETWEEN 0 AND 51200),
  daily_points INTEGER NOT NULL DEFAULT 0
    CHECK (daily_points BETWEEN 0 AND 51200),
  completed_chapters INTEGER NOT NULL DEFAULT 0
    CHECK (completed_chapters BETWEEN 0 AND 512),
  active_chapters INTEGER NOT NULL DEFAULT 0
    CHECK (active_chapters BETWEEN 0 AND 512),
  day_key TEXT NOT NULL,
  source_updated_at TEXT NOT NULL DEFAULT '',
  synced_at_ms INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_weibian_rankings_total
  ON weibian_ranking_snapshots(
    total_points DESC,
    completed_chapters DESC,
    updated_at ASC
  );

CREATE INDEX IF NOT EXISTS idx_weibian_rankings_daily
  ON weibian_ranking_snapshots(
    day_key,
    daily_points DESC,
    total_points DESC,
    updated_at ASC
  );
