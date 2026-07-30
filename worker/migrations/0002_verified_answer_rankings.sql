-- v2 rankings are derived only from answers that the Weibian Worker validates
-- against an exact allowlisted content bundle. The v1 progress snapshots remain
-- untouched as rollback evidence and are never read by the v2 leaderboard.
CREATE TABLE IF NOT EXISTS weibian_answer_events_v2 (
  event_id TEXT PRIMARY KEY,
  user_key TEXT NOT NULL,
  canonical_task_id TEXT NOT NULL,
  chapter_id INTEGER NOT NULL CHECK (chapter_id BETWEEN 1 AND 541),
  content_version TEXT NOT NULL,
  task_semantic_digest TEXT NOT NULL
    CHECK (length(task_semantic_digest) = 64),
  selected_option TEXT NOT NULL,
  correct INTEGER NOT NULL CHECK (correct IN (0, 1)),
  points INTEGER NOT NULL CHECK (points IN (0, 1)),
  received_at_ms INTEGER NOT NULL,
  beijing_day TEXT NOT NULL CHECK (length(beijing_day) = 10),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_key, canonical_task_id)
);

CREATE INDEX IF NOT EXISTS idx_weibian_answer_rankings_total
  ON weibian_answer_events_v2(
    user_key,
    points,
    received_at_ms
  );

CREATE INDEX IF NOT EXISTS idx_weibian_answer_rankings_daily
  ON weibian_answer_events_v2(
    beijing_day,
    user_key,
    points,
    received_at_ms
  );
