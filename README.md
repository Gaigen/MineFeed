# MineFeed

**Your Minecraft moments, shared without leaving the game.**

MineFeed is a client-side social feed for Minecraft. Share a screenshot, ask for help, celebrate an advancement or post a memorable death — and keep the conversation going in-game or on Discord. The global feed connects players across worlds and servers; no server-side mod is required.

[Download MineFeed](https://github.com/Gaigen/MineFeed/releases/latest) · [Join the Discord](https://discord.gg/FzZBQ43ujk) · [Report a bug](https://github.com/Gaigen/MineFeed/issues)

## What you can do

- **Post your moments:** talk, ask for help, celebrate achievements or share deaths. Add a screenshot, a poll or hashtags.
- **Keep the conversation going:** comment, react, follow replies and find posts through tags and search.
- **Bring your friends:** use the global feed or join a private community. Community applications are reviewed by moderators and require a linked Discord account.
- **Connect Discord (optional):** link your account for sign-in and tag protection. Feed posts and replies connect to the Discord forums.
- **Choose your experience:** customize notifications and post templates. An optional 18+ feed is disabled until you enable it.

## Install and get started

1. [Download a release](https://github.com/Gaigen/MineFeed/releases/latest) and choose the JAR matching **both** your Minecraft version and mod loader. MineFeed provides Fabric and NeoForge builds for **1.20.1**, **1.21.1** and **26.1.2**.
2. Install the matching loader. Fabric builds also require [Fabric API](https://modrinth.com/mod/fabric-api). Put the MineFeed JAR in your client’s `mods` folder; install only one MineFeed build at a time.
3. Start Minecraft and press **J**, or use the **MF** button next to your inventory. Create a profile with a nickname and unique `@tag`, or sign in to an existing one.
4. **Save your recovery code somewhere safe.** It is shown when issued and lets you recover your profile. Never share it: anyone with the code can access your account. You can also link Discord for an additional sign-in method.

MineFeed needs an internet connection. Posts in the global feed are shared publicly with other MineFeed users and the connected Discord feed; private communities have their own access controls.

### Updating from V1

MineFeed 0.2.0 uses a new, incompatible API. If you have V1, replace its JAR with the correct V2 build and create a V2 profile. V2 uses a fresh database; accounts and posts from the V2 test environment were not carried over.

## Building and contributing

The repository contains shared client code, loader/version-specific projects, the V2 API and the Discord bot. See [build and release instructions](ci/README.md) for the six builds and [production notes](server/LAUNCH-v2.md) for the API and Discord setup. Please include your Minecraft version, loader, MineFeed version and relevant logs when reporting a problem.

## License

[GPL-3.0-or-later](LICENSE).
