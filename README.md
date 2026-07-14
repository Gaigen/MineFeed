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

Settings: Mods → MineFeed → Config (inventory button and offsets, death-post prompt, toast notifications, GG-compose).

## Build from source

    .\gradlew build          # jar in build/libs/
    .\gradlew runClient      # dev client

## License

GPL-3.0-or-later — [LICENSE](LICENSE).