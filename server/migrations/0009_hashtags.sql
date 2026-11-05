-- MineFeed v2: #hashtags in posts. One row per (post, tag); tags are stored normalized (lower case, ё -> е).
CREATE TABLE post_tags (
  tag         TEXT NOT NULL,
  post_id     TEXT NOT NULL,
  space       TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (tag, post_id)
);
CREATE INDEX post_tags_space ON post_tags (space, created_at);
CREATE INDEX post_tags_post ON post_tags (post_id);
