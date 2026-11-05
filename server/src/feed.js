// Feed: posts live in Discord forum threads (written by a webhook as the player), D1 is the index.
import * as A from "./avatars.js";
import { HttpError } from "./util.js";
import { rateLimit } from "./accounts.js";
import { dapi, webhookName } from "./dapi.js";
import * as N from "./notify.js";
import { parseUpload, freshen } from "./images.js";
import * as H from "./hashtags.js";
import { closeReports, modLog } from "./modlog.js";

export const TYPES = ["rip", "help", "gg", "talk"];
export const MAX_POST = 500;
export const MAX_COMMENT = 300;
export const SYNC_OVERLAP = 3000;      // re-send changes this close to the cursor (clock jitter)
export const SYNC_MAX_AGE = 86_400_000; // older cursors must reload the feed
const PAGE = 20;
const DAY = 86_400_000;
export const MAX_PINS = 3;
export const POLL_MIN = 2;
export const POLL_MAX = 4;
export const POLL_OPTION = 40;
const COMMENTS_PAGE = 50;

const AUTHOR_COLS = "a.tag AS a_tag, a.nick AS a_nick, a.skin AS a_skin, a.avatar AS a_av, a.discord_id AS a_did, a.discord_avatar AS a_dav";
export const POST_SELECT = `SELECT p.*, ${AUTHOR_COLS} FROM posts p LEFT JOIN accounts a ON a.id = p.author_id`;
export const COMMENT_SELECT = `SELECT c.*, ${AUTHOR_COLS} FROM comments c LEFT JOIN accounts a ON a.id = c.author_id`;

// ---------- text ----------

const CTRL = /[\u0000-\u0009\u000b-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2060-\u206f\ufeff]/g;

export function cleanText(raw, max) {
  const text = String(raw ?? "")
    .replace(/\r\n?/g, "\n")
    .replace(/\u00a7./gu, "")
    .replace(CTRL, "")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  const len = [...text].length;
  if (len < 1) throw new HttpError(400, "empty_text", "Пустой текст");
  if (len > max) throw new HttpError(400, "too_long", `Слишком длинный текст (максимум ${max})`);
  return text;
}

export function threadName(text) {
  const line = text.split("\n").find((l) => l.trim()) || text;
  const chars = [...line.trim()];
  return chars.length > 95 ? chars.slice(0, 94).join("") + "…" : chars.join("");
}

// ---------- spaces ----------

const spaceCaches = new WeakMap(); // per D1 binding (one in production; many in tests)

function cacheFor(env) {
  let c = spaceCaches.get(env.DB);
  if (!c) spaceCaches.set(env.DB, (c = new Map()));
  return c;
}

/** Space row with a ready webhook; the global space is created from env on first use. */
export async function getSpace(env, id) {
  const cached = cacheFor(env).get(id);
  if (cached && cached.webhook_id) return cached;
  let row = await env.DB.prepare("SELECT * FROM spaces WHERE id = ?").bind(id).first();
  if (!row && id === "global") {
    if (!env.FEED_FORUM_ID) throw new HttpError(503, "feed_off", "Лента сейчас недоступна");
    await env.DB.prepare("INSERT OR IGNORE INTO spaces (id, forum_id, tags) VALUES ('global', ?, ?)")
      .bind(env.FEED_FORUM_ID, env.FEED_TAGS || "{}").run();
    row = await env.DB.prepare("SELECT * FROM spaces WHERE id = ?").bind(id).first();
  }
  if (!row) throw new HttpError(404, "no_space", "Сообщество не найдено");
  if (!row.webhook_id) {
    const wh = await dapi(env, "POST", `/channels/${row.forum_id}/webhooks`, { body: { name: "MineFeed" } });
    await env.DB.prepare("UPDATE spaces SET webhook_id = ?, webhook_token = ? WHERE id = ? AND webhook_id IS NULL")
      .bind(wh.id, wh.token, id).run();
    row = await env.DB.prepare("SELECT * FROM spaces WHERE id = ?").bind(id).first();
  }
  row.tagIds = JSON.parse(row.tags || "{}");
  cacheFor(env).set(id, row);
  return row;
}

/** Who may read/write a space: everyone for the global feed, members for a community. */
export async function spaceFor(env, account, id) {
  const sid = String(id || "global");
  if (sid === "global") return { ...(await getSpace(env, sid)), role: null };
  const m = await env.DB.prepare(
    "SELECT m.role FROM members m JOIN communities c ON c.id = m.community_id WHERE m.community_id = ? AND m.account_id = ? AND c.deleted = 0"
  ).bind(sid, account.id).first();
  if (!m) throw new HttpError(404, "no_space", "Сообщество не найдено");
  return { ...(await getSpace(env, sid)), role: m.role };
}

async function postAs(env, space, account, payload, threadId, files) {
  const q = "?wait=true" + (threadId ? `&thread_id=${threadId}` : "");
  return dapi(env, "POST", `/webhooks/${space.webhook_id}/${space.webhook_token}${q}`, {
    auth: false,
    files: files || undefined,
    body: {
      ...payload,
      username: webhookName(account.nick, account.tag),
      ...(A.urlOf(A.keyOf(account)) ? { avatar_url: A.urlOf(A.keyOf(account)) } : {}),
      allowed_mentions: { parse: [] },
    },
  });
}

// ---------- output ----------

function author(r) {
  if (r.author_id) {
    const avatar = A.keyOf({ avatar: r.a_av, skin: r.a_skin, discord_id: r.a_did, discord_avatar: r.a_dav });
    return { id: r.author_id, tag: r.a_tag || null, nick: r.a_nick || "?", avatar };
  }
  const [did, hash] = String(r.ext_avatar || "").split("/");
  return { discord: true, nick: r.ext_name || "Discord", avatar: A.discordKey(did, hash) };
}

export function postOut(r, my) {
  if (r.deleted) return { id: r.id, deleted: true, updatedAt: r.updated_at };
  return {
    id: r.id,
    space: r.space,
    type: r.type,
    text: r.text,
    author: author(r),
    createdAt: r.created_at,
    updatedAt: r.updated_at,
    comments: r.comments,
    reactions: [r.re0, r.re1],
    my: my ?? -1,
    solved: !!r.solved,
    pinned: !!r.pinned,
    editedAt: r.edited_at || null,
    image: r.image ? JSON.parse(r.image) : null,
  };
}

export function commentOut(r) {
  if (r.deleted) return { id: r.id, postId: r.post_id, deleted: true, updatedAt: r.updated_at };
  return {
    id: r.id,
    postId: r.post_id,
    text: r.text,
    author: author(r),
    createdAt: r.created_at,
    updatedAt: r.updated_at,
    editedAt: r.edited_at || null,
    image: r.image ? JSON.parse(r.image) : null,
  };
}

async function myReactions(env, account, ids) {
  if (!ids.length) return {};
  const { results } = await env.DB.prepare(
    `SELECT post_id, slot FROM reactions WHERE account_id = ? AND post_id IN (${ids.map(() => "?").join(",")})`
  ).bind(account.id, ...ids).all();
  return Object.fromEntries(results.map((r) => [r.post_id, r.slot]));
}

async function postsOut(env, account, rows) {
  await freshen(env, rows, "posts");
  const live = rows.filter((r) => !r.deleted).map((r) => r.id);
  const my = await myReactions(env, account, live);
  const polls = await pollsFor(env, account, live);
  return rows.map((r) => {
    const out = postOut(r, my[r.id]);
    if (polls[r.id]) out.poll = polls[r.id];
    return out;
  });
}

// ---------- polls ----------

/** {postId: {options: [text], votes: [n], my: index|-1}} for the posts that have a poll. */
async function pollsFor(env, account, ids) {
  if (!ids.length) return {};
  const marks = ids.map(() => "?").join(",");
  const { results: polls } = await env.DB.prepare(`SELECT post_id, options FROM polls WHERE post_id IN (${marks})`).bind(...ids).all();
  if (!polls.length) return {};
  const pids = polls.map((p) => p.post_id);
  const pm = pids.map(() => "?").join(",");
  const [{ results: counts }, { results: mine }] = await Promise.all([
    env.DB.prepare(`SELECT post_id, option, COUNT(*) AS n FROM poll_votes WHERE post_id IN (${pm}) GROUP BY post_id, option`).bind(...pids).all(),
    env.DB.prepare(`SELECT post_id, option FROM poll_votes WHERE account_id = ? AND post_id IN (${pm})`).bind(account.id, ...pids).all(),
  ]);
  const out = {};
  for (const p of polls) {
    const options = JSON.parse(p.options);
    out[p.post_id] = { options, votes: options.map(() => 0), my: -1 };
  }
  for (const c of counts) if (out[c.post_id] && c.option < out[c.post_id].votes.length) out[c.post_id].votes[c.option] = c.n;
  for (const m of mine) if (out[m.post_id]) out[m.post_id].my = m.option;
  return out;
}

/** Validates {options: [...]} of a new post: 2-4 short unique options, or null for no poll. */
function parsePoll(raw) {
  if (raw == null) return null;
  const list = Array.isArray(raw.options) ? raw.options : null;
  if (!list) throw new HttpError(400, "bad_poll", "Неверный опрос");
  const options = list.map((o) => String(o ?? "").replace(/\s+/g, " ").replace(CTRL, "").replace(/\u00a7./gu, "").trim()).filter(Boolean);
  if (options.length < POLL_MIN || options.length > POLL_MAX) throw new HttpError(400, "bad_poll", `В опросе от ${POLL_MIN} до ${POLL_MAX} вариантов`);
  if (options.some((o) => [...o].length > POLL_OPTION)) throw new HttpError(400, "bad_poll", `Вариант длиннее ${POLL_OPTION} символов`);
  if (new Set(options.map((o) => o.toLowerCase())).size !== options.length) throw new HttpError(400, "bad_poll", "Варианты повторяются");
  return options;
}

/** What the Discord copy of a post says: the text plus the poll options (voting happens in the mod). */
function discordContent(text, options) {
  if (!options) return text;
  const lines = options.map((o, i) => `${["1️⃣", "2️⃣", "3️⃣", "4️⃣"][i]} ${o}`);
  return `${text}\n\n📊 ${lines.join("\n")}\n-# голосование — в моде MineFeed`;
}

async function pollOptions(env, postId) {
  const p = await env.DB.prepare("SELECT options FROM polls WHERE post_id = ?").bind(postId).first();
  return p ? JSON.parse(p.options) : null;
}

/** POST /v2/posts/:id/vote {option: index, or -1 to take the vote back} */
export async function vote(env, account, id, body, now) {
  const post = await loadPost(env, id);
  await spaceFor(env, account, post.space);
  const options = await pollOptions(env, post.id);
  if (!options) throw new HttpError(404, "no_poll", "В этом посте нет опроса");
  const option = Number.isInteger(body.option) ? body.option : null;
  if (option === null || option < -1 || option >= options.length) throw new HttpError(400, "bad_option", "Неизвестный вариант");
  if (post.solved) throw new HttpError(400, "closed", "Опрос закрыт");
  await rateLimit(env, "vote", account.id, now);
  const stmts = [env.DB.prepare("DELETE FROM poll_votes WHERE post_id = ? AND account_id = ?").bind(post.id, account.id)];
  if (option >= 0) {
    stmts.push(env.DB.prepare("INSERT INTO poll_votes (post_id, account_id, option, created_at) VALUES (?, ?, ?, ?)").bind(post.id, account.id, option, now));
  }
  stmts.push(env.DB.prepare("UPDATE posts SET updated_at = ? WHERE id = ?").bind(now, post.id));
  await env.DB.batch(stmts);
  return { poll: (await pollsFor(env, account, [post.id]))[post.id] };
}

// ---------- pins ----------

/** POST /v2/posts/:id/pin {pinned} — community owner/mod or a server moderator; at most MAX_PINS per space. */
export async function pin(env, account, id, body, now) {
  const post = await loadPost(env, id);
  const space = await spaceForModeration(env, account, post.space);
  const allowed = !!account.moderator || (post.space !== "global" && (space.role === "owner" || space.role === "mod"));
  if (!allowed) throw new HttpError(403, "forbidden", "Закреплять могут только модераторы");
  const pinned = body.pinned === undefined ? !post.pinned : !!body.pinned;
  if (pinned && !post.pinned) {
    const { results } = await env.DB.prepare(
      "SELECT id FROM posts WHERE space = ? AND pinned > 0 AND deleted = 0 ORDER BY pinned DESC"
    ).bind(post.space).all();
    for (const old of results.slice(MAX_PINS - 1)) {
      await env.DB.prepare("UPDATE posts SET pinned = 0, updated_at = ? WHERE id = ?").bind(now, old.id).run();
    }
  }
  await env.DB.prepare("UPDATE posts SET pinned = ?, updated_at = ? WHERE id = ?").bind(pinned ? now : 0, now, post.id).run();
  if (account.id !== post.author_id) {
    await modLog(env, account.id, pinned ? "pin" : "unpin", { target: post.author_id, postId: post.id, space: post.space }, now);
  }
  const [out] = await postsOut(env, account, [await env.DB.prepare(`${POST_SELECT} WHERE p.id = ?`).bind(post.id).first()]);
  return { post: out };
}

function cursorOf(r) {
  return `${r.created_at}_${r.id}`;
}

function parseCursor(raw) {
  const m = /^(\d{1,15})_(\d{1,20})$/.exec(String(raw || ""));
  return m ? [Number(m[1]), m[2]] : null;
}

function intParam(raw, def, min, max) {
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : def;
}

export async function loadPost(env, id) {
  if (!/^\d{1,20}$/.test(String(id))) throw new HttpError(404, "no_post", "Пост не найден");
  const row = await env.DB.prepare(`${POST_SELECT} WHERE p.id = ?`).bind(id).first();
  if (!row || row.deleted) throw new HttpError(404, "no_post", "Пост удалён или не найден");
  return row;
}

/** Author, server moderator, or owner/mod of the community the post is in. */
function canModerate(account, authorId, space) {
  return account.id === authorId || !!account.moderator || (space && (space.role === "owner" || space.role === "mod"));
}

/** Space check that lets server moderators act on posts they can't otherwise open. */
export async function spaceForModeration(env, account, id) {
  try {
    return await spaceFor(env, account, id);
  } catch (e) {
    if (account.moderator && e.code === "no_space") return { id, role: null };
    throw e;
  }
}

// ---------- reading ----------

/** GET /v2/feed?space=&type=rip|help|gg|talk|mine&before=&limit= */
export async function feed(env, account, q, now) {
  const space = await spaceFor(env, account, q.get("space"));
  const limit = intParam(q.get("limit"), PAGE, 1, 50);
  const type = q.get("type");
  const where = ["p.space = ?", "p.deleted = 0"];
  const binds = [space.id];
  if (TYPES.includes(type)) { where.push("p.type = ?"); binds.push(type); }
  if (type === "mine") { where.push("p.author_id = ?"); binds.push(account.id); }
  const hashtag = H.feedTag(q.get("tag"));
  if (hashtag) { where.push("p.id IN (SELECT post_id FROM post_tags WHERE tag = ? AND space = ?)"); binds.push(hashtag, space.id); }
  const authorId = q.get("author");
  if (authorId && /^[0-9a-f-]{36}$/.test(authorId)) { where.push("p.author_id = ?"); binds.push(authorId); }
  const top = q.get("sort") === "top";
  if (top) {
    // most reacted/commented posts of the last day or week; one page, no cursor
    const days = q.get("days") === "7" ? 7 : 1;
    where.push("p.created_at > ?"); binds.push(now - days * DAY);
    const { results } = await env.DB.prepare(
      `${POST_SELECT} WHERE ${where.join(" AND ")} AND (p.re0 + p.re1 + p.comments) > 0
       ORDER BY (p.re0 + p.re1) * 2 + p.comments DESC, p.created_at DESC LIMIT ?`
    ).bind(...binds, Math.min(limit, 30)).all();
    return { posts: await postsOut(env, account, results), next: null, now };
  }
  const before = parseCursor(q.get("before"));
  if (before) { where.push("(p.created_at, p.id) < (?, ?)"); binds.push(...before); }
  const { results } = await env.DB.prepare(
    `${POST_SELECT} WHERE ${where.join(" AND ")} ORDER BY p.created_at DESC, p.id DESC LIMIT ?`
  ).bind(...binds, limit + 1).all();
  let pinned;
  if (!before && !hashtag && !authorId && !type) {
    const pr = await env.DB.prepare(
      `${POST_SELECT} WHERE p.space = ? AND p.pinned > 0 AND p.deleted = 0 ORDER BY p.pinned DESC LIMIT ${MAX_PINS}`
    ).bind(space.id).all();
    pinned = await postsOut(env, account, pr.results);
  }
  if (!before && space.id !== "global") {
    await env.DB.prepare("UPDATE members SET read_at = ? WHERE community_id = ? AND account_id = ?").bind(now, space.id, account.id).run();
  }
  const more = results.length > limit;
  const rows = results.slice(0, limit);
  return { posts: await postsOut(env, account, rows), next: more ? cursorOf(rows[rows.length - 1]) : null, now, ...(pinned ? { pinned } : {}) };
}

/** GET /v2/posts/:id — the post and its latest comments. */
export async function getPost(env, account, id, now) {
  const row = await loadPost(env, id);
  await spaceFor(env, account, row.space);
  const [post] = await postsOut(env, account, [row]);
  const page = await commentsPage(env, row.id, null);
  return { post, ...page, now };
}

async function commentsPage(env, postId, before) {
  const where = ["c.post_id = ?", "c.deleted = 0"];
  const binds = [postId];
  if (before) { where.push("(c.created_at, c.id) < (?, ?)"); binds.push(...before); }
  const { results } = await env.DB.prepare(
    `${COMMENT_SELECT} WHERE ${where.join(" AND ")} ORDER BY c.created_at DESC, c.id DESC LIMIT ?`
  ).bind(...binds, COMMENTS_PAGE + 1).all();
  const more = results.length > COMMENTS_PAGE;
  const rows = results.slice(0, COMMENTS_PAGE).reverse(); // oldest first for display
  await freshen(env, rows, "comments");
  return { comments: rows.map(commentOut), before: more ? cursorOf(rows[0]) : null };
}

/** GET /v2/posts/:id/comments?before= — older comments. */
export async function comments(env, account, id, q) {
  const row = await loadPost(env, id);
  await spaceFor(env, account, row.space);
  return commentsPage(env, row.id, parseCursor(q.get("before")));
}

/**
 * GET /v2/sync?since=&space=&post= — one cheap call the mod repeats:
 * changed posts in the space, changed comments of the open post, new notifications.
 */
export async function sync(env, account, q, now) {
  const space = await spaceFor(env, account, q.get("space"));
  const since = intParam(q.get("since"), 0, 0, now);
  const interval = { open: intParam(env.SYNC_OPEN, 12, 5, 600), closed: intParam(env.SYNC_CLOSED, 120, 30, 3600) };
  const notifications = await N.changes(env, account, since ? since - SYNC_OVERLAP : 0);
  const communities = await unreadCounts(env, account);
  if (!since || now - since > SYNC_MAX_AGE) return { now, reset: true, posts: [], comments: [], notifications, communities, interval };
  const from = since - SYNC_OVERLAP;
  const { results } = await env.DB.prepare(
    `${POST_SELECT} WHERE p.space = ? AND p.updated_at > ? ORDER BY p.updated_at LIMIT 101`
  ).bind(space.id, from).all();
  if (results.length > 100) return { now, reset: true, posts: [], comments: [], notifications, communities, interval };
  const out = { now, reset: false, posts: await postsOut(env, account, results), comments: [], notifications, communities, interval };
  const postId = q.get("post");
  if (postId && /^\d{1,20}$/.test(postId)) {
    const c = await env.DB.prepare(
      `${COMMENT_SELECT} WHERE c.post_id = ? AND c.updated_at > ? ORDER BY c.updated_at LIMIT 101`
    ).bind(postId, from).all();
    out.comments = (await freshen(env, c.results.slice(0, 100), "comments")).map(commentOut);
  }
  return out;
}

/** [{id, unread}] for every community the player is in (new posts by others since last open). */
async function unreadCounts(env, account) {
  const { results } = await env.DB.prepare(
    `SELECT m.community_id AS id,
       (SELECT COUNT(*) FROM posts p WHERE p.space = m.community_id AND p.deleted = 0 AND p.created_at > m.read_at
          AND (p.author_id IS NULL OR p.author_id <> m.account_id)) AS unread
     FROM members m WHERE m.account_id = ?`
  ).bind(account.id).all();
  return results;
}

// ---------- writing ----------

/** POST /v2/posts {space, type, text, image?: {data: base64 PNG/JPEG}} — text may be empty with a picture. */
export async function createPost(env, account, body, now) {
  const space = await spaceFor(env, account, body.space);
  if (!TYPES.includes(body.type)) throw new HttpError(400, "bad_type", "Неизвестный тип поста");
  const file = body.image ? parseUpload(body.image) : null;
  const poll = parsePoll(body.poll);
  const text = file && !poll && !String(body.text ?? "").trim() ? "" : cleanText(body.text, MAX_POST);
  await rateLimit(env, "post", account.id, now);
  if (file) await rateLimit(env, "image", account.id, now);
  const tag = space.tagIds[body.type];
  const msg = await postAs(env, space, account, {
    content: discordContent(text, poll),
    thread_name: text ? threadName(text) : `📷 ${account.nick}`,
    ...(tag ? { applied_tags: [tag] } : {}),
  }, null, file ? [file] : null);
  const id = msg.channel_id;
  const att = (msg.attachments || [])[0];
  const image = file && att ? JSON.stringify([{ url: att.url, w: att.width || file.w, h: att.height || file.h }]) : null;
  await env.DB.prepare(
    "INSERT INTO posts (id, space, author_id, type, text, image, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
  ).bind(id, space.id, account.id, body.type, text, image, now, now).run();
  if (poll) await env.DB.prepare("INSERT INTO polls (post_id, options) VALUES (?, ?)").bind(id, JSON.stringify(poll)).run();
  await H.indexPost(env, id, space.id, text, now);
  await N.mentions(env, account, text, { postId: id, space: space.id, skip: [] }, now);
  const [out] = await postsOut(env, account, [await loadPost(env, id)]);
  return { post: out };
}

/**
 * Pinned first post of a new community, written as its owner, plus an optional first comment.
 * System text, so the usual length limits don't apply (Discord max is 2000).
 * Returns the ids to edit them later: { thread, comment }.
 */
export async function publishWelcome(env, spaceId, owner, { title, text, comment }, now) {
  // Discord only: the mod doesn't show it. Not indexed in D1, so the bot ignores replies there too.
  const space = await getSpace(env, spaceId);
  const msg = await postAs(env, space, owner, {
    content: text,
    thread_name: threadName(title),
    ...(space.tagIds.talk ? { applied_tags: [space.tagIds.talk] } : {}),
  });
  const id = msg.channel_id;
  const c = comment ? await postAs(env, space, owner, { content: comment }, id) : null;
  // pin + lock: the welcome thread is read-only (Manage Threads can still write)
  await dapi(env, "PATCH", `/channels/${id}`, { body: { flags: 2, locked: true }, allow404: true }).catch(() => {});
  return { thread: id, comment: c ? c.id : null };
}

/**
 * Rewrites the welcome post after the owner edits the community. The comment is edited,
 * added or removed to match. Returns the comment id that now exists (or null).
 */
export async function updateWelcome(env, spaceId, owner, { threadId, commentId }, { title, text, comment }) {
  const space = await getSpace(env, spaceId);
  const hook = `/webhooks/${space.webhook_id}/${space.webhook_token}/messages`;
  const q = `?thread_id=${threadId}`;
  await dapi(env, "PATCH", `/channels/${threadId}`, { body: { name: threadName(title) }, allow404: true });
  await dapi(env, "PATCH", `${hook}/${threadId}${q}`, { auth: false, body: { content: text, allowed_mentions: { parse: [] } }, allow404: true });
  if (commentId && comment) {
    const r = await dapi(env, "PATCH", `${hook}/${commentId}${q}`, { auth: false, body: { content: comment, allowed_mentions: { parse: [] } }, allow404: true });
    if (r) return commentId;
  } else if (commentId) {
    await dapi(env, "DELETE", `${hook}/${commentId}${q}`, { auth: false, allow404: true });
    return null;
  }
  if (!comment) return null;
  // the thread is locked: open it for a moment so the webhook may write
  await dapi(env, "PATCH", `/channels/${threadId}`, { body: { locked: false }, allow404: true });
  try {
    const m = await postAs(env, space, owner, { content: comment }, threadId);
    return m.id;
  } finally {
    await dapi(env, "PATCH", `/channels/${threadId}`, { body: { locked: true }, allow404: true }).catch(() => {});
  }
}

/** POST /v2/posts/:id/comments {text} */
export async function createComment(env, account, id, body, now) {
  const post = await loadPost(env, id);
  const space = await spaceFor(env, account, post.space);
  const text = cleanText(body.text, MAX_COMMENT);
  await rateLimit(env, "comment", account.id, now);
  let msg;
  try {
    msg = await postAs(env, space, account, { content: text }, post.id);
  } catch (e) {
    if (e.discordStatus === 404) { // thread was removed in Discord
      await markPostDeleted(env, post.id, now);
      throw new HttpError(404, "no_post", "Пост удалён или не найден");
    }
    throw e;
  }
  await env.DB.batch([
    env.DB.prepare("INSERT INTO comments (id, post_id, author_id, text, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")
      .bind(msg.id, post.id, account.id, text, now, now),
    env.DB.prepare("UPDATE posts SET comments = comments + 1, updated_at = ? WHERE id = ?").bind(now, post.id),
  ]);
  const notified = [];
  if (post.author_id && post.author_id !== account.id && await N.isReader(env, post.space, post.author_id)) {
    await N.add(env, { to: post.author_id, kind: "comment", postId: post.id, commentId: msg.id, actor: account, text }, now);
    notified.push(post.author_id);
  }
  await N.mentions(env, account, text, { postId: post.id, commentId: msg.id, space: post.space, skip: notified }, now);
  const row = await env.DB.prepare(`${COMMENT_SELECT} WHERE c.id = ?`).bind(msg.id).first();
  return { comment: commentOut(row) };
}

/** PATCH /v2/posts/:id {text} — the author rewrites the text (the picture stays). */
export async function editPost(env, account, id, body, now) {
  const post = await loadPost(env, id);
  const space = await spaceFor(env, account, post.space);
  if (post.author_id !== account.id) throw new HttpError(403, "forbidden", "Можно менять только свои посты");
  const text = post.image && !String(body.text ?? "").trim() ? "" : cleanText(body.text, MAX_POST);
  if (text === post.text) return { post: postOut(await loadPost(env, id), -1) };
  await rateLimit(env, "edit", account.id, now);
  const hook = `/webhooks/${space.webhook_id}/${space.webhook_token}/messages/${post.id}?thread_id=${post.id}`;
  try {
    await dapi(env, "PATCH", hook, { auth: false, body: { content: discordContent(text, await pollOptions(env, post.id)), allowed_mentions: { parse: [] } } });
  } catch (e) {
    if (e.discordStatus !== 404) throw e;
    await markPostDeleted(env, post.id, now);
    throw new HttpError(404, "no_post", "Пост удалён или не найден");
  }
  const name = text ? threadName(text) : `📷 ${account.nick}`;
  if (name !== (post.text ? threadName(post.text) : `📷 ${account.nick}`)) {
    // the thread title follows the first line; Discord allows few renames, so this is best effort
    await dapi(env, "PATCH", `/channels/${post.id}`, { body: { name }, allow404: true }).catch(() => {});
  }
  await env.DB.prepare("UPDATE posts SET text = ?, edited_at = ?, updated_at = ? WHERE id = ?").bind(text, now, now, post.id).run();
  await H.indexPost(env, post.id, post.space, text, post.created_at);
  const [out] = await postsOut(env, account, [await env.DB.prepare(`${POST_SELECT} WHERE p.id = ?`).bind(post.id).first()]);
  return { post: out };
}

/** PATCH /v2/comments/:id {text} — the author rewrites a comment. */
export async function editComment(env, account, id, body, now) {
  if (!/^\d{1,20}$/.test(String(id))) throw new HttpError(404, "no_comment", "Комментарий не найден");
  const c = await env.DB.prepare("SELECT * FROM comments WHERE id = ? AND deleted = 0").bind(id).first();
  if (!c) throw new HttpError(404, "no_comment", "Комментарий не найден");
  const post = await loadPost(env, c.post_id);
  const space = await spaceFor(env, account, post.space);
  if (c.author_id !== account.id) throw new HttpError(403, "forbidden", "Можно менять только свои комментарии");
  const text = c.image && !String(body.text ?? "").trim() ? "" : cleanText(body.text, MAX_COMMENT);
  if (text !== c.text) {
    await rateLimit(env, "edit", account.id, now);
    const hook = `/webhooks/${space.webhook_id}/${space.webhook_token}/messages/${c.id}?thread_id=${post.id}`;
    try {
      await dapi(env, "PATCH", hook, { auth: false, body: { content: text, allowed_mentions: { parse: [] } } });
    } catch (e) {
      if (e.discordStatus !== 404) throw e;
      await markCommentDeleted(env, c.id, post.id, now);
      throw new HttpError(404, "no_comment", "Комментарий удалён");
    }
    await env.DB.batch([
      env.DB.prepare("UPDATE comments SET text = ?, edited_at = ?, updated_at = ? WHERE id = ?").bind(text, now, now, c.id),
      env.DB.prepare("UPDATE posts SET updated_at = ? WHERE id = ?").bind(now, post.id),
    ]);
  }
  const row = await env.DB.prepare(`${COMMENT_SELECT} WHERE c.id = ?`).bind(c.id).first();
  return { comment: commentOut(row) };
}

export async function markPostDeleted(env, id, now) {
  await env.DB.prepare("UPDATE posts SET deleted = 1, updated_at = ? WHERE id = ? AND deleted = 0").bind(now, id).run();
  await closeReports(env, { postId: id, wholePost: true }, "done", "gone", null, now);
}

/** DELETE /v2/posts/:id — author or moderator. */
export async function deletePost(env, account, id, now) {
  const post = await loadPost(env, id);
  const space = await spaceForModeration(env, account, post.space);
  if (!canModerate(account, post.author_id, space)) throw new HttpError(403, "forbidden", "Можно удалять только свои посты");
  await dapi(env, "DELETE", `/channels/${post.id}`, { allow404: true });
  if (account.id !== post.author_id) {
    await closeReports(env, { postId: post.id, wholePost: true }, "done", "deleted", account.id, now);
    await modLog(env, account.id, "delete_post", { target: post.author_id, postId: post.id, space: post.space }, now);
  }
  await markPostDeleted(env, post.id, now);
  return { ok: true };
}

/** DELETE /v2/comments/:id — comment author, post author or moderator. */
export async function deleteComment(env, account, id, now) {
  if (!/^\d{1,20}$/.test(String(id))) throw new HttpError(404, "no_comment", "Комментарий не найден");
  const c = await env.DB.prepare("SELECT * FROM comments WHERE id = ? AND deleted = 0").bind(id).first();
  if (!c) throw new HttpError(404, "no_comment", "Комментарий не найден");
  const post = await env.DB.prepare("SELECT author_id, space FROM posts WHERE id = ?").bind(c.post_id).first();
  const space = await spaceForModeration(env, account, post ? post.space : "global");
  if (account.id !== c.author_id && !canModerate(account, post && post.author_id, space)) {
    throw new HttpError(403, "forbidden", "Можно удалять только свои комментарии");
  }
  await dapi(env, "DELETE", `/channels/${c.post_id}/messages/${c.id}`, { allow404: true });
  if (account.id !== c.author_id) {
    await closeReports(env, { postId: c.post_id, commentId: c.id }, "done", "deleted", account.id, now);
    await modLog(env, account.id, "delete_comment", { target: c.author_id, postId: c.post_id, commentId: c.id, space: post ? post.space : null }, now);
  }
  await markCommentDeleted(env, c.id, c.post_id, now);
  return { ok: true };
}

export async function markCommentDeleted(env, id, postId, now) {
  const r = await env.DB.prepare("UPDATE comments SET deleted = 1, updated_at = ? WHERE id = ? AND deleted = 0").bind(now, id).run();
  if (r.meta.changes) {
    await env.DB.prepare("UPDATE posts SET comments = MAX(comments - 1, 0), updated_at = ? WHERE id = ?").bind(now, postId).run();
  }
  await closeReports(env, { postId, commentId: id }, "done", "gone", null, now);
}

/** POST /v2/posts/:id/react {slot: 0|1} — toggles; picking the other slot switches. */
export async function react(env, account, id, body, now) {
  const slot = body.slot === 0 || body.slot === 1 ? body.slot : null;
  if (slot === null) throw new HttpError(400, "bad_slot", "Неизвестная реакция");
  const post = await loadPost(env, id);
  await spaceFor(env, account, post.space);
  const cur = await env.DB.prepare("SELECT slot FROM reactions WHERE post_id = ? AND account_id = ?").bind(post.id, account.id).first();
  const adding = !cur || cur.slot !== slot;
  // counts are adjusted from the rows actually present, so retries and races stay consistent
  const stmts = [
    env.DB.prepare(
      `UPDATE posts SET
         re0 = MAX(re0 - (SELECT COUNT(*) FROM reactions WHERE post_id = ?1 AND account_id = ?2 AND slot = 0), 0),
         re1 = MAX(re1 - (SELECT COUNT(*) FROM reactions WHERE post_id = ?1 AND account_id = ?2 AND slot = 1), 0),
         updated_at = ?3
       WHERE id = ?1`
    ).bind(post.id, account.id, now),
    env.DB.prepare("DELETE FROM reactions WHERE post_id = ? AND account_id = ?").bind(post.id, account.id),
  ];
  if (adding) {
    stmts.push(
      env.DB.prepare("INSERT INTO reactions (post_id, account_id, slot, created_at) VALUES (?, ?, ?, ?)").bind(post.id, account.id, slot, now),
      env.DB.prepare(`UPDATE posts SET re${slot} = re${slot} + 1 WHERE id = ?`).bind(post.id),
    );
  }
  await env.DB.batch(stmts);
  const row = await env.DB.prepare("SELECT re0, re1 FROM posts WHERE id = ?").bind(post.id).first();
  if (adding && post.author_id && post.author_id !== account.id && await N.isReader(env, post.space, post.author_id)) {
    await N.react(env, { to: post.author_id, postId: post.id, actor: account, count: row.re0 + row.re1, text: post.text }, now);
  }
  return { reactions: [row.re0, row.re1], my: adding ? slot : -1 };
}

/** POST /v2/posts/:id/solve {solved} — author of a help post marks it solved. */
export async function solve(env, account, id, body, now) {
  const post = await loadPost(env, id);
  if (post.type !== "help") throw new HttpError(400, "not_help", "Решённым можно отметить только вопрос");
  const access = await spaceForModeration(env, account, post.space);
  if (!canModerate(account, post.author_id, access)) throw new HttpError(403, "forbidden", "Только автор может отметить вопрос решённым");
  const solved = body.solved === undefined ? !post.solved : !!body.solved;
  const space = await getSpace(env, post.space);
  const tags = [space.tagIds.help, solved ? space.tagIds.solved : null].filter(Boolean);
  // solved questions are locked in Discord too (no new replies there; the mod already blocks them)
  const patch = { locked: solved };
  if (tags.length) patch.applied_tags = tags;
  await dapi(env, "PATCH", `/channels/${post.id}`, { body: patch, allow404: true });
  await env.DB.prepare("UPDATE posts SET solved = ?, updated_at = ? WHERE id = ?").bind(solved ? 1 : 0, now, post.id).run();
  return { post: postOut(await loadPost(env, post.id), (await myReactions(env, account, [post.id]))[post.id]) };
}

