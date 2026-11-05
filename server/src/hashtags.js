// #hashtags: parsed from post text, indexed in post_tags, used for the tag feed, search and "popular now".
import { HttpError } from "./util.js";

export const MAX_TAGS = 10;
const TAG_RE = /(?<![\p{L}\p{N}_&#\/])#([\p{L}\p{N}_]{2,32})(?![\p{L}\p{N}_])/gu;
const DAY = 86_400_000;
const TRENDING_DAYS = 14;

/** "Майнкрафт" -> "майнкрафт", ё -> е; null if it is not a usable tag. */
export function normalizeTag(raw) {
  const t = String(raw ?? "").trim().replace(/^#/, "").toLowerCase().replace(/ё/g, "е");
  if (!/^[\p{L}\p{N}_]{2,32}$/u.test(t) || !/\p{L}/u.test(t)) return null;
  return t;
}

/** Unique normalized tags in the order they appear (at most MAX_TAGS). */
export function extractTags(text) {
  const out = [];
  for (const m of String(text ?? "").matchAll(TAG_RE)) {
    const t = normalizeTag(m[1]);
    if (t && !out.includes(t)) out.push(t);
    if (out.length >= MAX_TAGS) break;
  }
  return out;
}

/** (Re)writes the tag rows of one post. */
export async function indexPost(env, postId, space, text, createdAt) {
  const tags = extractTags(text);
  const stmts = [env.DB.prepare("DELETE FROM post_tags WHERE post_id = ?").bind(postId)];
  for (const t of tags) {
    stmts.push(env.DB.prepare("INSERT OR IGNORE INTO post_tags (tag, post_id, space, created_at) VALUES (?, ?, ?, ?)")
      .bind(t, postId, space, createdAt));
  }
  await env.DB.batch(stmts);
  return tags;
}

/**
 * GET /v2/hashtags?space=&q= — without q: popular tags of the last two weeks in the space;
 * with q: tags starting with q (all time), most used first. [{tag, posts}]
 */
export async function list(env, space, q, now, account = null) {
  const nf = account && account.show_nsfw ? "" : " AND p.nsfw = 0";
  const raw = String(q ?? "").trim().replace(/^#/, "");
  if (raw) {
    const prefix = raw.toLowerCase().replace(/ё/g, "е");
    if (!/^[\p{L}\p{N}_]{1,32}$/u.test(prefix)) return { tags: [] };
    const like = prefix.replace(/[\\%_]/g, (c) => "\\" + c) + "%";
    const { results } = await env.DB.prepare(
      `SELECT t.tag, COUNT(*) AS posts FROM post_tags t JOIN posts p ON p.id = t.post_id AND p.deleted = 0${nf}
       WHERE t.space = ? AND t.tag LIKE ? ESCAPE '\\' GROUP BY t.tag ORDER BY posts DESC, t.tag LIMIT 8`
    ).bind(space.id, like).all();
    return { tags: results };
  }
  const { results } = await env.DB.prepare(
    `SELECT t.tag, COUNT(*) AS posts FROM post_tags t JOIN posts p ON p.id = t.post_id AND p.deleted = 0${nf}
     WHERE t.space = ? AND t.created_at > ? GROUP BY t.tag ORDER BY posts DESC, MAX(t.created_at) DESC LIMIT 10`
  ).bind(space.id, now - TRENDING_DAYS * DAY).all();
  return { tags: results };
}

/** Validates the ?tag= feed filter. */
export function feedTag(raw) {
  if (raw == null || raw === "") return null;
  const t = normalizeTag(raw);
  if (!t) throw new HttpError(400, "bad_tag", "Неверный хештег");
  return t;
}
