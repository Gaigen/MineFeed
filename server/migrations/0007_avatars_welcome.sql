-- MineFeed v2: avatars (Minecraft skin or Discord picture) and an editable welcome post.
ALTER TABLE accounts ADD COLUMN skin TEXT;                         -- Minecraft profile UUID, 32 hex, sent by the mod
ALTER TABLE accounts ADD COLUMN discord_avatar TEXT;               -- Discord avatar hash
ALTER TABLE accounts ADD COLUMN avatar TEXT NOT NULL DEFAULT 'skin'; -- skin | discord | none
ALTER TABLE posts ADD COLUMN ext_avatar TEXT;                      -- "discordId/hash" of a Discord-only author
ALTER TABLE comments ADD COLUMN ext_avatar TEXT;

-- the pinned welcome thread: its starter message has the thread's id
ALTER TABLE communities ADD COLUMN about TEXT NOT NULL DEFAULT '';
ALTER TABLE communities ADD COLUMN links TEXT NOT NULL DEFAULT '';
ALTER TABLE communities ADD COLUMN lang TEXT NOT NULL DEFAULT 'en';
ALTER TABLE communities ADD COLUMN welcome_thread_id TEXT;
ALTER TABLE communities ADD COLUMN welcome_comment_id TEXT;

UPDATE communities SET
  about = COALESCE((SELECT a.about FROM community_applications a WHERE a.community_id = communities.id), ''),
  links = COALESCE((SELECT a.links FROM community_applications a WHERE a.community_id = communities.id), ''),
  lang  = COALESCE((SELECT a.lang  FROM community_applications a WHERE a.community_id = communities.id), 'en');
