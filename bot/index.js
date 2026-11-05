// MineFeed gateway relay. Keeps one Discord Gateway connection and forwards feed-forum events
// (comments written in Discord, deletions, tag changes, moderator role) to the Worker in batches.
// Players never talk to this process.
import { Client, GatewayIntentBits, Partials, ChannelType } from "discord.js";
import { readFileSync, existsSync } from "node:fs";
import { Relay } from "./relay.js";
import { makeFilter } from "./filter.js";
import { COMMAND, makeApplications, workerClient } from "./apply.js";
import { makeAnnouncer } from "./releases.js";
import { makePings, parseRoles } from "./pings.js";

loadEnvFile(new URL("./.env", import.meta.url).pathname);
const need = ["DISCORD_BOT_TOKEN", "WORKER_URL", "BOT_SECRET", "GUILD_ID", "FEED_CATEGORY_IDS"];
for (const k of need) if (!process.env[k]) { console.error(`missing ${k} (see .env.example)`); process.exit(1); }
const { DISCORD_BOT_TOKEN, WORKER_URL, BOT_SECRET, GUILD_ID } = process.env;
const MOD_ROLE_ID = process.env.MOD_ROLE_ID || "";

const relay = new Relay({ url: WORKER_URL, secret: BOT_SECRET });
const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent, GatewayIntentBits.GuildMembers],
  partials: [Partials.Channel, Partials.Message],
});

const channels = (id) => {
  const c = client.channels.cache.get(id);
  if (!c) return undefined;
  return { type: c.type, parentId: c.parentId, name: c.name, appliedTags: c.appliedTags };
};
const filter = makeFilter({ guildId: GUILD_ID, categories: process.env.FEED_CATEGORY_IDS.split(",").map((s) => s.trim()), channels });

const onInteraction = makeApplications({ call: workerClient({ url: WORKER_URL, secret: BOT_SECRET }), modRoleId: MOD_ROLE_ID });
const pingRoles = parseRoles(process.env.PING_ROLES);
const onPing = makePings({ roles: pingRoles });
client.on("interactionCreate", async (i) => { if (!(await onPing(i))) onInteraction(i); });

let forwarded = 0;
client.on("raw", (packet) => {
  const { t, d } = packet;
  if (!t || !d) return;
  if (t === "GUILD_MEMBER_ADD") {
    // linked MineFeed players get their community roles when they join the server
    if (d.guild_id === GUILD_ID && d.user && !d.user.bot) relay.push("MEMBER_JOIN", { discordId: d.user.id });
    return;
  }
  if (t === "GUILD_MEMBER_UPDATE") {
    if (MOD_ROLE_ID && d.guild_id === GUILD_ID && d.user) {
      relay.push("MODERATOR", { discordId: d.user.id, moderator: (d.roles || []).includes(MOD_ROLE_ID) });
    }
    return;
  }
  const out = filter(t, d);
  if (out) { relay.push(t, out); forwarded++; }
});

client.once("clientReady", async () => {
  console.log(`ready as ${client.user.tag}`);
  try {
    // guild command: shows up instantly, replaces the previous set
    await client.application.commands.set([COMMAND], GUILD_ID);
  } catch (e) {
    console.warn("command registration failed:", e.message);
  }
  if (MOD_ROLE_ID) {
    try {
      const guild = await client.guilds.fetch(GUILD_ID);
      await guild.members.fetch();
      const role = guild.roles.cache.get(MOD_ROLE_ID);
      for (const m of role ? role.members.values() : []) relay.push("MODERATOR", { discordId: m.id, moderator: true });
    } catch (e) {
      console.warn("moderator sync failed:", e.message);
    }
  }
});

// new releases of our mods -> announcements (pings the mod's role from PING_ROLES, only new ones)
// and per-mod release history channels (RELEASE_CHANNELS, every version, no ping)
const ANNOUNCE_CHANNEL_ID = process.env.ANNOUNCE_CHANNEL_ID || "";
const releaseChannels = parseRoles(process.env.RELEASE_CHANNELS); // same "key:id,key:id" format
const releaseChannel = (id, crosspost) => ({
  async send(msg) {
    const ch = await client.channels.fetch(id);
    const m = await ch.send(msg);
    if (crosspost && ch.type === ChannelType.GuildAnnouncement) await m.crosspost().catch((e) => console.warn("crosspost failed:", e.message));
    return m;
  },
  async recentFooters() {
    const ch = await client.channels.fetch(id);
    const msgs = await ch.messages.fetch({ limit: 100 });
    return new Set([...msgs.values()].filter((m) => m.author.id === client.user.id).flatMap((m) => m.embeds.map((e) => e.footer?.text)).filter(Boolean));
  },
});
const targets = [];
if (ANNOUNCE_CHANNEL_ID) {
  targets.push({ name: "announcements", channel: releaseChannel(ANNOUNCE_CHANNEL_ID, true), ping: true,
    since: Date.parse(process.env.RELEASES_SINCE || "") || Date.now(), fallback: "Fixes and improvements." });
}
for (const [key, id] of Object.entries(releaseChannels)) targets.push({ name: `${key}-releases`, channel: releaseChannel(id, false), projects: [key], since: 0 });
if (targets.length) {
  const tick = makeAnnouncer({ fetchJson, targets, roles: pingRoles });
  const run = () => { if (client.isReady()) tick().catch((e) => console.warn("releases:", e.message)); };
  setTimeout(run, 60_000);
  setInterval(run, 10 * 60_000);
}

async function fetchJson(url, headers = {}) {
  const r = await fetch(url, {
    headers: { "User-Agent": "minefeed-bot (+https://github.com/Gaigen/MineFeed)", Accept: "application/json", ...headers },
    signal: AbortSignal.timeout(15_000),
  });
  return { status: r.status, etag: r.headers.get("etag"), data: r.status === 200 ? await r.json() : null };
}

client.on("error", (e) => console.error("discord error:", e.message));
setInterval(() => {
  console.log(`alive: forwarded=${forwarded} queued=${relay.queue.length} dropped=${relay.dropped}`);
}, 15 * 60_000).unref();

for (const sig of ["SIGINT", "SIGTERM"]) {
  process.on(sig, async () => {
    console.log("stopping, flushing queue…");
    await relay.flush().catch(() => {});
    client.destroy();
    process.exit(0);
  });
}

client.login(DISCORD_BOT_TOKEN);

function loadEnvFile(path) {
  if (!existsSync(path)) return;
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (m && !line.trim().startsWith("#") && process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^(['"])(.*)\1$/, "$2");
  }
}
