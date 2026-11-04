// MineFeed gateway relay. Keeps one Discord Gateway connection and forwards feed-forum events
// (comments written in Discord, deletions, tag changes, moderator role) to the Worker in batches.
// Players never talk to this process.
import { Client, GatewayIntentBits, Partials } from "discord.js";
import { readFileSync, existsSync } from "node:fs";
import { Relay } from "./relay.js";
import { makeFilter } from "./filter.js";

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

let forwarded = 0;
client.on("raw", (packet) => {
  const { t, d } = packet;
  if (!t || !d) return;
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
