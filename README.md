# MineFeed

Global Minecraft feed for NeoForge 1.21.1 — one timeline for every player: posts, comments, reactions, tags, notifications.
**Client-only** — no server-side mod required.

## How to open

- Key **J** (Controls → MineFeed)
- **MF** button left of the inventory (can be disabled in mod settings)

## Build

```powershell
.\gradlew build          # jar in build/libs/
.\gradlew runClient      # dev client
```

## For players

1. Drop the jar into `mods/`
2. NeoForge 1.21.1
3. Done — backend URL is baked into the mod

Settings (Mods → MineFeed → Config): inventory button and offsets.

## Backend

See [proxy/README.md](proxy/README.md).

## License

GPL-3.0-or-later — [LICENSE](LICENSE).
