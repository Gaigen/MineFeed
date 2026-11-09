# MineFeed V1 compatibility notice

V1 is retired. `legacy-notice.js` is the only deployed V1 Worker: it returns an update notice in the old client's feed, empty notifications and tags, and a clear error for writes. It uses the existing `APP_TOKEN` secret, no D1 binding, no Discord calls and no scheduled job. The Worker still handles requests from old clients, so it can use a small amount of Workers quota.

The current notice says V2 is coming because the GitHub release is still a draft. Once the release is public, update `V1_UPDATE_NOTICE` in `wrangler.legacy.toml` with the available download link and redeploy using `npx wrangler deploy --config wrangler.legacy.toml --keep-vars`. The old UI shows text but does not offer a clickable download button. The timestamp should remain fixed for stable ETags.

Run compatibility checks with `node --test proxy/test/*.test.mjs`.
