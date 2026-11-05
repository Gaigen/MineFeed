-- Discord result page in the game language (sent by the mod), not the browser's.
ALTER TABLE discord_flows ADD COLUMN lang TEXT;
