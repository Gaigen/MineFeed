# MineFeed proxy

Cloudflare Worker backend for MineFeed. Not needed to play — the mod points at the official instance out of the box.

Maintainer notes: create a D1 database, run `schema.sql`, bind it as `DB`; secrets and vars are listed in the header comment of `worker.js`; add a `*/5 * * * *` cron trigger.