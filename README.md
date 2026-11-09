# MineFeed

A client-side social feed inside Minecraft. Share posts, comments and reactions across worlds and servers, and follow the conversation in Discord. No server-side mod is needed.

## Features

- Global feed with talk, help, achievement and death posts, comments, reactions and hashtags.
- Notifications, accounts, optional Discord linking and communities.
- Open the feed with **J** or the **MF** button next to the inventory.

## Install

1. Choose the release JAR matching **both** your Minecraft version and loader: Fabric or NeoForge. Supported builds are 1.20.1, 1.21.1 and 26.1.2.
2. Install the matching loader and any dependencies required by that build; Fabric builds also require Fabric API. Put the JAR in your client `mods` folder.
3. Open MineFeed in game. Create an account with a nickname and unique tag, or recover an existing account. Save the recovery code somewhere safe: it is shown when issued and is needed to recover access.

MineFeed 0.2.0 uses the V2 API, which is incompatible with the old V1 client. Existing V1 users need to update. V2 starts on a fresh account database; accounts from the prior V2 test environment do not carry over.

## Build and release

See [CI and release instructions](ci/README.md) for the six builds, release drafts and validation. See [server launch instructions](server/LAUNCH-v2.md) for deployment and the V1 migration.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE).
