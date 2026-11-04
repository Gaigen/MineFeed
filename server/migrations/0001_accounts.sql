-- MineFeed v2: accounts
CREATE TABLE accounts (
  id             TEXT PRIMARY KEY,
  tag            TEXT NOT NULL UNIQUE,          -- lowercase @tag, unique
  nick           TEXT NOT NULL,                 -- display name, not unique
  created_at     INTEGER NOT NULL,              -- unix ms
  tag_changed_at INTEGER,
  recovery_hash  TEXT NOT NULL UNIQUE,          -- sha256 of recovery code
  discord_id     TEXT UNIQUE,                   -- set by optional Discord link
  tag_protected  INTEGER NOT NULL DEFAULT 0,    -- tag confirmed via Discord
  banned         INTEGER NOT NULL DEFAULT 0
);

-- one row per device key; key itself is never stored
CREATE TABLE sessions (
  key_hash     TEXT PRIMARY KEY,
  account_id   TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  created_at   INTEGER NOT NULL,
  last_used_at INTEGER NOT NULL
);
CREATE INDEX idx_sessions_account ON sessions(account_id);

-- old tags stay reserved for their previous owner for a while
CREATE TABLE tag_holds (
  tag        TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  until      INTEGER NOT NULL
);

-- fixed-window counters for sensitive actions (register, recover)
CREATE TABLE rate_limits (
  bucket       TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL
);
