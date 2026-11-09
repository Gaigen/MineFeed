# MineFeed proxy

Cloudflare Worker backend for MineFeed. Not needed to play — the mod points at the official instance out of the box.

Maintainer notes: create a D1 database, run `schema.sql`, bind it as `DB`; secrets and vars are listed in the header comment of `worker.js`; add a `*/5 * * * *` cron trigger.
## Optional V1 update notice

The live V1 API can show a virtual, read-only announcement at the top of the main feed, without creating a Discord thread or database row. It is disabled unless both Worker variables below are set. Leave them unset until V2 is published and the download location is confirmed:

- `V1_UPDATE_NOTICE`: plain text including the V2 announcement and download address (maximum 500 characters).
- `V1_UPDATE_PUBLISHED_AT`: fixed ISO 8601 publication timestamp, e.g. `2026-11-09T12:00:00Z`.

The old mod treats this as a regular post. Its text is visible when the feed is opened; the old UI does not provide a dedicated clickable download button. The announcement is not included in filtered or paginated feeds. To disable it, remove either variable. The endpoint still uses the existing V1 infrastructure; this does not retire V1 or eliminate request/DB usage.

Run the compatibility checks with `node --test proxy/test/update-notice.test.mjs` (Node 24).
