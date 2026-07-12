// MineFeed worker v5
// Secrets: DISCORD_BOT_TOKEN, APP_TOKEN
// Vars: GUILD_ID, FORUM_CHANNEL_ID. KV: MINEFEED_KV
// Cron (* * * * *): scheduled() polls Discord replies

// Do not join — split URLs survive `strings` on the jar.
const DISCORD_API = "https:" + "//discord.com/api/v10";
const AVATAR_API = "https:" + "//mc-heads.net/avatar/";

const FEED_CACHE_TTL = 60;
const COMMENTS_CACHE_TTL = 30;
const RATE_LIMIT_TTL = 30;      // post: 1 per 30s (uuid + IP)
const COMMENT_RATE_TTL = 10;    // comment: 1 per 10s (uuid + IP)
const WRITE_BURST_LIMIT = 20;   // writes per IP per minute
const REACT_BURST_LIMIT = 30;   // reactions per IP per minute
const DEDUP_TTL = 600;
const MAX_TEXT = 500;
const MAX_COMMENT = 300;
const PAGE_LIMIT = 50;
const NOTIF_CAP = 50;
const TAG_POSTS_CAP = 200;
const AUTHOR_POSTS_CAP = 200;
const TAGS_INDEX_CAP = 500;
const MAX_TAGS = 8;
const CRON_THREAD_CAP = 20;     // threads polled per cron run
const CRON_MSG_LIMIT = 5;       // recent messages per thread
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

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      const p = url.pathname;
      if (p === "/v1/health") return json({ ok: true });
      if (!env.APP_TOKEN || request.headers.get("X-Feed-Token") !== env.APP_TOKEN) {
        return json({ error: "unauthorized" }, 401);
      }
      if (p === "/v1/feed" && request.method === "GET") return await handleFeed(env, url);
      if (p === "/v1/posts" && request.method === "POST") return await handleCreatePost(env, request);
      if (p === "/v1/react" && request.method === "POST") return await handleReact(env, request);
      if (p === "/v1/notifications" && request.method === "GET") return await handleNotifications(env, url);
      if (p === "/v1/tags" && request.method === "GET") return await handleTags(env, url);
      let m = /^\/v1\/posts\/(\d+)\/comments$/.exec(p);
      if (m && request.method === "GET") return await handleGetComments(env, url, m[1]);
      if (m && request.method === "POST") return await handleAddComment(env, request, m[1]);
      m = /^\/v1\/posts\/(\d+)\/solve$/.exec(p);
      if (m && request.method === "POST") return await handleSolve(env, request, m[1]);
      m = /^\/v1\/posts\/(\d+)$/.exec(p);
      if (m && request.method === "GET") return await handleGetPost(env, url, m[1]);
      return json({ error: "not_found" }, 404);
    } catch (e) {
      const detail = String((e && e.message) || e);
      const status = detail.startsWith("discord 403") ? 403
          : detail.startsWith("discord 404") ? 404
          : detail.startsWith("discord 429") ? 429
          : 500;
      return json({ error: detail.startsWith("discord") ? "discord" : "internal", detail }, status);
    }
  },

  // Cloudflare dashboard → Triggers → Cron: * * * * *
  async scheduled(controller, env, ctx) {
    ctx.waitUntil(pollDiscordReplies(env));
  },
};

// --- helpers ---

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function ipOf(request) {
  return request.headers.get("CF-Connecting-IP") || "0.0.0.0";
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

// --- rate limits & bans ---

async function banned(env, uuid, ip) {
  const [a, b] = await Promise.all([
    env.MINEFEED_KV.get("ban:" + uuid),
    env.MINEFEED_KV.get("ban:ip:" + ip),
  ]);
  return a !== null || b !== null;
}

// KV expirationTtl has a 60s floor — short cooldowns use a timestamp in the value.
async function cooldown(env, key, ttlSec) {
  const raw = await env.MINEFEED_KV.get(key, "json");
  const now = Date.now();
  if (raw && raw.t && now - raw.t < ttlSec * 1000) return true;
  const kvTtl = Math.max(60, ttlSec * 2);
  await env.MINEFEED_KV.put(key, JSON.stringify({ t: now }), { expirationTtl: kvTtl });
  return false;
}

async function burstLimited(env, key, limit) {
  const raw = await env.MINEFEED_KV.get(key, "json");
  const now = Date.now();
  const rec = raw && now - raw.t < 60000 ? raw : { t: now, n: 0 };
  rec.n += 1;
  await env.MINEFEED_KV.put(key, JSON.stringify(rec), { expirationTtl: 120 });
  return rec.n > limit;
}

async function sha1hex(text) {
  const buf = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

async function duplicate(env, uuid, text) {
  const key = "lastwrite:" + uuid;
  const hash = await sha1hex(text.trim().toLowerCase());
  const prev = await env.MINEFEED_KV.get(key);
  return prev === hash;
}

async function markWritten(env, uuid, text) {
  const key = "lastwrite:" + uuid;
  const hash = await sha1hex(text.trim().toLowerCase());
  await env.MINEFEED_KV.put(key, hash, { expirationTtl: DEDUP_TTL });
}

async function writeGuard(env, request, uuid, kind) {
  const ip = ipOf(request);
  if (await banned(env, uuid, ip)) return json({ error: "banned" }, 403);
  if (await burstLimited(env, "ratelimit:ipw:" + ip, WRITE_BURST_LIMIT)) {
    return json({ error: "rate_limited" }, 429);
  }
  if (kind === "post") {
    if (await cooldown(env, "ratelimit:" + uuid, RATE_LIMIT_TTL)) return json({ error: "rate_limited" }, 429);
    if (await cooldown(env, "ratelimit:ip:" + ip, RATE_LIMIT_TTL)) return json({ error: "rate_limited" }, 429);
  } else if (kind === "comment") {
    if (await cooldown(env, "ratelimit:c:" + uuid, COMMENT_RATE_TTL)) {
      return json({ error: "comment_rate_limited" }, 429);
    }
    if (await cooldown(env, "ratelimit:ipc:" + ip, COMMENT_RATE_TTL)) {
      return json({ error: "comment_rate_limited" }, 429);
    }
  } else if (kind === "react") {
    if (await burstLimited(env, "ratelimit:ipr:" + ip, REACT_BURST_LIMIT)) return json({ error: "rate_limited" }, 429);
  }
  return null;
}

// --- reactions (KV only, not mirrored to Discord) ---

function normalizeVotes(raw) {
  if (!raw) return {};
  const values = Object.values(raw);
  if (values.length && Array.isArray(values[0])) {
    const votes = {};
    for (const [key, uuids] of Object.entries(raw)) {
      for (const u of uuids) if (!votes[u]) votes[u] = key;
    }
    return votes;
  }
  return raw;
}

function tally(votes) {
  const t = {};
  for (const k of Object.values(votes)) t[k] = (t[k] || 0) + 1;
  return t;
}

function totalCounts(post, votes) {
  const pair = TYPE_REACTIONS[post.type] || TYPE_REACTIONS.talk;
  const mod = tally(votes);
  const counts = {};
  for (const k of pair) counts[k] = mod[k] || 0;
  return counts;
}

async function handleReact(env, request) {
  const body = await request.json().catch(() => null);
  if (!body) return json({ error: "bad_json" }, 400);
  const postId = String(body.postId || "");
  const uuid = String(body.uuid || "").trim();
  const key = String(body.key || "");
  if (!/^\d+$/.test(postId) || !uuid || uuid.length > 40) return json({ error: "bad_request" }, 400);
  const post = await env.MINEFEED_KV.get("post:" + postId, "json");
  if (!post) return json({ error: "not_found" }, 404);
  const pair = TYPE_REACTIONS[post.type] || TYPE_REACTIONS.talk;
  if (!pair.includes(key)) return json({ error: "bad_key" }, 400);
  const guard = await writeGuard(env, request, uuid, "react");
  if (guard) return guard;

  const votes = normalizeVotes(await env.MINEFEED_KV.get("react:" + postId, "json"));
  const prev = votes[uuid] || null;
  let active;
  if (prev === key) {
    delete votes[uuid];
    active = null;
  } else {
    votes[uuid] = key;
    active = key;
  }
  await env.MINEFEED_KV.put("react:" + postId, JSON.stringify(votes));
  return json({ counts: totalCounts(post, votes), active });
}

// --- feed ---

async function getActiveThreads(env, fresh = false) {
  if (!fresh) {
    const cached = await env.MINEFEED_KV.get("feed:threads", "json");
    if (cached && Date.now() - cached.at < FEED_CACHE_TTL * 1000) return cached.threads;
  }
  const data = await discord(env, "GET", "/guilds/" + env.GUILD_ID + "/threads/active");
  const threads = (data.threads || [])
    .filter((t) => t.parent_id === env.FORUM_CHANNEL_ID)
    .map((t) => ({ id: t.id, message_count: t.message_count || 0 }));
  threads.sort((a, b) => (idLess(a.id, b.id) ? 1 : -1));
  await env.MINEFEED_KV.put("feed:threads", JSON.stringify({ at: Date.now(), threads }));
  return threads;
}

async function buildPost(env, id, uuid, messageCount) {
  const post = await env.MINEFEED_KV.get("post:" + id, "json");
  if (!post) return null;
  const votes = normalizeVotes(await env.MINEFEED_KV.get("react:" + id, "json"));
  const counts = totalCounts(post, votes);
  return {
    ...post,
    commentCount: messageCount !== undefined ? messageCount : post.commentCount || 0,
    reactions: counts,
    myReaction: uuid ? votes[uuid] || null : null,
  };
}

async function handleGetPost(env, url, postId) {
  const uuid = url.searchParams.get("uuid") || "";
  const post = await buildPost(env, postId, uuid, undefined);
  if (!post) return json({ error: "not_found" }, 404);
  return json({ post });
}

async function handleFeed(env, url) {
  const limit = Math.min(parseInt(url.searchParams.get("limit") || "20", 10) || 20, 50);
  const cursor = url.searchParams.get("cursor") || "";
  const uuid = url.searchParams.get("uuid") || "";
  const tag = (url.searchParams.get("tag") || "").toLowerCase().replace(/^#/, "");
  const mine = url.searchParams.get("mine") === "1" || url.searchParams.get("mine") === "true";
  const fresh = url.searchParams.get("fresh") === "1" || url.searchParams.get("fresh") === "true";
  if (tag) return tagFeed(env, tag, cursor, limit, uuid);
  if (mine) return mineFeed(env, uuid, cursor, limit);

  const threads = await getActiveThreads(env, fresh);
  const list = cursor ? threads.filter((t) => idLess(t.id, cursor)) : threads;
  const page = list.slice(0, limit);
  const posts = [];
  for (const t of page) {
    const post = await buildPost(env, t.id, uuid, t.message_count);
    if (post) posts.push(post);
  }
  const nextCursor = list.length > limit && page.length ? page[page.length - 1].id : null;
  return json({ posts, nextCursor });
}

async function tagFeed(env, tag, cursor, limit, uuid) {
  const ids = (await env.MINEFEED_KV.get("tag:" + tag, "json")) || [];
  const start = cursor ? parseInt(cursor, 10) || 0 : 0;
  const page = ids.slice(start, start + limit);
  const posts = [];
  for (const id of page) {
    const post = await buildPost(env, id, uuid, undefined);
    if (post) posts.push(post);
  }
  const nextCursor = start + limit < ids.length ? String(start + limit) : null;
  return json({ posts, nextCursor });
}

// MINE: posts:by:{uuid} index; cursor = array offset.
async function mineFeed(env, uuid, cursor, limit) {
  if (!uuid || uuid.length > 40) return json({ error: "bad_uuid" }, 400);
  const ids = (await env.MINEFEED_KV.get("posts:by:" + uuid.toLowerCase(), "json")) || [];
  const start = cursor ? parseInt(cursor, 10) || 0 : 0;
  const page = ids.slice(start, start + limit);
  const posts = [];
  for (const id of page) {
    const post = await buildPost(env, id, uuid, undefined);
    if (post) posts.push(post);
  }
  const nextCursor = start + limit < ids.length ? String(start + limit) : null;
  return json({ posts, nextCursor });
}

async function indexAuthorPosts(env, uuid, postId) {
  if (!uuid) return;
  const key = "posts:by:" + uuid.toLowerCase();
  const arr = (await env.MINEFEED_KV.get(key, "json")) || [];
  arr.unshift(postId);
  await env.MINEFEED_KV.put(key, JSON.stringify(arr.slice(0, AUTHOR_POSTS_CAP)));
}

// --- create post ---

function emptyCounts(type) {
  const pair = TYPE_REACTIONS[type] || TYPE_REACTIONS.talk;
  const c = {};
  for (const k of pair) c[k] = 0;
  return c;
}

async function indexTags(env, tags, postId) {
  for (const tag of tags) {
    const key = "tag:" + tag;
    const arr = (await env.MINEFEED_KV.get(key, "json")) || [];
    arr.unshift(postId);
    await env.MINEFEED_KV.put(key, JSON.stringify(arr.slice(0, TAG_POSTS_CAP)));
  }
  if (tags.length === 0) return;
  const idx = (await env.MINEFEED_KV.get("tags:index", "json")) || {};
  for (const tag of tags) idx[tag] = (idx[tag] || 0) + 1;
  if (Object.keys(idx).length > TAGS_INDEX_CAP) {
    for (const [t, c] of Object.entries(idx)) {
      if (c <= 1) delete idx[t];
    }
  }
  await env.MINEFEED_KV.put("tags:index", JSON.stringify(idx));
}

async function handleCreatePost(env, request) {
  const body = await request.json().catch(() => null);
  if (!body) return json({ error: "bad_json" }, 400);
  const type = TYPE_REACTIONS[body.type] ? body.type : "talk";
  const author = String(body.author || "").trim();
  const uuid = String(body.uuid || "").trim();
  const text = String(body.text || "").trim();
  if (!AUTHOR_RE.test(author)) return json({ error: "bad_author" }, 400);
  if (!uuid || uuid.length > 40) return json({ error: "bad_uuid" }, 400);
  if (!text || text.length > MAX_TEXT) return json({ error: "bad_text" }, 400);
  const guard = await writeGuard(env, request, uuid, "post");
  if (guard) return guard;
  if (await duplicate(env, uuid, text)) return json({ error: "duplicate" }, 400);

  const tags = parseTags(text);
  const name = (TYPE_PREFIX[type] + " " + author + ": " + text).slice(0, 96);
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
  const post = {
    id: thread.id,
    type,
    author,
    uuid,
    text,
    tags,
    solved: false,
    createdAt: new Date().toISOString(),
    commentCount: 0,
  };
  await env.MINEFEED_KV.put("post:" + thread.id, JSON.stringify(post));
  await indexTags(env, tags, thread.id);
  await indexAuthorPosts(env, uuid, thread.id);
  await env.MINEFEED_KV.delete("feed:threads");
  await markWritten(env, uuid, text);
  return json({ post: { ...post, reactions: emptyCounts(type), myReaction: null } }, 201);
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
  const nkey = "notif:" + authorUuid;
  const list = (await env.MINEFEED_KV.get(nkey, "json")) || [];
  if (list.some((n) => n.id === notif.id)) return;
  list.unshift(notif);
  await env.MINEFEED_KV.put(nkey, JSON.stringify(list.slice(0, NOTIF_CAP)));
}

async function handleGetComments(env, url, postId) {
  const before = url.searchParams.get("before") || "";
  const fresh = url.searchParams.get("fresh") === "1" || url.searchParams.get("fresh") === "true";
  if (!before && !fresh) {
    const cached = await env.MINEFEED_KV.get("comments:" + postId, "json");
    if (cached && Date.now() - cached.at < COMMENTS_CACHE_TTL * 1000) return json(cached.data);
  }
  let qs = "?limit=" + PAGE_LIMIT;
  if (before) qs += "&before=" + before;
  const msgs = await discord(env, "GET", "/channels/" + postId + "/messages" + qs);
  const starter = msgs.find((msg) => msg.id === postId);
  const comments = msgs
    .filter((msg) => msg.id !== postId)
    .map(parseComment)
    .reverse();
  const nextBefore =
    !starter && msgs.length >= PAGE_LIMIT ? msgs[msgs.length - 1].id : null;
  const data = { comments, nextBefore };
  if (!before) {
    await env.MINEFEED_KV.put("comments:" + postId, JSON.stringify({ at: Date.now(), data }));
  }
  return json(data);
}

async function handleAddComment(env, request, postId) {
  const body = await request.json().catch(() => null);
  if (!body) return json({ error: "bad_json" }, 400);
  const author = String(body.author || "").trim();
  const uuid = String(body.uuid || "").trim();
  const text = String(body.text || "").trim();
  if (!AUTHOR_RE.test(author)) return json({ error: "bad_author" }, 400);
  if (!uuid || uuid.length > 40) return json({ error: "bad_uuid" }, 400);
  if (!text || text.length > MAX_COMMENT) return json({ error: "bad_text" }, 400);
  const post = await env.MINEFEED_KV.get("post:" + postId, "json");
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
  post.commentCount = (post.commentCount || 0) + 1;
  await env.MINEFEED_KV.put("post:" + postId, JSON.stringify(post));
  await env.MINEFEED_KV.delete("comments:" + postId);
  await markWritten(env, uuid, text);

  if (post.uuid && uuid !== post.uuid) {
    await pushNotif(env, post.uuid, {
      id: msg.id,
      postId: post.id,
      postText: (post.text || "").replace(/\s+/g, " ").trim().slice(0, 40),
      author,
      text: text.slice(0, 60),
      createdAt: msg.timestamp,
    });
  }
  await env.MINEFEED_KV.put("sync:msg:" + postId, msg.id); // skip duplicate cron notif

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

// --- cron: Discord → notif ---

async function pollDiscordReplies(env) {
  let threads;
  try {
    threads = await getActiveThreads(env);
  } catch {
    return;
  }
  const batch = threads.slice(0, CRON_THREAD_CAP);
  for (const t of batch) {
    try {
      await syncThreadReplies(env, t.id);
    } catch {
      // one bad thread must not kill the whole cron run
    }
  }
}

async function syncThreadReplies(env, postId) {
  const post = await env.MINEFEED_KV.get("post:" + postId, "json");
  if (!post || !post.uuid) return;

  const msgs = await discord(env, "GET",
      "/channels/" + postId + "/messages?limit=" + CRON_MSG_LIMIT);
  if (!Array.isArray(msgs) || msgs.length === 0) return;

  const lastKey = "sync:msg:" + postId;
  const lastSeen = await env.MINEFEED_KV.get(lastKey);
  // Discord returns newest first; only messages newer than lastSeen.
  const fresh = [];
  for (const m of msgs) {
    if (m.id === postId) continue; // thread starter
    if (lastSeen && !idLess(lastSeen, m.id)) continue;
    fresh.push(m);
  }
  fresh.reverse(); // oldest first

  let newest = lastSeen;
  let bumped = false;
  for (const m of fresh) {
    newest = m.id;
    const c = parseComment(m);
    if (c.fromMod) continue; // already notified in handleAddComment
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
    bumped = true;
  }
  if (newest && newest !== lastSeen) {
    await env.MINEFEED_KV.put(lastKey, newest);
  }
  if (bumped) {
    post.commentCount = (post.commentCount || 0);
    await env.MINEFEED_KV.delete("comments:" + postId); // bust cache for live poll
  }
}

// --- solve ---

async function handleSolve(env, request, postId) {
  const body = await request.json().catch(() => null);
  const uuid = String((body && body.uuid) || "").trim();
  const post = await env.MINEFEED_KV.get("post:" + postId, "json");
  if (!post) return json({ error: "not_found" }, 404);
  if (post.uuid !== uuid) return json({ error: "forbidden" }, 403);
  const guard = await writeGuard(env, request, uuid, "solve");
  if (guard) return guard;
  post.solved = true;
  await env.MINEFEED_KV.put("post:" + postId, JSON.stringify(post));
  const name = (SOLVED_PREFIX + " " + TYPE_PREFIX[post.type] + " " + post.author + ": " + post.text).slice(0, 96);
  await discord(env, "PATCH", "/channels/" + postId, { name, locked: true });
  await env.MINEFEED_KV.delete("feed:threads");
  return json({ ok: true, post });
}

// --- notifications ---

async function handleNotifications(env, url) {
  const uuid = (url.searchParams.get("uuid") || "").trim();
  const after = url.searchParams.get("after") || "";
  if (!uuid || uuid.length > 40) return json({ error: "bad_uuid" }, 400);
  const list = (await env.MINEFEED_KV.get("notif:" + uuid, "json")) || [];
  const fresh = after ? list.filter((n) => idLess(after, n.id)) : list;
  const latest = list.length ? list[0].id : after || null;
  return json({ notifications: fresh, latest });
}

// --- tags ---

async function handleTags(env, url) {
  const q = (url.searchParams.get("q") || "").toLowerCase().replace(/^#/, "").trim();
  const idx = (await env.MINEFEED_KV.get("tags:index", "json")) || {};
  let entries = Object.entries(idx);
  if (q) entries = entries.filter(([t]) => t.startsWith(q));
  entries.sort((a, b) => b[1] - a[1]);
  return json({ tags: entries.slice(0, 10).map(([tag, count]) => ({ tag, count })) });
}
