# MineFeed build automation

Every branch push, every pull request, and every pushed `v...` tag
runs the checks and builds six client-only jars in GitHub Actions. A manual workflow
run is also available after the workflow exists on the repository's default branch.
Each job uploads a downloadable artifact for testing. A successful build alone is
not an end-to-end test in a Minecraft launcher.

The version comes from `gradle.properties` (`mod_version`). Packaging verifies the
internal mod version, loader and Minecraft version, then outputs filenames such as
`minefeed-0.2.0+1.21.1-fabric.jar`. Those names are understood by the Discord release
announcer. Never upload sources/dev-only jars as player downloads.

## Stable release preparation

The project currently plans to promote the existing V2 Worker, rather than
replacing the incompatible V1 API. `ci/release-api.txt` records the chosen V2
HTTPS origin, and the release preflight checks it against all four client
implementations. Its name contains `dev`, but renaming it would break existing
clients; the name alone does not decide whether it is suitable for production.

A passing preflight only checks the version and URL consistency. Before publishing,
verify the Worker and D1, switch and test its Discord forum configuration, test all
six JARs in real launchers, review security and limits, and verify bot configuration.
See `server/LAUNCH-v2.md`. Do not retire V1 until the migration is agreed.

After the preparation changes are merged and CI succeeds:

1. Create and push a matching tag, e.g. `v0.2.0`, on the exact commit to release.
2. Actions checks the tag/version and requires the configured API origin,
   runs tests, builds all six JARs and creates **one GitHub Release draft** only
   if the complete artifact set passes validation. This uses the built-in
   `GITHUB_TOKEN` with `contents: write` only in the release job.
3. Review the draft's six files, release notes and test results before publishing.
   The workflow does not publish automatically. The Discord bot ignores drafts
   and announces the release only after a human publishes it.

The pipeline does not yet publish to Modrinth/CurseForge, deploy the Worker, or
change Discord channel permissions. Those are separate steps, not implicit side effects.
GitHub Actions tests cannot replace real sign-in/feed/posting/notification testing.
