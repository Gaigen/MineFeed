-- 18+ and spoilers.
-- posts.nsfw: 18+ post of the global feed (its Discord thread lives in the age-restricted FEED18 forum).
ALTER TABLE posts ADD COLUMN nsfw INTEGER NOT NULL DEFAULT 0;
-- communities.nsfw: the whole community is 18+ (its Discord forum is marked age-restricted).
ALTER TABLE communities ADD COLUMN nsfw INTEGER NOT NULL DEFAULT 0;
-- accounts.show_nsfw: the player confirmed 18+ and wants to see such posts; when they confirmed.
ALTER TABLE accounts ADD COLUMN show_nsfw INTEGER NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN nsfw_confirmed_at INTEGER;
