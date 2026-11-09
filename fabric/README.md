# MineFeed Fabric 1.21.1 (experimental)

This project builds the Minecraft 1.21.1 Fabric port from the shared code in `../src/main` plus Fabric-specific integration in `src/main`. It does not replace the NeoForge build.

Requirements: JDK 21, Minecraft 1.21.1, Fabric Loader 0.18.6 or newer, and Fabric API 0.116.17+1.21.1 or a compatible newer release for 1.21.1. Put the Fabric jar and Fabric API jar in the Fabric instance's `mods` directory; do not install the NeoForge jar in that instance.

From the repository root:

```sh
bash gradlew -p fabric build
bash gradlew -p fabric runClient
```

The remapped jar is in `fabric/build/libs/`. Both loaders use the same MineFeed account storage and the v2 development API by default. This is a development build, not a public release. The Fabric project is pinned to 1.21.1; it has not been verified for other Minecraft versions.

Fabric creates `config/feed-fabric.json` for client settings. Its settings are separate from NeoForge's TOML file; edits to the JSON require a game restart, except the theme, which can also be changed in MineFeed. Unlike NeoForge, this experimental port does not yet provide a settings screen in the mod list.

The remapped jar has been smoke-tested in a normal Fabric client: feed loading, the inventory button, the local command, and draft creation after death or an advancement. Before release, test multi-player interaction, notification delivery and clicks, full chat actions, account recovery and moderation. Test the shared logic on NeoForge too when changing it.
