-- MineFeed v2: editing posts/comments; reports keep the text as it was when reported.
ALTER TABLE posts ADD COLUMN edited_at INTEGER;
ALTER TABLE comments ADD COLUMN edited_at INTEGER;
ALTER TABLE reports ADD COLUMN snapshot TEXT;

-- polls: a post may carry 2-4 options; one vote per player (can be changed)
CREATE TABLE polls (
  post_id  TEXT PRIMARY KEY,
  options  TEXT NOT NULL                -- JSON array of option texts
);
CREATE TABLE poll_votes (
  post_id     TEXT NOT NULL,
  account_id  TEXT NOT NULL,
  option      INTEGER NOT NULL,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (post_id, account_id)
);
CREATE INDEX poll_votes_post ON poll_votes (post_id, option);

-- pinned posts: posts.pinned holds the pin time (0 = not pinned)
CREATE INDEX idx_posts_pinned ON posts (space, pinned) WHERE pinned > 0 AND deleted = 0;
