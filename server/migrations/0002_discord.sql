-- MineFeed v2: optional Discord link (identify scope only)
ALTER TABLE accounts ADD COLUMN discord_username TEXT;  -- Discord handle at link time (lowercase)
ALTER TABLE accounts ADD COLUMN lost_tag TEXT;          -- tag taken by its verified Discord owner (shown once)

-- short-lived OAuth flows started from the mod; the mod polls with poll_hash's secret
CREATE TABLE discord_flows (
  state      TEXT PRIMARY KEY,
  kind       TEXT NOT NULL,              -- 'link' | 'login'
  account_id TEXT,                       -- link: who started; login: filled by callback
  poll_hash  TEXT NOT NULL,              -- sha256 of the poll secret (never in the browser URL)
  status     TEXT NOT NULL DEFAULT 'pending',  -- pending | done | error
  error_code TEXT,
  error_text TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_discord_flows_created ON discord_flows(created_at);
