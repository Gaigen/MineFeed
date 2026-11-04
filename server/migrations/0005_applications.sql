-- MineFeed v2: communities are created from applications approved by server staff.
CREATE TABLE community_applications (
  id            TEXT PRIMARY KEY,
  account_id    TEXT NOT NULL,
  discord_id    TEXT NOT NULL,
  name          TEXT NOT NULL,
  description   TEXT NOT NULL DEFAULT '',
  about         TEXT NOT NULL DEFAULT '',   -- becomes the first comment
  links         TEXT NOT NULL DEFAULT '',
  lang          TEXT NOT NULL DEFAULT 'en', -- language of the welcome post
  status        TEXT NOT NULL DEFAULT 'pending', -- pending | approved | rejected
  staff_message TEXT,                       -- message with Approve/Reject buttons
  decided_by    TEXT,                       -- Discord id of the staff member
  reason        TEXT,
  community_id  TEXT,
  created_at    INTEGER NOT NULL,
  decided_at    INTEGER
);
CREATE INDEX idx_applications_account ON community_applications(account_id, status);

ALTER TABLE posts ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
