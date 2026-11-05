# MineFeed bot (gateway relay)

Small Node process for the VPS. It keeps one Discord Gateway connection and forwards
feed-forum events to the MineFeed Worker in batches (`POST /v2/bot/events`):

- comments written right in Discord (they show up in the mod, linked Discord accounts are matched to their MineFeed profile);
- deleted messages / threads (moderation in Discord removes things from the mod too);
- forum tag changes (e.g. "solved");
- the Moderator role (moderators can delete posts from the mod).

Players never connect to the VPS. If the bot is down, the mod keeps working; only Discord-side
changes are delayed (the queue is retried with backoff).

## Install (Debian/Ubuntu, systemd)

Current VPS (Ubuntu 24.04): `apt install nodejs` (18.19 from Ubuntu, security updates via apt),
`node_modules` copied as is (pure JS, no build step). Updating = copy files to /opt/minefeed-bot +
`sudo systemctl restart minefeed-bot`.

```sh
# Node 18+ (20 LTS recommended)
sudo useradd --system --home /opt/minefeed-bot --shell /usr/sbin/nologin minefeed
sudo mkdir -p /opt/minefeed-bot && sudo cp index.js relay.js filter.js package.json package-lock.json /opt/minefeed-bot/
cd /opt/minefeed-bot && sudo npm ci --omit=dev
sudo cp /path/to/.env /opt/minefeed-bot/.env      # see .env.example
sudo chown -R root:minefeed /opt/minefeed-bot && sudo chmod 640 /opt/minefeed-bot/.env
sudo cp minefeed-bot.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now minefeed-bot
journalctl -u minefeed-bot -f                     # "ready as Feed#…"
```

Memory: ~60–90 MB. Traffic: tiny.

## Settings

See `.env.example`. `BOT_SECRET` must equal the Worker secret of the same name.
`FEED_CATEGORY_IDS` lists the Discord categories that hold MineFeed forums (main feed + communities).

## Community applications

On start the bot registers the guild command `/community` (`/сообщество` in Russian Discord).
It checks with the Worker that the player's Discord is linked to MineFeed, opens a form
(name, short description, about, links) and sends it to `POST /v2/bot/apply`.
The Worker posts the application to `APPLICATIONS_CHANNEL_ID` with Approve/Reject buttons.
Only members with `MOD_ROLE_ID` or Administrator can press them; Reject asks for an optional reason.
On approval the Worker creates the community, a pinned welcome post and DMs the player.

## Release announcements and ping roles

- `releases.js` polls GitHub releases, Modrinth and CurseForge (via cfwidget) every 10 minutes for the mods in `PROJECTS`
  and posts one message per new version into `ANNOUNCE_CHANNEL_ID` (crossposted when it is a news channel),
  pinging the mod's role from `PING_ROLES`. Builds of one version for several Minecraft versions/loaders are merged.
  It keeps no state: the channel itself is the memory (embed footer `<Name> <version>`), and only versions published
  after `RELEASES_SINCE` are announced. Pre-releases (alpha/beta/rc/dev) are skipped.
  The text is the GitHub release body, or the commits of the release when the body is only "Full Changelog".
- `RELEASE_CHANNELS` (`key:channelId,...`): per-mod history channels; every version of that mod is posted there once,
  in version order, without a ping (the first run fills the channel with all past versions).
- `pings.js` handles buttons with `customId` `ping:<key>` (message in #welcome): a click toggles that ping role.
