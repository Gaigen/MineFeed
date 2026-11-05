// Player profiles and search. Only the global feed counts: community posts stay private.
import { HttpError } from "./util.js";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TAG = /^[a-z0-9_.]{1,32}$/;

function likeEscape(s) {
  return s.replace(/[\\%_]/g, (c) => "\\" + c);
}

/** GET /v2/users?q= — up to 10 players by @tag prefix or nick substring. */
export async function search(env, q) {
  const raw = String(q.get("q") || "").trim().replace(/^@/, "").slice(0, 32);
  if (!raw) return { users: [] };
  const lower = raw.toLowerCase();
  const { results } = await env.DB.prepare(
    `SELECT id, tag, nick FROM accounts
     WHERE banned = 0 AND (tag LIKE ?1 ESCAPE '\\' OR nick LIKE ?2 ESCAPE '\\')
     ORDER BY (tag = ?3) DESC, (tag LIKE ?1 ESCAPE '\\') DESC, length(tag), tag LIMIT 10`
  ).bind(likeEscape(lower) + "%", "%" + likeEscape(raw) + "%", lower).all();
  return { users: results.map((r) => ({ id: r.id, tag: r.tag, nick: r.nick })) };
}

/** GET /v2/users/:idOrTag — public profile with stats from the global feed. */
export async function profile(env, key) {
  const k = String(key || "").toLowerCase().replace(/^@/, "");
  if (!UUID.test(k) && !TAG.test(k)) throw new HttpError(404, "no_user", "Игрок не найден");
  const a = await env.DB.prepare(
    `SELECT id, tag, nick, created_at, discord_id, moderator, banned, tag_protected FROM accounts WHERE ${UUID.test(k) ? "id" : "tag"} = ?`
  ).bind(k).first();
  if (!a || a.banned) throw new HttpError(404, "no_user", "Игрок не найден");
  const s = await env.DB.prepare(
    `SELECT COUNT(*) AS posts, COALESCE(SUM(re0 + re1), 0) AS reactions, COALESCE(SUM(type = 'gg'), 0) AS gg,
       COALESCE(SUM(type = 'help' AND solved = 1), 0) AS solved
     FROM posts WHERE author_id = ? AND space = 'global' AND deleted = 0`
  ).bind(a.id).first();
  const c = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM comments c JOIN posts p ON p.id = c.post_id
     WHERE c.author_id = ? AND c.deleted = 0 AND p.space = 'global'`
  ).bind(a.id).first();
  return {
    user: {
      id: a.id, tag: a.tag, nick: a.nick, createdAt: a.created_at,
      discord: !!a.discord_id, moderator: !!a.moderator, tagProtected: !!a.tag_protected,
      stats: { posts: s.posts, comments: c.n, reactions: s.reactions, gg: s.gg, solved: s.solved },
    },
  };
}
