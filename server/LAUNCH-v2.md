# MineFeed production

The V2 API uses `https://minefeed-dev.kakouto4elovek.workers.dev`. The URL is retained for compatibility with existing clients and the Discord OAuth callback; this is the production Worker despite its name. `wrangler.production.toml` targets that Worker and its `minefeed-v2-prod` D1 database.

## Discord

The main `MINEFEED` category contains releases, the public `feed` and age-restricted `feed-18` forums, help, bugs and ideas. Private community forums are created under `MineFeed communities` (and additional categories created by the Worker as needed). The bot is configured with:

- `FEED_FORUM_IDS=1568042886967070761,1568042888166899795` — main feed forums only.
- `FEED_CATEGORY_IDS=1569191969870061671` — the private community category.
- `WORKER_URL=https://minefeed-dev.kakouto4elovek.workers.dev`.

Do not include the main `MINEFEED` category in `FEED_CATEGORY_IDS`: it also contains help, bug and idea forums, which are not feed sources. Reports and community applications go to staff channels. The obsolete development and V1 Discord channels are gone.

## Deployment and release

1. Back up production data and record the deployed Worker version. Apply migrations to the production D1 database before deploying.
2. From `server`, deploy with `npx wrangler deploy --config wrangler.production.toml --keep-vars`. Secrets remain in Cloudflare. Verify bindings, `/v2/meta`, bot connection and a real feed interaction.
3. GitHub release tags build six JARs and create a draft; review and publish the draft explicitly. The bot announces published releases, not drafts. Update the V1 notice to say V2 is available only after the download is public.

There is no active staging Worker. If a new staging environment is needed, create a separate Worker, database, Discord resources and secrets rather than pointing a test deployment at production.

## Rollback

Retain the previous Worker version ID and restricted database backups. Reverting the Worker does not undo database writes; verify bindings and secrets after rollback. Do not restore a backup over a live database without checking the consequences.
