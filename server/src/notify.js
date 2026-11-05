// In-mod notifications. Reactions fold into one row per post so they never spam.
import { HttpError } from "./util.js";
import { keyOf } from "./avatars.js";

const QUOTE = 80;
const PAGE = 30;
export const KEEP_MS = 60 * 86_400_000;

function quote(text) {
  const chars = [...String(text || "").replace(/\s+/g, " ").trim()];
  return chars.length > QUOTE ? chars.slice(0, QUOTE - 1).join("") + "…" : chars.join("");
}

function actorName(actor) {
  return actor.nick || actor.ext_name || "?";
}

/** One notification (comment, mention, ...). actor: account row or {ext_name}. */
export async function add(env, n, now) {
  await env.DB.prepare(
    `INSERT INTO notifications (account_id, kind, post_id, comment_id, community_id, actor_id, actor_name, text, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(n.to, n.kind, n.postId || null, n.commentId || null, n.communityId || null, n.actor.id || null, actorName(n.actor), quote(n.text), now, now).run();
}

/** Can this account still read the space? (people who left a community get nothing from it) */
export async function isReader(env, space, accountId) {
  if (!space || space === "global") return true;
  const m = await env.DB.prepare("SELECT 1 AS x FROM members WHERE community_id = ? AND account_id = ?").bind(space, accountId).first();
  return !!m;
}

/** "X and N others reacted to your post" — one row per post, bumped on each new reaction. */
export async function react(env, n, now) {
  await env.DB.prepare(
    `INSERT INTO notifications (account_id, kind, post_id, actor_id, actor_name, count, text, group_key, created_at, updated_at)
     VALUES (?1, 'react', ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?8)
     ON CONFLICT(group_key) DO UPDATE SET actor_id = ?3, actor_name = ?4, count = ?5, updated_at = ?8`
  ).bind(n.to, n.postId, n.actor.id, actorName(n.actor), n.count, quote(n.text), `react:${n.postId}`, now).run();
}

const MENTION = /(?:^|[^a-z0-9_.@])@([a-z0-9_.]{2,32})/gi;

/** Notify up to 5 @mentioned players (not the writer, not those in `skip`). */
export async function mentions(env, actor, text, where, now) {
  const tags = [...new Set([...String(text).matchAll(MENTION)].map((m) => m[1].toLowerCase().replace(/\.+$/, "")))].slice(0, 5);
  if (!tags.length) return;
  const space = where.space || "global";
  const { results } = await env.DB.prepare(
    `SELECT a.id FROM accounts a WHERE a.banned = 0 AND a.tag IN (${tags.map(() => "?").join(",")})
       AND (? = 'global' OR EXISTS (SELECT 1 FROM members m WHERE m.community_id = ? AND m.account_id = a.id))
       ${where.nsfw ? "AND a.show_nsfw = 1" : ""}`
  ).bind(...tags, space, space).all();
  for (const r of results) {
    if (r.id === actor.id || where.skip.includes(r.id)) continue;
    await add(env, { to: r.id, kind: "mention", postId: where.postId, commentId: where.commentId, actor, text }, now);
  }
}

function out(r, seenAt) {
  return {
    id: r.id,
    kind: r.kind,
    postId: r.post_id,
    commentId: r.comment_id,
    communityId: r.community_id || null,
    communityName: r.community_name || null,
    actor: r.actor_id
      ? { id: r.actor_id, tag: r.a_tag || null, nick: r.a_nick || r.actor_name,
          avatar: keyOf({ avatar: r.a_avatar, skin: r.a_skin, discord_id: r.a_discord_id, discord_avatar: r.a_discord_avatar }) }
      : { discord: true, nick: r.actor_name },
    count: r.count,
    text: r.text,
    postText: r.post_text == null ? null : quote(r.post_text),
    createdAt: r.created_at,
    updatedAt: r.updated_at,
    unread: r.updated_at > seenAt,
  };
}

const SELECT = `SELECT n.*, a.tag AS a_tag, a.nick AS a_nick, a.avatar AS a_avatar, a.skin AS a_skin,
    a.discord_id AS a_discord_id, a.discord_avatar AS a_discord_avatar, substr(p.text, 1, ${QUOTE}) AS post_text
  , cm.name AS community_name
  FROM notifications n LEFT JOIN accounts a ON a.id = n.actor_id LEFT JOIN posts p ON p.id = n.post_id
  LEFT JOIN communities cm ON cm.id = n.community_id`;

async function unread(env, account) {
  const r = await env.DB.prepare("SELECT COUNT(*) AS n FROM notifications WHERE account_id = ? AND updated_at > ?")
    .bind(account.id, account.notifs_seen_at || 0).first();
  return r.n;
}

/** For /v2/sync: unread count and rows changed after `since` (none when since = 0). */
export async function changes(env, account, since) {
  const count = await unread(env, account);
  if (!since || !count) return { unread: count, items: [] };
  const { results } = await env.DB.prepare(`${SELECT} WHERE n.account_id = ? AND n.updated_at > ? ORDER BY n.updated_at DESC LIMIT 20`)
    .bind(account.id, since).all();
  return { unread: count, items: results.map((r) => out(r, account.notifs_seen_at || 0)) };
}

/** GET /v2/notifications?before= */
export async function list(env, account, q) {
  const before = Number.parseInt(q.get("before"), 10);
  const { results } = await env.DB.prepare(
    `${SELECT} WHERE n.account_id = ?${Number.isFinite(before) ? " AND n.updated_at < ?" : ""} ORDER BY n.updated_at DESC LIMIT ${PAGE + 1}`
  ).bind(account.id, ...(Number.isFinite(before) ? [before] : [])).all();
  const more = results.length > PAGE;
  const rows = results.slice(0, PAGE);
  return {
    items: rows.map((r) => out(r, account.notifs_seen_at || 0)),
    unread: await unread(env, account),
    before: more ? rows[rows.length - 1].updated_at : null,
  };
}

/** POST /v2/notifications/seen {at} — everything up to `at` (default now) is read. */
export async function seen(env, account, body, now) {
  const at = Number.isFinite(body.at) ? Math.min(body.at, now) : now;
  if (at < 0) throw new HttpError(400, "bad_at", "Некорректное время");
  await env.DB.prepare("UPDATE accounts SET notifs_seen_at = MAX(notifs_seen_at, ?) WHERE id = ?").bind(at, account.id).run();
  return { ok: true };
}

/** Daily cleanup. */
export async function prune(env, now) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM notifications WHERE updated_at < ?").bind(now - KEEP_MS),
    env.DB.prepare("DELETE FROM rate_limits WHERE window_start < ?").bind(now - 86_400_000),
    env.DB.prepare("DELETE FROM tag_holds WHERE until < ?").bind(now),
  ]);
}
