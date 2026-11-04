// Small Discord REST helper (bot token). Tests stub env.DISCORD_FETCH.
import { HttpError } from "./util.js";

const API = "https:" + "//discord.com/api/v10";

/**
 * Calls Discord. Returns parsed JSON, null for 204, or null for 404 when `allow404`.
 * opts: { body, files: [{name, type, data}], auth: false (webhook calls), allow404 }
 */
export async function dapi(env, method, path, opts = {}) {
  const f = env.DISCORD_FETCH || fetch;
  const headers = {};
  if (opts.auth !== false) headers.authorization = `Bot ${env.DISCORD_BOT_TOKEN}`;
  let body;
  if (opts.files && opts.files.length) {
    body = new FormData();
    body.append("payload_json", JSON.stringify(opts.body || {}));
    opts.files.forEach((file, i) => body.append(`files[${i}]`, new Blob([file.data], { type: file.type }), file.name));
  } else if (opts.body !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(opts.body);
  }
  const res = await f(API + path, { method, headers, body });
  if (res.status === 429) {
    const j = await res.json().catch(() => ({}));
    throw new HttpError(503, "discord_busy", "Discord перегружен, попробуй через минуту", { retryAfter: Math.ceil(j.retry_after || 5) });
  }
  if (res.status === 404 && opts.allow404) return null;
  if (!res.ok) {
    console.error("discord", method, path.replace(/\/webhooks\/(\d+)\/[^/?]+/, "/webhooks/$1/***"), res.status, (await res.text()).slice(0, 300));
    const e = new HttpError(502, "discord_failed", "Discord не ответил, попробуй ещё раз");
    e.discordStatus = res.status;
    throw e;
  }
  if (res.status === 204) return null;
  return res.json();
}

/** Discord snowflake -> unix ms. */
export function snowflakeTime(id) {
  return Number((BigInt(id) >> 22n) + 1420070400000n);
}

// Webhook names may not contain these (Discord rule).
const BAD_NAME = /discord|clyde|```/gi;

export function webhookName(nick, tag) {
  let name = `${nick} (@${tag})`.replace(BAD_NAME, (m) => m[0] + "\u200b" + m.slice(1));
  if ([...name].length > 80) name = [...name].slice(0, 79).join("") + "…";
  return name;
}
