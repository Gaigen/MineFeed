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
