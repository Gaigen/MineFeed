// Events from the Discord gateway bot (runs on the VPS). It forwards raw-ish Discord events in batches;
// everything here is idempotent, so the bot may safely resend a batch.
import { HttpError, sha256Hex } from "./util.js";
import { dapi, snowflakeTime } from "./dapi.js";
import { TYPES, MAX_POST, markPostDeleted, markCommentDeleted } from "./feed.js";
import * as N from "./notify.js";
import * as C from "./communities.js";

const MAX_EVENTS = 100;
const MAX_DISCORD_TEXT = 1000;

export async function checkBot(request, env) {
  const h = request.headers.get("authorization") || "";
  if (!env.BOT_SECRET || h.length < 20) throw new HttpError(401, "no_auth", "unauthorized");
  // constant-time-ish compare via hashes
  if ((await sha256Hex(h)) !== (await sha256Hex(`Bearer ${env.BOT_SECRET}`))) throw new HttpError(401, "no_auth", "unauthorized");
}

/** Discord markup -> plain text the mod can show. */
export function discordText(d) {
  const users = Object.fromEntries((d.mentions || []).map((u) => [u.id, u.global_name || u.username]));
  let t = String(d.content || "")
    .replace(/<@!?(\d+)>/g, (_, id) => "@" + (users[id] || "user"))
    .replace(/<#(\d+)>/g, "#channel")
    .replace(/<@&(\d+)>/g, "@role")
    .replace(/<a?:(\w+):\d+>/g, ":$1:")
    .replace(/<t:(\d+)(?::\w)?>/g, (_, s) => new Date(Number(s) * 1000).toISOString().slice(0, 16).replace("T", " "))
    .trim();
  const chars = [...t];
  if (chars.length > MAX_DISCORD_TEXT) t = chars.slice(0, MAX_DISCORD_TEXT - 1).join("") + "…";
  return t;
}

function images(d) {
  const list = (d.attachments || [])
    .filter((a) => /^image\//.test(a.content_type || "") && a.width && a.height)
    .slice(0, 4)
    .map((a) => ({ url: a.url, w: a.width, h: a.height }));
  return list.length ? JSON.stringify(list) : null;
}

async function ourWebhooks(env) {
  const { results } = await env.DB.prepare("SELECT webhook_id FROM spaces WHERE webhook_id IS NOT NULL").all();
  return new Set(results.map((r) => r.webhook_id));
}

async function linkedAccount(env, discordId) {
  return env.DB.prepare("SELECT id, tag, nick FROM accounts WHERE discord_id = ? AND banned = 0").bind(discordId).first();
}

function displayName(d) {
  return (d.member && d.member.nick) || (d.author && (d.author.global_name || d.author.username)) || "Discord";
}

async function onMessage(env, d, ctx, now) {
  if (!d.id || !d.channel_id || !d.author) return "skip";
  if (d.webhook_id && ctx.hooks.has(d.webhook_id)) return "ours";
  if (d.author.bot) return "skip";
  const text = discordText(d);
  const image = images(d);
  if (!text && !image) return "skip";
  const acc = await linkedAccount(env, d.author.id);
  const created = snowflakeTime(d.id);
  const actor = acc || { ext_name: displayName(d) };

  if (d.id === d.channel_id) {
    // someone opened a new forum post right in Discord
    const space = await env.DB.prepare("SELECT id, tags FROM spaces WHERE forum_id = ?").bind(String(d.parent_id || "")).first();
    if (!space) return "skip";
    const tagIds = JSON.parse(space.tags || "{}");
    const applied = new Set(d.applied_tags || []);
    const type = TYPES.find((t) => applied.has(tagIds[t])) || "talk";
    const body = [d.thread_name, text].filter(Boolean).join("\n");
    const r = await env.DB.prepare(
      `INSERT OR IGNORE INTO posts (id, space, author_id, ext_name, type, text, image, created_at, updated_at, solved)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(d.id, space.id, acc ? acc.id : null, acc ? null : displayName(d), type, [...body].slice(0, MAX_POST).join("") || "…",
      image, created, now, applied.has(tagIds.solved) ? 1 : 0).run();
    return r.meta.changes ? "post" : "dup";
  }

  const post = await env.DB.prepare("SELECT id, space, author_id, deleted FROM posts WHERE id = ?").bind(d.channel_id).first();
  if (!post || post.deleted) return "skip";
  const r = await env.DB.prepare(
    `INSERT OR IGNORE INTO comments (id, post_id, author_id, ext_name, text, image, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(d.id, post.id, acc ? acc.id : null, acc ? null : displayName(d), text, image, created, now).run();
  if (!r.meta.changes) return "dup";
  await env.DB.prepare("UPDATE posts SET comments = comments + 1, updated_at = ? WHERE id = ?").bind(now, post.id).run();
  const skip = [];
  if (post.author_id && (!acc || acc.id !== post.author_id) && await N.isReader(env, post.space, post.author_id)) {
    await N.add(env, { to: post.author_id, kind: "comment", postId: post.id, commentId: d.id, actor, text: text || "🖼" }, now);
    skip.push(post.author_id);
  }
  if (text) await N.mentions(env, actor, text, { postId: post.id, commentId: d.id, space: post.space, skip }, now);
  return "comment";
}

async function onEdit(env, d, ctx, now) {
  if (!d.id || d.content === undefined || (d.webhook_id && ctx.hooks.has(d.webhook_id))) return "skip";
  const text = discordText(d);
  if (!text) return "skip";
  const c = await env.DB.prepare("UPDATE comments SET text = ?1, updated_at = ?2 WHERE id = ?3 AND deleted = 0 AND text <> ?1")
    .bind(text, now, String(d.id)).run();
  if (c.meta.changes) return "edit";
  const p = await env.DB.prepare("UPDATE posts SET text = ?1, updated_at = ?2 WHERE id = ?3 AND deleted = 0 AND author_id IS NULL AND text <> ?1")
    .bind([...text].slice(0, MAX_POST).join(""), now, String(d.id)).run();
  return p.meta.changes ? "edit" : "skip";
}

async function onDelete(env, id, channelId, now) {
  if (id === channelId) { await markPostDeleted(env, id, now); return "post"; }
  await markCommentDeleted(env, id, channelId, now);
  return "comment";
}

async function onThreadUpdate(env, d, now) {
  const post = await env.DB.prepare("SELECT p.id, p.solved, s.tags FROM posts p JOIN spaces s ON s.id = p.space WHERE p.id = ?").bind(d.id).first();
  if (!post) return "skip";
  const solvedTag = JSON.parse(post.tags || "{}").solved;
  if (!solvedTag || !Array.isArray(d.applied_tags)) return "skip";
  const solved = d.applied_tags.includes(solvedTag) ? 1 : 0;
  if (solved === post.solved) return "same";
  await env.DB.prepare("UPDATE posts SET solved = ?, updated_at = ? WHERE id = ?").bind(solved, now, post.id).run();
  // the Solved tag was set/removed in Discord: lock/unlock the thread to match
  await dapi(env, "PATCH", `/channels/${post.id}`, { body: { locked: !!solved }, allow404: true });
  return "solved";
}

/** POST /v2/bot/events {events: [{t, d}]} */
export async function events(env, body, now) {
  const list = Array.isArray(body.events) ? body.events.slice(0, MAX_EVENTS) : [];
  const ctx = { hooks: await ourWebhooks(env) };
  const results = [];
  for (const ev of list) {
    const d = ev && ev.d ? ev.d : {};
    let r = "skip";
    try {
      switch (ev && ev.t) {
        case "MESSAGE_CREATE": r = await onMessage(env, d, ctx, now); break;
        case "MESSAGE_UPDATE": r = await onEdit(env, d, ctx, now); break;
        case "MESSAGE_DELETE": r = await onDelete(env, String(d.id), String(d.channel_id), now); break;
        case "MESSAGE_DELETE_BULK":
          for (const id of (d.ids || []).slice(0, 100)) await onDelete(env, String(id), String(d.channel_id), now);
          r = "bulk"; break;
        case "THREAD_DELETE": await markPostDeleted(env, String(d.id), now); r = "post"; break;
        case "THREAD_UPDATE": r = await onThreadUpdate(env, d, now); break;
        case "MEMBER_JOIN": { // a player with a linked Discord joined the server: give community roles
          const acc = await env.DB.prepare("SELECT id FROM accounts WHERE discord_id = ?").bind(String(d.discordId)).first();
          r = acc ? `roles:${await C.syncRoles(env, acc.id, String(d.discordId), true)}` : "skip"; break;
        }
        case "MODERATOR": {
          const u = await env.DB.prepare("UPDATE accounts SET moderator = ? WHERE discord_id = ?").bind(d.moderator ? 1 : 0, String(d.discordId)).run();
          r = u.meta.changes ? "moderator" : "skip"; break;
        }
      }
    } catch (e) {
      console.error("bot event", ev && ev.t, e);
      r = "error";
    }
    results.push(r);
  }
  return { ok: true, results };
}
