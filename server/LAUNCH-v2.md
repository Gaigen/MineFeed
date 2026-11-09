# MineFeed v2 production and migration

The production API deliberately keeps `https://minefeed-dev.kakouto4elovek.workers.dev`: released clients and the Discord OAuth callback use this address. Its name does not imply that it is a staging environment. `wrangler.production.toml` targets that Worker and the clean `minefeed-v2-prod` database. Production Discord feeds are in the `MINEFEED V2` category; community forums use the separate `MineFeed communities` category. Both are kept private until launch.

`wrangler.toml` is for an independent staging Worker named `minefeed-v2-staging`, using the former test database and test Discord resources. That Worker must be provisioned with its own secrets before use. Never use it to deploy to the production URL. Store secrets in Cloudflare, not in these files.

## Deployment

1. Back up the databases and record the currently deployed Worker version. Apply migrations to the intended D1 database before deploying.
2. Deploy from `server` with `npx wrangler deploy --config wrangler.production.toml --keep-vars`. Secrets are retained. Verify bindings, `/v2/meta`, and Discord integration before opening channels or publishing a release.
3. The bot's `WORKER_URL` must point at the production API, and `FEED_CATEGORY_IDS` must include `1568042885482422314` (main feeds) and `1569191969870061671` (communities). Preserve other needed configuration and secrets.
4. When the release is approved, remove the @everyone View deny on the `MINEFEED V2` category and its two forums, retaining the age restriction on `feed-18`. Community categories stay private and access is role-controlled.
5. Publish the tested release, deploy and enable the V1 update notice, and verify it in the old client. Keep V1 available for a migration period; retire it and its old channels only after the notice and backups have been verified and the sunset date is agreed.

## Rollback

Keep the previous Worker version ID, the previous bindings and restricted database backups. If the production switch fails, restore the previous deployment and verify the D1 binding and secrets as well as the API. Reverting the Worker does not undo database writes. Never restore a backup over a live database without checking the consequences first.
