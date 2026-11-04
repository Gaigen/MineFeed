# MineFeed

**A social feed inside Minecraft.** One global timeline shared by every player with the mod — post, comment, react and get replies without leaving the game.

**Client-only** — drop the jar into `mods/` and that's it. No server-side mod, no account, no setup. Works on any server and in singleplayer; you post under your Minecraft nickname and skin.

## Why

Minecraft moments usually die in screenshots nobody sees. MineFeed gives them a place: died a stupid death? Post it. Stuck on a build or a farm? Ask for help. Finally beat the dragon? Brag. Everyone running the mod sees it — across servers and worlds.

## What's inside

- **4 kinds of posts**: `[~]` talk, `[?]` help (can be marked solved), `[GG]` achievements, `[F]` deaths
- **Comments** under every post
- **Reactions** — each post type has its own pair, one per player, toggleable
- **#tags** — tag your posts, browse tag feeds, autocomplete while typing
- **Reply notifications** — on-screen toast when someone answers your post; click it to jump into the thread
- **Death posts** — one click after respawn to share your death message
- **GG on advancement** — optional auto-compose when you earn an advancement
- Retro-styled UI with real player avatars

## How to open

- Key **J** (Controls → MineFeed)
- **MF** button next to the inventory (can be disabled in settings)

## Install

1. NeoForge 1.21.1
2. Drop the jar into `mods/`
3. Done

Settings: Mods → MineFeed → Config. Existing options are preserved. The new
**Notifications** section controls reply toasts, their placement, duration,
count and polling interval, optional local sound and local chat notifications (both off by
default; they are never sent to the server chat). **Post templates** controls
the prefills for death and advancement posts. Templates use these placeholders:

- Reply toast and chat: `{author}`, `{message}`, `{post}`
- Death post: `{player}`, `{message}`
- Advancement post: `{player}`, `{advancement}`

Unknown placeholders stay as written. Templates do not post automatically; the
compose screen still requires manual submission. Toasts can be turned off
without disabling the unread inbox. Configuration is saved in
`config/feed-client.toml`.

When local chat notifications are enabled, replies include a post preview and
clickable **Open** and **Reply** actions. Open shows the discussion; Reply
prefills a client-only command in chat. Type the reply and press Enter to send it
to the MineFeed discussion, not to Minecraft server chat. The Minecraft chat
input length limit applies; use the post screen for longer comments. Chat
history can retain the command and text. Post previews are limited by the
notification service. Failed requests are reported locally.

## Build from source

    .\gradlew build          # jar in build/libs/
    .\gradlew runClient      # dev client

## License

GPL-3.0-or-later — [LICENSE](LICENSE).
## Notification inbox

The `!` counter in the feed opens the unread inbox even when the count is zero.
Opening the inbox does not mark entries read. Open a post or choose **Mark all
read** to clear entries. Unread entries and the cursor are saved locally (up to
100 entries); the service returns up to 50 recent notifications. The notification
sound plays once per received batch, not once per comment. Notification fetch
uses the current service API and only reports comments on your own posts;
reactions, mentions and new-post subscriptions need server support.
