# MineFeed proxy (Cloudflare Worker)

Backend in front of the Discord API. The bot token never ships in the client.

## Deploy (Cloudflare dashboard)

1. **KV** namespace `MINEFEED_KV` → bind as `MINEFEED_KV`
2. **Secrets**: `DISCORD_BOT_TOKEN`, `APP_TOKEN`
3. **Vars**: `GUILD_ID`, `FORUM_CHANNEL_ID`
4. Paste `worker.js` → Deploy
5. **Cron Trigger**: `* * * * *` (Discord reply notifications)

## Discord bot

- Permissions: Send Messages, Create Posts, Read Message History, Embed Links, Manage Messages
- Message Content Intent enabled
- Bot must be on the same server as the forum channel

## API

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/v1/health` | ping |
| GET | `/v1/feed` | feed (`mine=1`, `tag=`, `fresh=1`) |
| POST | `/v1/posts` | new post |
| GET/POST | `/v1/posts/{id}/comments` | comments |
| POST | `/v1/react` | reaction (KV only, not Discord) |
| GET | `/v1/notifications` | notifications |
| GET | `/v1/tags` | tags |

All requests except `/v1/health` require the `X-Feed-Token` header.

## Notes

- Reactions live in KV only; they are not mirrored to Discord
- Worker `APP_TOKEN` must match the token embedded in the mod jar
