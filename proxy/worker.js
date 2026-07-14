// MineFeed worker v9 — D1 + in-memory snapshots. KV не используется.
// Secrets: DISCORD_BOT_TOKEN, APP_TOKEN, ADMIN_TOKEN (admin only)
// Vars: GUILD_ID, FORUM_CHANNEL_ID, REPORT_CHANNEL_ID (optional), DEBUG (optional)
// Bindings: DB = D1. KV не нужен.
// Cron: */5 * * * *
//
// Read-путь не делает НИ ОДНОЙ записи. AFK-клиент бесплатен.
// Do not join — split URLs survive `strings` on the jar.
const DISCORD_API = "https:" + "//discord.com/api/v10";
const AVATAR_API = "https:" + "//mc-heads.net/avatar/";

const FEED_SNAPSHOT_TTL = 8_000;   // память изолята
const THREADS_TTL = 30_000;
const COMMENTS_TTL = 10_000;
const TAGS_TTL = 60_000;
const FEED_PAGE = 20;

const RATE_LIMIT_TTL = 30;
const COMMENT_RATE_TTL = 10;
const WRITE_BURST_LIMIT = 20;
const REACT_BURST_LIMIT = 30;
const DEDUP_TTL = 600;
const MAX_TEXT = 500;
const MAX_COMMENT = 300;
const PAGE_LIMIT = 50;
const NOTIF_CAP = 50;
const MAX_TAGS = 8;
const CRON_THREAD_CAP = 8;
const CRON_MSG_LIMIT = 5;
const SOLVED_PREFIX = "[\u2713]";
const TAG_RE = /#([\p{L}\p{N}_]+)/gu;
const AUTHOR_RE = /^[a-zA-Z0-9_]{3,16}$/;

const TYPE_REACTIONS = {
  rip: ["heart", "coffin"],
  help: ["ring", "nerd"],
  gg: ["cup", "clover"],
  talk: ["fire", "zzz"],
};
const TYPE_PREFIX = { rip: "[F]", help: "[?]", gg: "[GG]", talk: "[~]" };
const TYPE_COLOR = { rip: 0xff5555, help: 0xffff55, gg: 0x55ff55, talk: 0x55ffff };

// --- in-memory cache (per isolate) ---
const mem = {
  feed: null,            // { at, data: { posts(+_votes), nextCursor } }
  threads: null,         // { at, list }
  comments: new Map(),   // postId -> { at, data }
  tags: null,            // { at, data }
  burst: new Map(),      // ip -> { t, n } быстрый пре-фильтр флуда
};

const SCHEMA = [
  "CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, type TEXT NOT NULL DEFAULT 'talk', author TEXT NOT NULL, uuid TEXT NOT NULL, text TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '[]', solved INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, comment_count INTEGER NOT NULL DEFAULT 0)",
  "CREATE INDEX IF NOT EXISTS idx_posts_uuid ON posts(uuid)",
  "CREATE TABLE IF NOT EXISTS reactions (post_id TEXT NOT NULL, uuid TEXT NOT NULL, key TEXT NOT NULL, PRIMARY KEY (post_id, uuid))",
  "CREATE TABLE IF NOT EXISTS post_tags (tag TEXT NOT NULL, post_id TEXT NOT NULL, PRIMARY KEY (tag, post_id))",
  "CREATE TABLE IF NOT EXISTS notifications (uuid TEXT NOT NULL, id TEXT NOT NULL, post_id TEXT NOT NULL, post_text TEXT NOT NULL DEFAULT '', author TEXT NOT NULL DEFAULT '', text TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL DEFAULT '', PRIMARY KEY (uuid, id))",
  "CREATE INDEX IF NOT EXISTS idx_notif_post ON notifications(post_id)",
  "CREATE TABLE IF NOT EXISTS comment_owners (msg_id TEXT PRIMARY KEY, uuid TEXT NOT NULL)",
  "CREATE TABLE IF NOT EXISTS bans (key TEXT PRIMARY KEY)",
  "CREATE TABLE IF NOT EXISTS limiter (key TEXT PRIMARY KEY, ts INTEGER NOT NULL, n INTEGER NOT NULL DEFAULT 0)",
  "CREATE TABLE IF NOT EXISTS dedup (uuid TEXT PRIMARY KEY, hash TEXT NOT NULL, ts INTEGER NOT NULL)",
  "CREATE TABLE IF NOT EXISTS sync_state (post_id TEXT PRIMARY KEY, last_msg_id TEXT NOT NULL)",
];
let schemaReady = false;
async function ensureSchema(env) {
  if (schemaReady) return;
  await env.DB.batch(SCHEMA.map((s) => env.DB.prepare(s)));
  schemaReady = true;
}

export default {
  async fetch(request, env, ctx) {
    try {
      const url = new URL(request.url);
      const p = url.pathname;
      if (p === "/v1/health") return json({ ok: true });
      await ensureSchema(env);

      if (p.startsWith("/v1/admin/")) {
        return await routeAdmin(env, request, url, p);
      }

      if (!env.APP_TOKEN || request.headers.get("X-Feed-Token") !== env.APP_TOKEN) {
        return json({ error: "unauthorized" }, 401);
      }

      if (p === "/v1/feed" && request.method === "GET") {
        return await handleFeed(env, url, request);
      }
      if (p === "/v1/posts" && request.method === "POST") {
        return await handleCreatePost(env, request);
      }
      if (p === "/v1/react" && request.method === "POST") {
        return await handleReact(env, request);
      }
      if (p === "/v1/report" && request.method === "POST") {
        return await handleReport(env, request, ctx);
      }
      if (p === "/v1/notifications" && request.method === "GET") {
        return await handleNotifications(env, url);
      }
      if (p === "/v1/tags" && request.method === "GET") {
        return await handleTags(env, url);
      }

      let m = /^\/v1\/posts\/(\d+)\/comments$/.exec(p);
      if (m && request.method === "GET") {
        return await handleGetComments(env, url, m[1], request, ctx);
      }
      if (m && request.method === "POST") {
        return await handleAddComment(env, request, m[1], ctx);
      }
      m = /^\/v1\/posts\/(\d+)\/comments\/(\d+)$/.exec(p);
      if (m && request.method === "DELETE") {
        return await handleDeleteComment(env, request, m[1], m[2]);
      }
      m = /^\/v1\/posts\/(\d+)\/solve$/.exec(p);
      if (m && request.method === "POST") {
        return await handleSolve(env, request, m[1]);
      }
      m = /^\/v1\/posts\/(\d+)$/.exec(p);
      if (m && request.method === "GET") {
        return await handleGetPost(env, url, m[1]);
      }
      if (m && request.method === "DELETE") {
        return await handleDeletePost(env, request, m[1], ctx);
      }

      return json({ error: "not_found" }, 404);
    } catch (e) {
      return errResponse(e, env);
    }
  },

  async scheduled(controller, env, ctx) {
    ctx.waitUntil(pollDiscordReplies(env));
  },
};

// --- helpers ---

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...extraHeaders },
  });
}

async function jsonEtag(data, request, status = 200) {
  const body = JSON.stringify(data);
  const hash = await sha1hex(body);
  const etag = '"' + hash + '"';
  if (request.headers.get("If-None-Match") === etag) {
    return new Response(null, { status: 304, headers: { etag } });
  }
  return new Response(body, {
    status,
    headers: { "content-type": "application/json; charset=utf-8", etag },
  });
}

function errResponse(e, env) {
  const detail = String((e && e.message) || e);
  const status = detail.startsWith("discord 403") ? 403
      : detail.startsWith("discord 404") ? 404
      : detail.startsWith("discord 429") ? 429
      : 500;
  const code = detail.startsWith("discord") ? "discord" : "internal";
  console.log("error:", detail);
  const payload = { error: code };
  if (env.DEBUG === "1") payload.detail = detail;
  return json(payload, status);
}

function ipOf(request) {
  return request.headers.get("CF-Connecting-IP") || "0.0.0.0";
}

function normalizeUuid(uuid) {
  const s = String(uuid || "").replace(/-/g, "").toLowerCase();
  if (s.length !== 32) return s;
  return s.slice(0, 8) + "-" + s.slice(8, 12) + "-" + s.slice(12, 16) + "-"
      + s.slice(16, 20) + "-" + s.slice(20);
}

function sanitizeName(name) {
  return String(name || "").replace(/\s+/g, " ").trim().slice(0, 96);
}

async function discord(env, method, path, body) {
  const res = await fetch(DISCORD_API + path, {
    method,
    headers: {
      Authorization: "Bot " + env.DISCORD_BOT_TOKEN,
      "content-type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return null;
  const raw = await res.text();
  let data = null;
  try {
    data = raw ? JSON.parse(raw) : null;
  } catch {
    data = null;
  }
  if (!res.ok) {
    const hint = (data && data.message) || raw || "";
    throw new Error("discord " + res.status + " " + path + (hint ? ": " + hint : ""));
  }
  return data;
}

function idLess(a, b) {
  if (a.length !== b.length) return a.length < b.length;
  return a < b;
}

function parseTags(text) {
  const tags = [];
  for (const m of text.matchAll(TAG_RE)) {
    const t = m[1].toLowerCase();
    if (!tags.includes(t)) tags.push(t);
    if (tags.length >= MAX_TAGS) break;
  }
  return tags;
}

async function sha1hex(text) {
  const buf = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

// --- identity ---

function parseIdentity(body) {
  if (!body) return { error: json({ error: "bad_json" }, 400) };
  const author = String(body.author || body.username || "").trim();
  const uuid = normalizeUuid(body.uuid || "");
  if (!AUTHOR_RE.test(author)) return { error: json({ error: "bad_author" }, 400) };
  if (!uuid || uuid.length > 40) return { error: json({ error: "bad_uuid" }, 400) };
  return { uuid, author };
}

function parseUuid(body) {
  if (!body) return { error: json({ error: "bad_json" }, 400) };
  const uuid = normalizeUuid(body.uuid || "");
  if (!uuid || uuid.length > 40) return { error: json({ error: "bad_uuid" }, 400) };
  return { uuid };
}

// --- rate limits & bans (D1 + память) ---

function memFlooding(ip) {
  const now = Date.now();
  const rec = mem.burst.get(ip);
  if (!rec || now - rec.t >= 60_000) {
    mem.burst.set(ip, { t: now, n: 1 });
    if (mem.burst.size > 2000) mem.burst.clear();
    return false;
  }
  rec.n += 1;
  return rec.n > 60; // жёсткий потолок на изолят; ниже — точный D1-лимит
}

async function banned(env, uuid, ip) {
  const row = await env.DB.prepare("SELECT key FROM bans WHERE key IN (?, ?) LIMIT 1")
    .bind("u:" + uuid, "ip:" + ip).first();
  return !!row;
}

async function cooldownActive(env, key, ttlSec) {
  const row = await env.DB.prepare("SELECT ts FROM limiter WHERE key = ?").bind(key).first();
  return !!(row && Date.now() - row.ts < ttlSec * 1000);
}

function stampStmt(env, key) {
  return env.DB.prepare("INSERT OR REPLACE INTO limiter (key, ts, n) VALUES (?, ?, 0)")
    .bind(key, Date.now());
}

async function burstLimited(env, key, limit) {
  const now = Date.now();
  const row = await env.DB.prepare("SELECT ts, n FROM limiter WHERE key = ?").bind(key).first();
  if (!row || now - row.ts >= 60_000) {
    await env.DB.prepare("INSERT OR REPLACE INTO limiter (key, ts, n) VALUES (?, ?, 1)")
      .bind(key, now).run();
    return false;
  }
  await env.DB.prepare("UPDATE limiter SET n = n + 1 WHERE key = ?").bind(key).run();
  return row.n + 1 > limit;
}

async function duplicate(env, uuid, text) {
  const hash = await sha1hex(text.trim().toLowerCase());
  const row = await env.DB.prepare("SELECT hash, ts FROM dedup WHERE uuid = ?").bind(uuid).first();
  return !!(row && row.hash === hash && Date.now() - row.ts < DEDUP_TTL * 1000);
}

async function markWrittenStmt(env, uuid, text) {
  const hash = await sha1hex(text.trim().toLowerCase());
  return env.DB.prepare("INSERT OR REPLACE INTO dedup (uuid, hash, ts) VALUES (?, ?, ?)")
    .bind(uuid, hash, Date.now());
}

async function writeGuard(env, request, uuid, kind) {
  const ip = ipOf(request);
  if (memFlooding(ip)) return json({ error: "rate_limited" }, 429);
  if (await banned(env, uuid, ip)) return json({ error: "banned" }, 403);
  if (kind === "react") {
    if (await burstLimited(env, "ipr:" + ip, REACT_BURST_LIMIT)) {
      return json({ error: "rate_limited" }, 429);
    }
    return null;
  }
  if (await burstLimited(env, "ipw:" + ip, WRITE_BURST_LIMIT)) {
    return json({ error: "rate_limited" }, 429);
  }
  if (kind === "post") {
    if (await cooldownActive(env, "u:" + uuid, RATE_LIMIT_TTL)) {
      return json({ error: "rate_limited" }, 429);
    }
    if (await cooldownActive(env, "ip:" + ip, RATE_LIMIT_TTL)) {
      return json({ error: "rate_limited" }, 429);
    }
  } else if (kind === "comment") {
    if (await cooldownActive(env, "cu:" + uuid, COMMENT_RATE_TTL)) {
      return json({ error: "comment_rate_limited" }, 429);
    }
    if (await cooldownActive(env, "cip:" + ip, COMMENT_RATE_TTL)) {
      return json({ error: "comment_rate_limited" }, 429);
    }
  }
  return null;
}

function stampStmtsFor(env, request, uuid, kind) {
  const ip = ipOf(request);
  if (kind === "post") return [stampStmt(env, "u:" + uuid), stampStmt(env, "ip:" + ip)];
  if (kind === "comment") return [stampStmt(env, "cu:" + uuid), stampStmt(env, "cip:" + ip)];
  return [];
}

// --- posts: D1 → API shape ---

function resolveCommentCount(discordMessageCount, dbCount) {
  if (discordMessageCount !== undefined && discordMessageCount !== null) {
    const discordReplies = Math.max(0, (discordMessageCount | 0) - 1);
    return Math.max(dbCount || 0, discordReplies);
  }
  return dbCount || 0;
}

function rowToPost(row, messageCount) {
  return {
    id: row.id,
    type: row.type,
    author: row.author,
    uuid: row.uuid,
    text: row.text,
    tags: JSON.parse(row.tags || "[]"),
    solved: !!row.solved,
    createdAt: row.created_at,
    commentCount: resolveCommentCount(messageCount, row.comment_count),
  };
}

function tallyPair(type, votes) {
  const pair = TYPE_REACTIONS[type] || TYPE_REACTIONS.talk;
  const counts = {};
  for (const k of pair) counts[k] = 0;
  for (const k of Object.values(votes)) {
    if (counts[k] !== undefined) counts[k] += 1;
  }
  return counts;
}

function personalize(posts, uuid) {
  return posts.map((p) => {
    const { _votes, ...rest } = p;
    return { ...rest, myReaction: uuid ? (_votes && _votes[uuid]) || null : null };
  });
}

/** Посты + голоса для списка id. 2 SQL-запроса на любую страницу. */
async function buildPosts(env, ids, msgCountById) {
  if (!ids.length) return [];
  const ph = ids.map(() => "?").join(",");
  const [postsRes, votesRes] = await env.DB.batch([
    env.DB.prepare("SELECT * FROM posts WHERE id IN (" + ph + ")").bind(...ids),
    env.DB.prepare("SELECT post_id, uuid, key FROM reactions WHERE post_id IN (" + ph + ")").bind(...ids),
  ]);
  const votesBy = {};
  for (const r of votesRes.results) (votesBy[r.post_id] ||= {})[r.uuid] = r.key;
  const byId = {};
  for (const row of postsRes.results) byId[row.id] = row;
  const out = [];
  for (const id of ids) {
    const row = byId[id];
    if (!row) continue;
    const votes = votesBy[id] || {};
    out.push({
      ...rowToPost(row, msgCountById ? msgCountById[id] : undefined),
      reactions: tallyPair(row.type, votes),
      _votes: votes,
    });
  }
  return out;
}

// --- feed ---

async function getThreads(env) {
  if (mem.threads && Date.now() - mem.threads.at < THREADS_TTL) return mem.threads.list;
  let data;
  try {
    data = await discord(env, "GET", "/guilds/" + env.GUILD_ID + "/threads/active");
  } catch (e) {
    if (mem.threads) return mem.threads.list; // stale лучше, чем ошибка
    throw e;
  }
  const list = (data.threads || [])
    .filter((t) => t.parent_id === env.FORUM_CHANNEL_ID)
    .map((t) => ({ id: t.id, message_count: t.message_count || 0 }));
  list.sort((a, b) => (idLess(a.id, b.id) ? 1 : -1));
  mem.threads = { at: Date.now(), list };
  return list;
}

async function getMainFeedSnapshot(env) {
  if (mem.feed && Date.now() - mem.feed.at < FEED_SNAPSHOT_TTL) return mem.feed.data;
  const threads = await getThreads(env);
  const page = threads.slice(0, FEED_PAGE);
  const msgCountById = {};
  for (const t of page) msgCountById[t.id] = t.message_count;
  const posts = await buildPosts(env, page.map((t) => t.id), msgCountById);
  const nextCursor = threads.length > FEED_PAGE && page.length
      ? page[page.length - 1].id : null;
  const data = { posts, nextCursor };
  mem.feed = { at: Date.now(), data };
  return data;
}

function invalidateFeed() {
  mem.feed = null;
  mem.threads = null;
  mem.tags = null;
}

async function idFeed(env, sql, binds, limit, uuid) {
  const res = await env.DB.prepare(sql).bind(...binds).all();
  const ids = res.results.map((r) => r.id);
  const built = await buildPosts(env, ids, undefined);
  const posts = personalize(built, uuid);
  const nextCursor = ids.length === limit ? ids[ids.length - 1] : null;
  return { posts, nextCursor };
}

async function handleFeed(env, url, request) {
  const limit = Math.min(parseInt(url.searchParams.get("limit") || "20", 10) || 20, 50);
  const cursor = url.searchParams.get("cursor") || "";
  const uuid = url.searchParams.get("uuid") || "";
  const tag = (url.searchParams.get("tag") || "").toLowerCase().replace(/^#/, "");
  const mine = url.searchParams.get("mine") === "1" || url.searchParams.get("mine") === "true";

  if (tag) {
    const sql = cursor
        ? "SELECT post_id AS id FROM post_tags WHERE tag = ? AND CAST(post_id AS INTEGER) < CAST(? AS INTEGER) ORDER BY CAST(post_id AS INTEGER) DESC LIMIT ?"
        : "SELECT post_id AS id FROM post_tags WHERE tag = ? ORDER BY CAST(post_id AS INTEGER) DESC LIMIT ?";
    const binds = cursor ? [tag, cursor, limit] : [tag, limit];
    return json(await idFeed(env, sql, binds, limit, uuid));
  }
  if (mine) {
    if (!uuid || uuid.length > 40) return json({ error: "bad_uuid" }, 400);
    const u = normalizeUuid(uuid);
    const sql = cursor
        ? "SELECT id FROM posts WHERE uuid = ? AND CAST(id AS INTEGER) < CAST(? AS INTEGER) ORDER BY CAST(id AS INTEGER) DESC LIMIT ?"
        : "SELECT id FROM posts WHERE uuid = ? ORDER BY CAST(id AS INTEGER) DESC LIMIT ?";
    const binds = cursor ? [u, cursor, limit] : [u, limit];
    return json(await idFeed(env, sql, binds, limit, uuid));
  }
  if (cursor) {
    // старые страницы: список тредов уже в памяти, D1 — только чтение
    const threads = await getThreads(env);
    const list = threads.filter((t) => idLess(t.id, cursor));
    const page = list.slice(0, limit);
    const msgCountById = {};
    for (const t of page) msgCountById[t.id] = t.message_count;
    const built = await buildPosts(env, page.map((t) => t.id), msgCountById);
    const posts = personalize(built, uuid);
    const nextCursor = list.length > limit && page.length ? page[page.length - 1].id : null;
    return json({ posts, nextCursor });
  }

  // главная страница: один снапшот на всех, персонализация на лету
  const snap = await getMainFeedSnapshot(env);
  const result = {
    posts: personalize(snap.posts.slice(0, limit), uuid),
    nextCursor: snap.nextCursor,
  };
  return jsonEtag(result, request);
}

async function getPostRow(env, postId) {
  return env.DB.prepare("SELECT * FROM posts WHERE id = ?").bind(postId).first();
}

async function handleGetPost(env, url, postId) {
  const uuid = url.searchParams.get("uuid") || "";
  const built = await buildPosts(env, [postId], undefined);
  if (!built.length) return json({ error: "not_found" }, 404);
  return json({ post: personalize(built, uuid)[0] });
}

// --- reactions ---

async function handleReact(env, request) {
  const body = await request.json().catch(() => null);
  if (!body) return json({ error: "bad_json" }, 400);
  const id = parseUuid(body);
  if (id.error) return id.error;
  const { uuid } = id;

  const postId = String(body.postId || "");
  const key = String(body.key || "");
  if (!/^\d+$/.test(postId)) return json({ error: "bad_request" }, 400);
  const post = await getPostRow(env, postId);
  if (!post) return json({ error: "not_found" }, 404);
  if (post.solved) return json({ error: "locked" }, 409);
  const pair = TYPE_REACTIONS[post.type] || TYPE_REACTIONS.talk;
  if (!pair.includes(key)) return json({ error: "bad_key" }, 400);
  const guard = await writeGuard(env, request, uuid, "react");
  if (guard) return guard;

  const prev = await env.DB.prepare("SELECT key FROM reactions WHERE post_id = ? AND uuid = ?")
    .bind(postId, uuid).first();
  let active;
  if (prev && prev.key === key) {
    await env.DB.prepare("DELETE FROM reactions WHERE post_id = ? AND uuid = ?")
      .bind(postId, uuid).run();
    active = null;
  } else {
    await env.DB.prepare("INSERT OR REPLACE INTO reactions (post_id, uuid, key) VALUES (?, ?, ?)")
      .bind(postId, uuid, key).run();
    active = key;
  }
  const res = await env.DB.prepare("SELECT key, COUNT(*) AS n FROM reactions WHERE post_id = ? GROUP BY key")
    .bind(postId).all();
  const counts = {};
  for (const k of pair) counts[k] = 0;
  for (const r of res.results) if (counts[r.key] !== undefined) counts[r.key] = r.n;
  mem.feed = null;
  return json({ counts, active });
}

// --- create post ---

async function handleCreatePost(env, request) {
  const body = await request.json().catch(() => null);
  const id = parseIdentity(body);
  if (id.error) return id.error;
  const { uuid, author } = id;

  const type = TYPE_REACTIONS[body.type] ? body.type : "talk";
  const text = String(body.text || "").trim();
  if (!text || text.length > MAX_TEXT) return json({ error: "bad_text" }, 400);
  const guard = await writeGuard(env, request, uuid, "post");
  if (guard) return guard;
  if (await duplicate(env, uuid, text)) return json({ error: "duplicate" }, 400);

  const tags = parseTags(text);
  const name = sanitizeName(TYPE_PREFIX[type] + " " + author + ": " + text);
  const message = {
    embeds: [{
      author: { name: author, icon_url: AVATAR_API + uuid },
      description: text,
      color: TYPE_COLOR[type],
    }],
  };
  const thread = await discord(env, "POST", "/channels/" + env.FORUM_CHANNEL_ID + "/threads", {
    name,
    type: 11,
    message,
  });
  const createdAt = new Date().toISOString();
  const stmts = [
    env.DB.prepare("INSERT OR REPLACE INTO posts (id, type, author, uuid, text, tags, solved, created_at, comment_count) VALUES (?, ?, ?, ?, ?, ?, 0, ?, 0)")
      .bind(thread.id, type, author, uuid, text, JSON.stringify(tags), createdAt),
    ...tags.map((t) => env.DB.prepare("INSERT OR IGNORE INTO post_tags (tag, post_id) VALUES (?, ?)").bind(t, thread.id)),
    ...stampStmtsFor(env, request, uuid, "post"),
    await markWrittenStmt(env, uuid, text),
  ];
  await env.DB.batch(stmts);
  invalidateFeed();
  const post = {
    id: thread.id, type, author, uuid, text, tags,
    solved: false, createdAt, commentCount: 0,
  };
  return json({ post: { ...post, reactions: tallyPair(type, {}), myReaction: null } }, 201);
}

// --- comments ---

function parseComment(m) {
  let author = (m.author && (m.author.global_name || m.author.username)) || "?";
  let text = m.content || "";
  let fromMod = false;
  const bold = /^\*\*(.{1,32}?)\*\*: /s.exec(text);
  if (m.author && m.author.bot && bold) {
    fromMod = true;
    author = bold[1];
    text = text.slice(bold[0].length);
  } else if (m.author && m.author.bot && m.embeds && m.embeds.length) {
    fromMod = true;
    const e = m.embeds[0];
    if (e.author && e.author.name) author = e.author.name;
    if (e.description) text = e.description;
  }
  return { id: m.id, author, text, createdAt: m.timestamp, fromMod };
}

async function pushNotif(env, authorUuid, notif) {
  if (!authorUuid) return;
  await env.DB.batch([
    env.DB.prepare("INSERT OR IGNORE INTO notifications (uuid, id, post_id, post_text, author, text, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
      .bind(authorUuid, notif.id, notif.postId, notif.postText || "", notif.author || "", notif.text || "", notif.createdAt || ""),
    env.DB.prepare("DELETE FROM notifications WHERE uuid = ? AND id NOT IN (SELECT id FROM notifications WHERE uuid = ? ORDER BY CAST(id AS INTEGER) DESC LIMIT ?)")
      .bind(authorUuid, authorUuid, NOTIF_CAP),
  ]);
}

async function handleGetComments(env, url, postId, request, ctx) {
  const before = url.searchParams.get("before") || "";
  const fresh = url.searchParams.get("fresh") === "1" || url.searchParams.get("fresh") === "true";
  const inm = request.headers.get("If-None-Match");
  if (!before) {
    const cached = mem.comments.get(postId);
    const allowCache = !(fresh && !inm);
    if (allowCache && cached && Date.now() - cached.at < COMMENTS_TTL) {
      return jsonEtag(cached.data, request);
    }
  }
  let qs = "?limit=" + PAGE_LIMIT;
  if (before) qs += "&before=" + before;
  let msgs;
  try {
    msgs = await discord(env, "GET", "/channels/" + postId + "/messages" + qs);
  } catch (e) {
    if (isDiscord404(e)) {
      ctx.waitUntil(purgePost(env, postId));
      return json({ error: "not_found" }, 404);
    }
    throw e;
  }
  const starter = msgs.find((msg) => msg.id === postId);
  const comments = msgs
    .filter((msg) => msg.id !== postId)
    .map(parseComment)
    .reverse();
  const nextBefore =
    !starter && msgs.length >= PAGE_LIMIT ? msgs[msgs.length - 1].id : null;
  const data = { comments, nextBefore };
  if (!before) {
    if (mem.comments.size > 300) mem.comments.clear();
    mem.comments.set(postId, { at: Date.now(), data });
    // запись только если счётчик реально разошёлся; неполная страница (50+ комментов) счётчик не трогает
    if (starter) {
      ctx.waitUntil(env.DB.prepare("UPDATE posts SET comment_count = ? WHERE id = ? AND comment_count <> ?")
        .bind(comments.length, postId, comments.length).run());
    }
    return jsonEtag(data, request);
  }
  return json(data);
}

async function handleAddComment(env, request, postId, ctx) {
  const body = await request.json().catch(() => null);
  const id = parseIdentity(body);
  if (id.error) return id.error;
  const { uuid, author } = id;

  const text = String(body.text || "").trim();
  if (!text || text.length > MAX_COMMENT) return json({ error: "bad_text" }, 400);
  const post = await getPostRow(env, postId);
  if (!post) return json({ error: "not_found" }, 404);
  if (post.solved) return json({ error: "locked" }, 409);
  const guard = await writeGuard(env, request, uuid, "comment");
  if (guard) return guard;
  if (await duplicate(env, uuid, text)) return json({ error: "duplicate" }, 400);

  const msg = await discord(env, "POST", "/channels/" + postId + "/messages", {
    embeds: [{
      author: { name: author, icon_url: AVATAR_API + uuid },
      description: text,
    }],
  });
  await env.DB.batch([
    env.DB.prepare("UPDATE posts SET comment_count = comment_count + 1 WHERE id = ?").bind(postId),
    env.DB.prepare("INSERT OR REPLACE INTO comment_owners (msg_id, uuid) VALUES (?, ?)").bind(msg.id, uuid),
    ...stampStmtsFor(env, request, uuid, "comment"),
    await markWrittenStmt(env, uuid, text),
  ]);
  mem.comments.delete(postId);
  mem.feed = null;

  if (post.uuid && uuid !== post.uuid) {
    ctx.waitUntil(pushNotif(env, post.uuid, {
      id: msg.id,
      postId: post.id,
      postText: (post.text || "").replace(/\s+/g, " ").trim().slice(0, 40),
      author,
      text: text.slice(0, 60),
      createdAt: msg.timestamp,
    }));
  }

  return json({
    comment: {
      id: msg.id,
      author,
      text,
      createdAt: msg.timestamp || new Date().toISOString(),
      fromMod: true,
    },
  }, 201);
}

async function handleDeleteComment(env, request, postId, msgId) {
  const body = await request.json().catch(() => null);
  const id = parseUuid(body);
  if (id.error) return id.error;
  const owner = await env.DB.prepare("SELECT uuid FROM comment_owners WHERE msg_id = ?").bind(msgId).first();
  if (!owner || owner.uuid !== id.uuid) return json({ error: "forbidden" }, 403);
  try {
    await discord(env, "DELETE", "/channels/" + postId + "/messages/" + msgId);
  } catch (e) {
    if (!isDiscord404(e)) throw e;
  }
  await env.DB.batch([
    env.DB.prepare("UPDATE posts SET comment_count = MAX(0, comment_count - 1) WHERE id = ?").bind(postId),
    env.DB.prepare("DELETE FROM comment_owners WHERE msg_id = ?").bind(msgId),
  ]);
  mem.comments.delete(postId);
  return json({ ok: true });
}

// --- delete post / purge / report ---

function isDiscord404(e) {
  return String((e && e.message) || e).startsWith("discord 404");
}

async function purgePost(env, postId) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM posts WHERE id = ?").bind(postId),
    env.DB.prepare("DELETE FROM reactions WHERE post_id = ?").bind(postId),
    env.DB.prepare("DELETE FROM post_tags WHERE post_id = ?").bind(postId),
    env.DB.prepare("DELETE FROM sync_state WHERE post_id = ?").bind(postId),
    env.DB.prepare("DELETE FROM notifications WHERE post_id = ?").bind(postId),
  ]);
  mem.comments.delete(postId);
  invalidateFeed();
}

async function handleDeletePost(env, request, postId, ctx) {
  const body = await request.json().catch(() => null);
  const id = parseUuid(body);
  if (id.error) return id.error;
  const post = await getPostRow(env, postId);
  if (!post) return json({ error: "not_found" }, 404);
  if (post.uuid !== id.uuid) return json({ error: "forbidden" }, 403);
  try {
    await discord(env, "DELETE", "/channels/" + postId);
  } catch (e) {
    if (!isDiscord404(e)) throw e;
  }
  ctx.waitUntil(purgePost(env, postId));
  return json({ ok: true });
}

async function handleReport(env, request, ctx) {
  if (!env.REPORT_CHANNEL_ID) return json({ error: "not_configured" }, 503);
  const body = await request.json().catch(() => null);
  if (!body) return json({ error: "bad_json" }, 400);
  const id = parseIdentity(body);
  if (id.error) return id.error;
  const postId = String(body.postId || "");
  const commentId = body.commentId ? String(body.commentId) : "";
  const reason = String(body.reason || "").trim().slice(0, 200);
  if (!/^\d+$/.test(postId)) return json({ error: "bad_request" }, 400);
  const text = "**Report** from `" + id.author + "` (`" + id.uuid + "`)\n"
      + "Post: `" + postId + "`"
      + (commentId ? " Comment: `" + commentId + "`" : "")
      + (reason ? "\nReason: " + reason : "");
  ctx.waitUntil(discord(env, "POST", "/channels/" + env.REPORT_CHANNEL_ID + "/messages", {
    content: text.slice(0, 2000),
  }));
  return json({ ok: true });
}

// --- cron: Discord → notif ---

async function pollDiscordReplies(env) {
  await ensureSchema(env);
  let threads;
  try {
    threads = await getThreads(env);
  } catch {
    return;
  }
  if (!threads.length) return;

  const minute = Math.floor(Date.now() / 60000);
  const start = minute % threads.length;
  const batch = [];
  for (let i = 0; i < CRON_THREAD_CAP && i < threads.length; i++) {
    batch.push(threads[(start + i) % threads.length]);
  }
  for (const t of batch) {
    try {
      await syncThreadReplies(env, t.id);
    } catch {
      // one bad thread must not kill the whole cron run
    }
  }
  // housekeeping раз в ~30 минут: чистим протухшие лимиты и dedup
  if (minute % 30 === 0) {
    const cutoff = Date.now() - 60 * 60 * 1000;
    await env.DB.batch([
      env.DB.prepare("DELETE FROM limiter WHERE ts < ?").bind(cutoff),
      env.DB.prepare("DELETE FROM dedup WHERE ts < ?").bind(Date.now() - DEDUP_TTL * 2000),
    ]);
  }
}

async function syncThreadReplies(env, postId) {
  const post = await getPostRow(env, postId);
  if (!post || !post.uuid) return;

  let msgs;
  try {
    msgs = await discord(env, "GET",
        "/channels/" + postId + "/messages?limit=" + CRON_MSG_LIMIT);
  } catch (e) {
    if (isDiscord404(e)) {
      await purgePost(env, postId);
    }
    return;
  }
  if (!Array.isArray(msgs) || msgs.length === 0) return;

  const state = await env.DB.prepare("SELECT last_msg_id FROM sync_state WHERE post_id = ?").bind(postId).first();
  const lastSeen = state ? state.last_msg_id : null;
  if (lastSeen === null) {
    await env.DB.prepare("INSERT OR REPLACE INTO sync_state (post_id, last_msg_id) VALUES (?, ?)")
      .bind(postId, msgs[0].id).run();
    return;
  }

  const fresh = [];
  for (const m of msgs) {
    if (m.id === postId) continue;
    if (lastSeen && !idLess(lastSeen, m.id)) continue;
    fresh.push(m);
  }
  fresh.reverse();

  let newest = lastSeen;
  let addedCount = 0;
  for (const m of fresh) {
    newest = m.id;
    if (m.author && m.author.bot) continue;
    const c = parseComment(m);
    if (c.fromMod) continue;
    addedCount++;
    const text = (c.text || "").replace(/\s+/g, " ").trim();
    if (!text) continue;
    await pushNotif(env, post.uuid, {
      id: m.id,
      postId: post.id,
      postText: (post.text || "").replace(/\s+/g, " ").trim().slice(0, 40),
      author: c.author,
      text: text.slice(0, 60),
      createdAt: c.createdAt,
    });
  }
  const stmts = [];
  if (newest && newest !== lastSeen) {
    stmts.push(env.DB.prepare("INSERT OR REPLACE INTO sync_state (post_id, last_msg_id) VALUES (?, ?)")
      .bind(postId, newest));
  }
  if (addedCount > 0) {
    stmts.push(env.DB.prepare("UPDATE posts SET comment_count = comment_count + ? WHERE id = ?")
      .bind(addedCount, postId));
    mem.comments.delete(postId);
    mem.feed = null;
  }
  if (stmts.length) await env.DB.batch(stmts);
}

// --- solve ---

async function handleSolve(env, request, postId) {
  const body = await request.json().catch(() => null);
  const id = parseUuid(body);
  if (id.error) return id.error;
  const { uuid } = id;
  const post = await getPostRow(env, postId);
  if (!post) return json({ error: "not_found" }, 404);
  if (post.uuid !== uuid) return json({ error: "forbidden" }, 403);
  const guard = await writeGuard(env, request, uuid, "solve");
  if (guard) return guard;
  const name = sanitizeName(SOLVED_PREFIX + " " + TYPE_PREFIX[post.type] + " "
      + post.author + ": " + post.text);
  await discord(env, "PATCH", "/channels/" + postId, { name, locked: true });
  await env.DB.batch([
    env.DB.prepare("UPDATE posts SET solved = 1 WHERE id = ?").bind(postId),
    env.DB.prepare("DELETE FROM notifications WHERE uuid = ? AND post_id = ?").bind(post.uuid, postId),
  ]);
  invalidateFeed();
  return json({ ok: true, post: { ...rowToPost({ ...post, solved: 1 }) } });
}

// --- notifications ---

async function handleNotifications(env, url) {
  const uuid = (url.searchParams.get("uuid") || "").trim();
  const after = url.searchParams.get("after") || "";
  if (!uuid || uuid.length > 40) return json({ error: "bad_uuid" }, 400);
  const res = await env.DB.prepare("SELECT * FROM notifications WHERE uuid = ? ORDER BY CAST(id AS INTEGER) DESC LIMIT ?")
    .bind(normalizeUuid(uuid), NOTIF_CAP).all();
  const list = res.results.map((r) => ({
    id: r.id, postId: r.post_id, postText: r.post_text,
    author: r.author, text: r.text, createdAt: r.created_at,
  }));
  // Без ?after= отдаём только курсор — никогда не реплеим историю после рестарта.
  const fresh = after ? list.filter((n) => idLess(after, n.id)) : [];
  const latest = list.length ? list[0].id : after || null;
  return json({ notifications: fresh, latest });
}

// --- tags ---

async function handleTags(env, url) {
  const q = (url.searchParams.get("q") || "").toLowerCase().replace(/^#/, "").trim();
  if (!q && mem.tags && Date.now() - mem.tags.at < TAGS_TTL) {
    return json(mem.tags.data);
  }
  const like = q.replace(/[\\%_]/g, (c) => "\\" + c) + "%";
  const res = q
      ? await env.DB.prepare("SELECT tag, COUNT(*) AS n FROM post_tags WHERE tag LIKE ? ESCAPE '\\' GROUP BY tag ORDER BY n DESC LIMIT 10").bind(like).all()
      : await env.DB.prepare("SELECT tag, COUNT(*) AS n FROM post_tags GROUP BY tag ORDER BY n DESC LIMIT 10").all();
  const data = { tags: res.results.map((r) => ({ tag: r.tag, count: r.n })) };
  if (!q) mem.tags = { at: Date.now(), data };
  return json(data);
}

// --- admin (ADMIN_TOKEN — not in client jar) ---

function adminOk(env, request) {
  const t = request.headers.get("X-Admin-Token");
  return env.ADMIN_TOKEN && t === env.ADMIN_TOKEN;
}

async function routeAdmin(env, request, url, p) {
  if (!adminOk(env, request)) return json({ error: "unauthorized" }, 401);
  if (p === "/v1/admin/ban" && request.method === "POST") {
    return await handleAdminBan(env, request);
  }
  if (p === "/v1/admin/unban" && request.method === "POST") {
    return await handleAdminUnban(env, request);
  }
  const m = /^\/v1\/admin\/posts\/(\d+)$/.exec(p);
  if (m && request.method === "DELETE") {
    return await handleAdminDeletePost(env, m[1]);
  }
  return json({ error: "not_found" }, 404);
}

async function handleAdminBan(env, request) {
  const body = await request.json().catch(() => null);
  if (!body) return json({ error: "bad_json" }, 400);
  const stmts = [];
  if (body.uuid) stmts.push(env.DB.prepare("INSERT OR IGNORE INTO bans (key) VALUES (?)").bind("u:" + normalizeUuid(body.uuid)));
  if (body.ip) stmts.push(env.DB.prepare("INSERT OR IGNORE INTO bans (key) VALUES (?)").bind("ip:" + body.ip));
  if (stmts.length) await env.DB.batch(stmts);
  return json({ ok: true });
}

async function handleAdminUnban(env, request) {
  const body = await request.json().catch(() => null);
  if (!body) return json({ error: "bad_json" }, 400);
  const stmts = [];
  if (body.uuid) stmts.push(env.DB.prepare("DELETE FROM bans WHERE key = ?").bind("u:" + normalizeUuid(body.uuid)));
  if (body.ip) stmts.push(env.DB.prepare("DELETE FROM bans WHERE key = ?").bind("ip:" + body.ip));
  if (stmts.length) await env.DB.batch(stmts);
  return json({ ok: true });
}

async function handleAdminDeletePost(env, postId) {
  try {
    await discord(env, "DELETE", "/channels/" + postId);
  } catch (e) {
    if (!isDiscord404(e)) throw e;
  }
  await purgePost(env, postId);
  return json({ ok: true });
}