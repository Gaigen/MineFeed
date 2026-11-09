-- MineFeed v2: moderation — report queue, temporary bans, moderation log.
ALTER TABLE accounts ADD COLUMN ban_until INTEGER;   -- with banned = 1: end of the ban (ms), NULL = permanent
ALTER TABLE accounts ADD COLUMN ban_reason TEXT;

CREATE TABLE reports (
  id           TEXT PRIMARY KEY,
  post_id      TEXT NOT NULL,
  comment_id   TEXT,                        -- NULL = the post itself
  space        TEXT NOT NULL,
  target_id    TEXT,                        -- reported author's account (NULL for Discord-only authors)
  reporter_id  TEXT NOT NULL,
  reason       TEXT NOT NULL,               -- spam | abuse | nsfw | scam | other
  details      TEXT NOT NULL DEFAULT '',
  status       TEXT NOT NULL DEFAULT 'open', -- open | done | dismissed
  action       TEXT,                        -- deleted | dismissed | banned | kicked | gone
  resolved_by  TEXT,
  resolved_at  INTEGER,
  created_at   INTEGER NOT NULL
);
CREATE INDEX reports_open ON reports (status, space, created_at);
CREATE INDEX reports_target ON reports (post_id, comment_id);

CREATE TABLE mod_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  actor_id    TEXT NOT NULL,
  action      TEXT NOT NULL,                -- delete_post | delete_comment | dismiss | kick | ban | unban
  target_id   TEXT,
  post_id     TEXT,
  comment_id  TEXT,
  space       TEXT,
  reason      TEXT,
  created_at  INTEGER NOT NULL
);
CREATE INDEX mod_log_time ON mod_log (created_at);
