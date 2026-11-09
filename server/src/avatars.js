// Avatars: the player's Minecraft skin head (sent by the mod) or their Discord picture.
// Wire format for the mod: "skin:<uuid32>" | "discord:<userId>/<hash>" | null.

const SKIN = /^[0-9a-f]{32}$/;
const HASH = /^(a_)?[0-9a-f]{32}$/;
const SNOWFLAKE = /^\d{5,25}$/;
export const CHOICES = ["skin", "discord", "none"];

/** Minecraft UUID with or without dashes → 32 hex, or null. */
export function normalizeSkin(raw) {
  const s = String(raw ?? "").toLowerCase().replace(/-/g, "");
  return SKIN.test(s) ? s : null;
}

export function discordKey(userId, hash) {
  return userId && hash && SNOWFLAKE.test(String(userId)) && HASH.test(String(hash)) ? `discord:${userId}/${hash}` : null;
}

/** Key from account columns, honouring the player's choice with a fallback to the other source. */
export function keyOf(a) {
  if (!a || a.avatar === "none") return null;
  const skin = a.skin && SKIN.test(a.skin) ? `skin:${a.skin}` : null;
  const disc = discordKey(a.discord_id, a.discord_avatar);
  return a.avatar === "discord" ? disc || skin : skin || disc;
}

/** Public picture URL for Discord webhooks. */
export function urlOf(key, size = 128) {
  if (!key) return null;
  if (key.startsWith("skin:")) return `https://mc-heads.net/avatar/${key.slice(5)}/${size}`;
  if (key.startsWith("discord:")) {
    const [id, hash] = key.slice(8).split("/");
    return `https://cdn.discordapp.com/avatars/${id}/${hash}.png?size=${size}`;
  }
  return null;
}
