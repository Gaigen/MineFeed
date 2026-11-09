-- MineFeed v2: private communities. Each one = a closed Discord forum + two roles (mod, member).
CREATE TABLE communities (
  id             TEXT PRIMARY KEY,           -- also the space id
  name           TEXT NOT NULL,
  description    TEXT NOT NULL DEFAULT '',
  owner_id       TEXT NOT NULL,
  forum_id       TEXT NOT NULL,
  category_id    TEXT NOT NULL,
  mod_role_id    TEXT NOT NULL,
  member_role_id TEXT NOT NULL,
  members        INTEGER NOT NULL DEFAULT 1,
  created_at     INTEGER NOT NULL,
  deleted        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_communities_owner ON communities(owner_id) WHERE deleted = 0;

-- Discord categories that hold community forums (max 50 channels each)
CREATE TABLE community_categories (
  id         TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);

CREATE TABLE members (
  community_id TEXT NOT NULL,
  account_id   TEXT NOT NULL,
  role         TEXT NOT NULL,                -- owner | mod | member
  via          TEXT,                         -- note of the code used to join
  joined_at    INTEGER NOT NULL,
  read_at      INTEGER NOT NULL DEFAULT 0,   -- for unread counters
  PRIMARY KEY (community_id, account_id)
) WITHOUT ROWID;
CREATE INDEX idx_members_account ON members(account_id);

CREATE TABLE invite_codes (
  code         TEXT PRIMARY KEY,             -- uppercase A-Z 0-9 -
  community_id TEXT NOT NULL,
  note         TEXT NOT NULL DEFAULT '',
  max_uses     INTEGER,                      -- NULL = unlimited
  uses         INTEGER NOT NULL DEFAULT 0,
  expires_at   INTEGER,                      -- NULL = never
  approval     INTEGER NOT NULL DEFAULT 0,   -- joining needs a mod's approval
  created_by   TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  revoked      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_codes_community ON invite_codes(community_id);

CREATE TABLE join_requests (
  community_id TEXT NOT NULL,
  account_id   TEXT NOT NULL,
  via          TEXT,
  created_at   INTEGER NOT NULL,
  PRIMARY KEY (community_id, account_id)
) WITHOUT ROWID;
CREATE INDEX idx_requests_account ON join_requests(account_id);

CREATE TABLE community_bans (
  community_id TEXT NOT NULL,
  account_id   TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  PRIMARY KEY (community_id, account_id)
) WITHOUT ROWID;

-- join requests / acceptance notifications point at a community
ALTER TABLE notifications ADD COLUMN community_id TEXT;
