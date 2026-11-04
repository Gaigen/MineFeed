-- MineFeed v2: feed. Discord forum threads hold the content; D1 is the index the mod reads.

-- a "space" is one forum: the global feed or a private community
CREATE TABLE spaces (
  id            TEXT PRIMARY KEY,          -- 'global' or community id
  forum_id      TEXT NOT NULL UNIQUE,      -- Discord forum channel
  webhook_id    TEXT,                      -- webhook that posts as players
  webhook_token TEXT,
  tags          TEXT                       -- JSON {rip,help,gg,talk,solved} -> forum tag id
);

CREATE TABLE posts (
  id         TEXT PRIMARY KEY,             -- Discord thread id (= starter message id)
  space      TEXT NOT NULL,
  author_id  TEXT,                         -- MineFeed account; NULL = written in Discord
  ext_name   TEXT,                         -- Discord display name when author_id is NULL
  type       TEXT NOT NULL,                -- rip | help | gg | talk
  text       TEXT NOT NULL,
  image      TEXT,                         -- JSON, reserved for pictures
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,             -- bumped on every change; drives /v2/sync
  comments   INTEGER NOT NULL DEFAULT 0,
  re0        INTEGER NOT NULL DEFAULT 0,   -- first reaction of the post type
  re1        INTEGER NOT NULL DEFAULT 0,   -- second reaction
  solved     INTEGER NOT NULL DEFAULT 0,
  deleted    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_posts_feed ON posts(space, created_at, id) WHERE deleted = 0;
CREATE INDEX idx_posts_type ON posts(space, type, created_at, id) WHERE deleted = 0;
CREATE INDEX idx_posts_author ON posts(author_id, created_at, id) WHERE deleted = 0;
CREATE INDEX idx_posts_updated ON posts(space, updated_at);

CREATE TABLE comments (
  id         TEXT PRIMARY KEY,             -- Discord message id
  post_id    TEXT NOT NULL,
  author_id  TEXT,
  ext_name   TEXT,
  text       TEXT NOT NULL,
  image      TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_comments_post ON comments(post_id, created_at, id) WHERE deleted = 0;
CREATE INDEX idx_comments_updated ON comments(post_id, updated_at);

-- one reaction per player per post
CREATE TABLE reactions (
  post_id    TEXT NOT NULL,
  account_id TEXT NOT NULL,
  slot       INTEGER NOT NULL,             -- 0 | 1
  created_at INTEGER NOT NULL,
  PRIMARY KEY (post_id, account_id)
) WITHOUT ROWID;

CREATE TABLE notifications (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id TEXT NOT NULL,                -- who receives it
  kind       TEXT NOT NULL,                -- comment | mention | react
  post_id    TEXT,
  comment_id TEXT,
  actor_id   TEXT,
  actor_name TEXT,                         -- nick (or Discord name) at the time
  count      INTEGER NOT NULL DEFAULT 1,   -- reactions are folded into one row per post
  text       TEXT,                         -- short quote
  group_key  TEXT UNIQUE,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX idx_notifications_account ON notifications(account_id, updated_at);

ALTER TABLE accounts ADD COLUMN notifs_seen_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN moderator INTEGER NOT NULL DEFAULT 0;
