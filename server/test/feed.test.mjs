import { test } from "node:test";
import assert from "node:assert/strict";
import { makeD1 } from "./d1shim.mjs";
import { route } from "../src/index.js";
import { HttpError, errorResponse } from "../src/util.js";
import { cleanText, threadName } from "../src/feed.js";
import { webhookName } from "../src/dapi.js";

const MIG = new URL("../migrations", import.meta.url).pathname;
const T0 = 1_800_000_000_000;
let seq = 1_500_000_000_000_000_000n;

function setup() {
  const calls = [];
  const env = {
    DB: makeD1(MIG),
    FEED_FORUM_ID: "900",
    FEED_TAGS: JSON.stringify({ rip: "t1", help: "t2", gg: "t3", talk: "t4", solved: "t5" }),
    REPORT_CHANNEL_ID: "777",
    DISCORD_GUILD_ID: "1",
    BOT_SECRET: "bot-secret-0123456789",
    DISCORD_FETCH: async (url, init = {}) => {
      const u = new URL(url);
      const form = init.body instanceof FormData ? init.body : null;
      const body = form ? JSON.parse(form.get("payload_json")) : init.body ? JSON.parse(init.body) : null;
      const files = form ? [...form.entries()].filter(([k]) => k.startsWith("files[")).map(([, f]) => f) : [];
      calls.push({ method: init.method, path: u.pathname, search: u.search, body, files });
      if (u.pathname === "/api/v10/attachments/refresh-urls") {
        return Response.json({ refreshed_urls: body.attachment_urls.map((o) => ({ original: o, refreshed: o.replace(/ex=[0-9a-f]+/, "ex=ffffffff") })) });
      }
      if (init.method === "POST" && u.pathname === "/api/v10/channels/900/webhooks") return Response.json({ id: "wh1", token: "tok" });
      if (init.method === "POST" && u.pathname === "/api/v10/webhooks/wh1/tok") {
        const id = String(seq++);
        const thread = u.searchParams.get("thread_id");
        if (thread === "404") return new Response("{}", { status: 404 });
        const attachments = files.map((f, i) => ({ id: `a${i}`, url: `https://cdn.discordapp.com/attachments/${thread || id}/a${i}/${f.name}?ex=6a000000&is=1&hm=abc`, width: 2, height: 1, content_type: f.type }));
        return Response.json({ id, channel_id: thread || id, attachments });
      }
      if (init.method === "DELETE" || init.method === "PATCH") return new Response(null, { status: 204 });
      if (u.pathname === "/api/v10/channels/777/messages") return Response.json({ id: "r1" });
      return new Response("{}", { status: 404 });
    },
  };
  const api = async (method, path, opts = {}) => {
    const headers = { "cf-connecting-ip": "1.1.1.1" };
    if (opts.key) headers.authorization = `Bearer ${opts.key}`;
    if (opts.auth) headers.authorization = opts.auth;
    const req = new Request("https://x.test" + path, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) });
    let res;
    try { res = await route(req, env, opts.now ?? T0); } catch (e) { if (!(e instanceof HttpError)) throw e; res = errorResponse(e); }
    return { status: res.status, data: await res.json() };
  };
  const user = async (tag) => {
    const r = await api("POST", "/v2/auth/register", { body: { tag, nick: tag.toUpperCase() } });
    return { key: r.data.key, id: r.data.account.id, tag };
  };
  return { env, api, user, calls };
}

test("text helpers", () => {
  assert.equal(cleanText("  §cHi\r\n\n\n\nthere\u200b  ", 500), "Hi\n\nthere");
  assert.throws(() => cleanText(" §a ", 500), /Пустой/);
  assert.throws(() => cleanText("x".repeat(501), 500), /длинный/);
  assert.equal(threadName("\nfirst line\nsecond"), "first line");
  assert.equal([...threadName("я".repeat(200))].length, 95);
  assert.equal(webhookName("Discord fan", "abc"), "D\u200biscord fan (@abc)");
});

test("post, feed, filters and pagination", async () => {
  const { api, user, calls } = setup();
  const a = await user("alice");
  const r = await api("POST", "/v2/posts", { key: a.key, body: { type: "help", text: "How to craft?\nplease" } });
  assert.equal(r.status, 201);
  assert.equal(r.data.post.author.tag, "alice");
  assert.equal(r.data.post.type, "help");
  const hook = calls.find((c) => c.path === "/api/v10/webhooks/wh1/tok");
  assert.equal(hook.body.thread_name, "How to craft?");
  assert.deepEqual(hook.body.applied_tags, ["t2"]);
  assert.equal(hook.body.username, "ALICE (@alice)");
  assert.deepEqual(hook.body.allowed_mentions, { parse: [] });
  assert.equal(calls.filter((c) => c.path.endsWith("/webhooks") ).length, 1);

  for (let i = 0; i < 24; i++) await api("POST", "/v2/posts", { key: a.key, body: { type: i % 2 ? "gg" : "talk", text: "p" + i }, now: T0 + 1000 + i * 400_000 });
  const b = await user("bob");
  const later = { now: T0 + 30 * 400_000 };
  const p1 = await api("GET", "/v2/feed", { key: b.key });
  assert.equal(p1.data.posts.length, 20);
  assert.equal(p1.data.posts[0].text, "p23");
  assert.ok(p1.data.next);
  const p2 = await api("GET", "/v2/feed?before=" + p1.data.next, { key: b.key });
  assert.deepEqual(p2.data.posts.map((p) => p.text), ["p3", "p2", "p1", "p0", "How to craft?\nplease"]);
  assert.equal(p2.data.next, null);
  const help = await api("GET", "/v2/feed?type=help", { key: b.key });
  assert.equal(help.data.posts.length, 1);
  const mine = await api("GET", "/v2/feed?type=mine", { key: b.key });
  assert.equal(mine.data.posts.length, 0);
  assert.equal((await api("POST", "/v2/posts", { key: b.key, body: { type: "nope", text: "x" } })).status, 400);
  assert.equal((await api("GET", "/v2/feed")).status, 401);
  // rate limit: 10 posts per hour (bob)
  for (let i = 0; i < 10; i++) assert.equal((await api("POST", "/v2/posts", { key: b.key, body: { type: "talk", text: "b" + i }, ...later })).status, 201);
  assert.equal((await api("POST", "/v2/posts", { key: b.key, body: { type: "talk", text: "b" }, ...later })).status, 429);
});

test("comments, reactions, notifications and sync", async () => {
  const { api, user } = setup();
  const a = await user("alice");
  const b = await user("bob");
  const c = await user("carol");
  const post = (await api("POST", "/v2/posts", { key: a.key, body: { type: "talk", text: "hello" } })).data.post;

  const s0 = await api("GET", "/v2/sync", { key: a.key, now: T0 + 10 });
  assert.equal(s0.data.reset, true); // no cursor yet
  const since = s0.data.now;

  const cm = await api("POST", `/v2/posts/${post.id}/comments`, { key: b.key, body: { text: "nice @carol and @alice" }, now: T0 + 20_000 });
  assert.equal(cm.status, 201);
  assert.equal(cm.data.comment.author.tag, "bob");

  // alice: one "comment" notification (no duplicate mention); carol: mention
  const sa = await api("GET", `/v2/sync?since=${since}&post=${post.id}`, { key: a.key, now: T0 + 30_000 });
  assert.equal(sa.data.reset, false);
  assert.equal(sa.data.posts.length, 1);
  assert.equal(sa.data.posts[0].comments, 1);
  assert.equal(sa.data.comments.length, 1);
  assert.equal(sa.data.notifications.unread, 1);
  assert.equal(sa.data.notifications.items[0].kind, "comment");
  assert.equal(sa.data.notifications.items[0].actor.tag, "bob");
  const nc = await api("GET", "/v2/notifications", { key: c.key });
  assert.equal(nc.data.items[0].kind, "mention");

  // reactions: toggle, switch, folded notification
  let r = await api("POST", `/v2/posts/${post.id}/react`, { key: b.key, body: { slot: 0 }, now: T0 + 40_000 });
  assert.deepEqual(r.data, { reactions: [1, 0], my: 0 });
  r = await api("POST", `/v2/posts/${post.id}/react`, { key: b.key, body: { slot: 1 }, now: T0 + 41_000 });
  assert.deepEqual(r.data, { reactions: [0, 1], my: 1 });
  r = await api("POST", `/v2/posts/${post.id}/react`, { key: c.key, body: { slot: 1 }, now: T0 + 42_000 });
  assert.deepEqual(r.data, { reactions: [0, 2], my: 1 });
  r = await api("POST", `/v2/posts/${post.id}/react`, { key: b.key, body: { slot: 1 }, now: T0 + 43_000 });
  assert.deepEqual(r.data, { reactions: [0, 1], my: -1 });
  const na = await api("GET", "/v2/notifications", { key: a.key });
  const reacts = na.data.items.filter((n) => n.kind === "react");
  assert.equal(reacts.length, 1);
  assert.equal(reacts[0].actor.tag, "carol");
  assert.equal(reacts[0].count, 2);
  assert.equal(na.data.unread, 2);
  await api("POST", "/v2/notifications/seen", { key: a.key, body: {}, now: T0 + 50_000 });
  assert.equal((await api("GET", "/v2/notifications", { key: a.key })).data.unread, 0);

  const feedC = await api("GET", "/v2/feed", { key: c.key });
  assert.equal(feedC.data.posts[0].my, 1);

  // comment deletion: others can't, post author can
  assert.equal((await api("DELETE", `/v2/comments/${cm.data.comment.id}`, { key: c.key })).status, 403);
  assert.equal((await api("DELETE", `/v2/comments/${cm.data.comment.id}`, { key: a.key, now: T0 + 60_000 })).status, 200);
  const sb = await api("GET", `/v2/sync?since=${T0 + 55_000}&post=${post.id}`, { key: b.key, now: T0 + 61_000 });
  assert.equal(sb.data.comments[0].deleted, true);
  assert.equal(sb.data.posts[0].comments, 0);

  // post deletion
  assert.equal((await api("DELETE", `/v2/posts/${post.id}`, { key: b.key })).status, 403);
  assert.equal((await api("DELETE", `/v2/posts/${post.id}`, { key: a.key, now: T0 + 70_000 })).status, 200);
  const sd = await api("GET", `/v2/sync?since=${T0 + 65_000}`, { key: b.key, now: T0 + 71_000 });
  assert.deepEqual(sd.data.posts, [{ id: post.id, deleted: true, updatedAt: T0 + 70_000 }]);
  assert.equal((await api("GET", `/v2/posts/${post.id}`, { key: b.key })).status, 404);
  // old cursor -> reset
  assert.equal((await api("GET", `/v2/sync?since=${T0}`, { key: b.key, now: T0 + 2 * 86_400_000 })).data.reset, true);
});

test("solve and report", async () => {
  const { api, user, calls } = setup();
  const a = await user("alice");
  const b = await user("bob");
  const help = (await api("POST", "/v2/posts", { key: a.key, body: { type: "help", text: "q" } })).data.post;
  const talk = (await api("POST", "/v2/posts", { key: a.key, body: { type: "talk", text: "t" } })).data.post;
  assert.equal((await api("POST", `/v2/posts/${talk.id}/solve`, { key: a.key, body: {} })).status, 400);
  assert.equal((await api("POST", `/v2/posts/${help.id}/solve`, { key: b.key, body: {} })).status, 403);
  const s = await api("POST", `/v2/posts/${help.id}/solve`, { key: a.key, body: { solved: true } });
  assert.equal(s.data.post.solved, true);
  assert.deepEqual(calls.find((c) => c.method === "PATCH").body, { locked: true, applied_tags: ["t2", "t5"] });
  calls.length = 0;
  await api("POST", `/v2/posts/${help.id}/solve`, { key: a.key, body: { solved: false } });
  assert.deepEqual(calls.find((c) => c.method === "PATCH").body, { locked: false, applied_tags: ["t2"] });
  const rep = await api("POST", "/v2/reports", { key: b.key, body: { postId: talk.id, reason: "spam" } });
  assert.equal(rep.status, 200);
  const msg = calls.find((c) => c.path === "/api/v10/channels/777/messages");
  assert.match(msg.body.content, /@bob/);
  assert.match(msg.body.content, new RegExp(`channels/1/${talk.id}`));
});

test("comment on a thread removed in Discord marks the post deleted", async () => {
  const { api, user, env } = setup();
  const a = await user("alice");
  await api("POST", "/v2/posts", { key: a.key, body: { type: "talk", text: "x" } });
  await env.DB.prepare("INSERT INTO posts (id, space, author_id, type, text, created_at, updated_at) VALUES ('404', 'global', ?, 'talk', 'gone', 1, 1)").bind(a.id).run();
  assert.equal((await api("POST", "/v2/posts/404/comments", { key: a.key, body: { text: "hi" } })).status, 404);
  assert.equal((await env.DB.prepare("SELECT deleted FROM posts WHERE id = '404'").first()).deleted, 1);
});

test("bot events", async () => {
  const { api, user, env, calls } = setup();
  const auth = "Bearer bot-secret-0123456789";
  assert.equal((await api("POST", "/v2/bot/events", { body: { events: [] } })).status, 401);
  assert.equal((await api("POST", "/v2/bot/events", { auth: "Bearer wrong-secret-xxxxxxxx", body: { events: [] } })).status, 401);
  const a = await user("alice");
  const post = (await api("POST", "/v2/posts", { key: a.key, body: { type: "talk", text: "hello" } })).data.post;
  await env.DB.prepare("UPDATE accounts SET discord_id = 'd-alice' WHERE id = ?").bind(a.id).run();
  const cid = String(seq++);
  const ev = (t, d) => ({ t, d });
  const msg = { id: cid, channel_id: post.id, content: "hey <@42> <:pog:1>", author: { id: "d-zed", username: "zed", global_name: "Zed" }, mentions: [{ id: "42", username: "max" }] };
  let r = await api("POST", "/v2/bot/events", { auth, body: { events: [
    ev("MESSAGE_CREATE", msg),
    ev("MESSAGE_CREATE", msg), // duplicate
    ev("MESSAGE_CREATE", { id: String(seq++), channel_id: post.id, content: "mine", webhook_id: "wh1", author: { id: "x", bot: true } }),
    ev("MESSAGE_CREATE", { id: String(seq++), channel_id: post.id, content: "from alice in discord", author: { id: "d-alice", username: "alice" } }),
  ] }, now: T0 + 5000 });
  assert.deepEqual(r.data.results, ["comment", "dup", "ours", "comment"]);
  const full = await api("GET", `/v2/posts/${post.id}`, { key: a.key });
  assert.equal(full.data.post.comments, 2);
  assert.equal(full.data.comments[0].text, "hey @max :pog:");
  assert.deepEqual(full.data.comments[0].author, { discord: true, nick: "Zed", avatar: null });
  assert.equal(full.data.comments[1].author.tag, "alice");
  const n = await api("GET", "/v2/notifications", { key: a.key });
  assert.equal(n.data.items.length, 1); // own Discord comment doesn't notify
  assert.deepEqual(n.data.items[0].actor, { discord: true, nick: "Zed" });

  r = await api("POST", "/v2/bot/events", { auth, body: { events: [
    ev("MESSAGE_UPDATE", { id: cid, channel_id: post.id, content: "edited" }),
    ev("MESSAGE_DELETE", { id: cid, channel_id: post.id }),
    ev("MESSAGE_CREATE", { id: "555", channel_id: "555", parent_id: "900", thread_name: "Discord post", applied_tags: ["t3"], content: "body", author: { id: "d-q", username: "q" } }),
    ev("THREAD_UPDATE", { id: post.id, applied_tags: ["t4", "t5"] }),
    ev("MODERATOR", { discordId: "d-alice", moderator: true }),
  ] }, now: T0 + 6000 });
  assert.deepEqual(r.data.results, ["edit", "comment", "post", "solved", "moderator"]);
  assert.ok(calls.some((c) => c.method === "PATCH" && c.path === `/api/v10/channels/${post.id}` && c.body.locked === true));
  const feed = await api("GET", "/v2/feed", { key: a.key });
  const dpost = feed.data.posts.find((p) => p.id === "555");
  assert.equal(dpost.type, "gg");
  assert.equal(dpost.text, "Discord post\nbody");
  assert.equal(feed.data.posts.find((p) => p.id === post.id).comments, 1);
  assert.equal((await api("GET", "/v2/me", { key: a.key })).data.account.moderator, true);
  // moderator can delete someone else's post
  assert.equal((await api("DELETE", "/v2/posts/555", { key: a.key })).status, 200);
  r = await api("POST", "/v2/bot/events", { auth, body: { events: [ev("THREAD_DELETE", { id: post.id })] }, now: T0 + 7000 });
  assert.equal((await api("GET", `/v2/posts/${post.id}`, { key: a.key })).status, 404);
});

// 2x1 PNG header is enough for the size check
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 2, 0, 0, 0, 1, 8, 6, 0, 0, 0]).toString("base64");

test("posts with a picture: upload to Discord, refresh expired links", async () => {
  const { api, user, calls, env } = setup();
  const a = await user("alice");
  const r = await api("POST", "/v2/posts", { key: a.key, body: { type: "gg", text: "", image: { type: "image/png", data: PNG } } });
  assert.equal(r.status, 201);
  const post = r.data.post;
  assert.equal(post.text, "");
  assert.equal(post.image.length, 1);
  assert.deepEqual([post.image[0].w, post.image[0].h], [2, 1]);
  const up = calls.find((c) => c.path === "/api/v10/webhooks/wh1/tok" && c.files.length);
  assert.equal(up.files[0].name, "screenshot.png");
  assert.equal(up.body.thread_name, "📷 ALICE");

  assert.equal((await api("POST", "/v2/posts", { key: a.key, body: { type: "gg", text: "x", image: { data: "aGVsbG8=" } } })).status, 400);
  assert.equal((await api("POST", "/v2/posts", { key: a.key, body: { type: "gg", text: "" } })).status, 400);

  // the stored link is expired (ex=6a000000 < T0): reading the feed refreshes and saves it
  env.DISCORD_BOT_TOKEN = "t";
  const feed = await api("GET", "/v2/feed", { key: a.key });
  assert.match(feed.data.posts[0].image[0].url, /ex=ffffffff/);
  const n = calls.filter((c) => c.path === "/api/v10/attachments/refresh-urls").length;
  await api("GET", "/v2/feed", { key: a.key });
  assert.equal(calls.filter((c) => c.path === "/api/v10/attachments/refresh-urls").length, n); // already fresh
});

test("profiles, search and the author filter", async () => {
  const { api, user } = setup();
  const a = await user("alice");
  const b = await user("bob_builder");
  await api("POST", "/v2/posts", { key: a.key, body: { type: "gg", text: "won" } });
  const help = (await api("POST", "/v2/posts", { key: a.key, body: { type: "help", text: "how?" } })).data.post;
  await api("POST", `/v2/posts/${help.id}/solve`, { key: a.key, body: { solved: true } });
  const bp = (await api("POST", "/v2/posts", { key: b.key, body: { type: "talk", text: "hi" } })).data.post;
  await api("POST", `/v2/posts/${bp.id}/react`, { key: a.key, body: { slot: 0 } });
  await api("POST", `/v2/posts/${bp.id}/comments`, { key: a.key, body: { text: "yo" } });

  const p = await api("GET", "/v2/users/@alice");
  assert.equal(p.status, 200);
  assert.deepEqual(p.data.user.stats, { posts: 2, comments: 1, reactions: 0, gg: 1, solved: 1 });
  const byId = await api("GET", `/v2/users/${b.id}`);
  assert.equal(byId.data.user.tag, "bob_builder");
  assert.equal(byId.data.user.stats.reactions, 1);
  assert.equal((await api("GET", "/v2/users/@nobody")).status, 404);

  const feed = await api("GET", `/v2/feed?space=global&author=${a.id}`, { key: b.key });
  assert.equal(feed.data.posts.length, 2);
  assert.ok(feed.data.posts.every((x) => x.author.id === a.id));

  assert.equal((await api("GET", "/v2/users?q=bob")).status, 401);
  const s1 = await api("GET", "/v2/users?q=%40bo", { key: a.key });
  assert.deepEqual(s1.data.users.map((u) => u.tag), ["bob_builder"]);
  const s2 = await api("GET", "/v2/users?q=LIC", { key: b.key }); // nick "ALICE" contains it
  assert.deepEqual(s2.data.users.map((u) => u.tag), ["alice"]);
  assert.deepEqual((await api("GET", "/v2/users?q=%25", { key: a.key })).data.users, []);
});

test("hashtags: parsing, tag feed, popular and prefix search", async () => {
  const { extractTags, normalizeTag } = await import("../src/hashtags.js");
  assert.deepEqual(extractTags("Убил дракона! #GG #Эндер, #ender_dragon #gg"), ["gg", "эндер", "ender_dragon"]);
  assert.deepEqual(extractTags("C# и a#b и #1 и #x и https://x.ru/#frag и &#39;"), []);
  assert.deepEqual(extractTags("#Ёлка"), ["елка"]);
  assert.deepEqual(extractTags("#" + "a".repeat(33)), []);
  assert.equal(extractTags(Array.from({ length: 15 }, (_, i) => "#t" + i + "x").join(" ")).length, 10);
  assert.equal(normalizeTag("#Майн"), "майн");

  const { api, user, env } = setup();
  const a = await user("alice");
  const b = await user("bob");
  const p1 = (await api("POST", "/v2/posts", { key: a.key, body: { type: "gg", text: "first #Дракон #build" } })).data.post;
  await api("POST", "/v2/posts", { key: a.key, body: { type: "talk", text: "second #дракон" }, now: T0 + 1000 });
  await api("POST", "/v2/posts", { key: b.key, body: { type: "talk", text: "no tags" }, now: T0 + 2000 });
  const f = await api("GET", "/v2/feed?tag=" + encodeURIComponent("#ДРАКОН"), { key: b.key, now: T0 + 3000 });
  assert.deepEqual(f.data.posts.map((p) => p.text), ["second #дракон", "first #Дракон #build"]);
  assert.equal((await api("GET", "/v2/feed?tag=x", { key: b.key })).status, 400);
  let h = await api("GET", "/v2/hashtags", { key: b.key, now: T0 + 3000 });
  assert.deepEqual(h.data.tags, [{ tag: "дракон", posts: 2 }, { tag: "build", posts: 1 }]);
  h = await api("GET", "/v2/hashtags?q=" + encodeURIComponent("#др"), { key: b.key, now: T0 + 3000 });
  assert.deepEqual(h.data.tags, [{ tag: "дракон", posts: 2 }]);
  h = await api("GET", "/v2/hashtags?q=%25", { key: b.key });
  assert.deepEqual(h.data.tags, []);
  // old posts drop out of "popular", deleted posts are not counted
  h = await api("GET", "/v2/hashtags", { key: b.key, now: T0 + 15 * 86_400_000 });
  assert.deepEqual(h.data.tags, []);
  await api("DELETE", "/v2/posts/" + p1.id, { key: a.key, now: T0 + 4000 });
  h = await api("GET", "/v2/hashtags", { key: b.key, now: T0 + 5000 });
  assert.deepEqual(h.data.tags, [{ tag: "дракон", posts: 1 }]);
  // closed spaces: only members
  assert.equal((await api("GET", "/v2/hashtags?space=nosuchspace", { key: b.key })).status >= 400, true);
  // Discord: a new forum post and its edit are indexed
  const auth = "Bearer bot-secret-0123456789";
  const tid = String(seq++);
  await api("POST", "/v2/bot/events", { auth, body: { events: [{ t: "MESSAGE_CREATE", d: { id: tid, channel_id: tid, parent_id: "900", thread_name: "Из дискорда", content: "смотрите #база", author: { id: "d-z", username: "z" } } }] }, now: T0 + 6000 });
  assert.equal((await api("GET", "/v2/feed?tag=база", { key: b.key, now: T0 + 7000 })).data.posts.length, 1);
  await api("POST", "/v2/bot/events", { auth, body: { events: [{ t: "MESSAGE_UPDATE", d: { id: tid, content: "смотрите #ферма", author: { id: "d-z", username: "z" } } }] }, now: T0 + 8000 });
  assert.equal((await api("GET", "/v2/feed?tag=база", { key: b.key, now: T0 + 9000 })).data.posts.length, 0);
  assert.equal((await api("GET", "/v2/feed?tag=ферма", { key: b.key, now: T0 + 9000 })).data.posts.length, 1);
  assert.ok(env);
});

test("editing posts and comments", async () => {
  const { api, user, env, calls } = setup();
  const a = await user("alice");
  const b = await user("bob");
  const post = (await api("POST", "/v2/posts", { key: a.key, body: { type: "talk", text: "old title\nbody #old" } })).data.post;
  const c = (await api("POST", `/v2/posts/${post.id}/comments`, { key: b.key, body: { text: "nice" }, now: T0 + 10 })).data.comment;
  await api("POST", "/v2/reports", { key: b.key, body: { postId: post.id, reason: "abuse" }, now: T0 + 20 });
  assert.equal((await api("PATCH", `/v2/posts/${post.id}`, { key: b.key, body: { text: "hack" } })).status, 403);
  calls.length = 0;
  const r = await api("PATCH", `/v2/posts/${post.id}`, { key: a.key, body: { text: "new title\nbody #new" }, now: T0 + 100 });
  assert.equal(r.status, 200);
  assert.equal(r.data.post.text, "new title\nbody #new");
  assert.equal(r.data.post.editedAt, T0 + 100);
  const edit = calls.find((x) => x.method === "PATCH" && x.path.startsWith("/api/v10/webhooks/wh1/tok/messages/"));
  assert.equal(edit.body.content, "new title\nbody #new");
  assert.equal(edit.search, `?thread_id=${post.id}`);
  assert.ok(calls.find((x) => x.method === "PATCH" && x.path === `/api/v10/channels/${post.id}` && x.body.name === "new title"));
  assert.equal((await api("GET", "/v2/feed?tag=old", { key: b.key })).data.posts.length, 0);
  assert.equal((await api("GET", "/v2/feed?tag=new", { key: b.key })).data.posts.length, 1);
  // the moderator sees what was reported
  await env.DB.prepare("UPDATE accounts SET moderator = 1 WHERE id = ?").bind(b.id).run();
  const q = await api("GET", "/v2/mod/reports", { key: b.key });
  assert.equal(q.data.reports[0].target.original, "old title\nbody #old");
  // comments
  assert.equal((await api("PATCH", `/v2/comments/${c.id}`, { key: a.key, body: { text: "x" } })).status, 403);
  const rc = await api("PATCH", `/v2/comments/${c.id}`, { key: b.key, body: { text: "very nice" }, now: T0 + 200 });
  assert.equal(rc.data.comment.text, "very nice");
  assert.equal(rc.data.comment.editedAt, T0 + 200);
  assert.equal((await api("PATCH", `/v2/comments/${c.id}`, { key: b.key, body: { text: "  " } })).status, 400);
  const s = await api("GET", `/v2/sync?space=global&since=${T0 + 150}&post=${post.id}`, { key: a.key, now: T0 + 300 });
  assert.equal(s.data.comments[0].text, "very nice");
});

test("polls, pins and top", async () => {
  const { api, user, env, calls } = setup();
  const a = await user("alice");
  const b = await user("bob");
  const bad = await api("POST", "/v2/posts", { key: a.key, body: { type: "help", text: "q", poll: { options: ["one"] } } });
  assert.equal(bad.status, 400);
  assert.equal((await api("POST", "/v2/posts", { key: a.key, body: { type: "help", text: "q", poll: { options: ["a", "A"] } } })).status, 400);
  calls.length = 0;
  const p = (await api("POST", "/v2/posts", { key: a.key, body: { type: "help", text: "Лучший биом?", poll: { options: ["Лес", " Пустыня ", "Горы"] } } })).data.post;
  assert.deepEqual(p.poll, { options: ["Лес", "Пустыня", "Горы"], votes: [0, 0, 0], my: -1 });
  const hook = calls.find((c) => c.path === "/api/v10/webhooks/wh1/tok");
  assert.match(hook.body.content, /Лучший биом\?\n\n📊 1️⃣ Лес\n2️⃣ Пустыня\n3️⃣ Горы/);
  let v = await api("POST", `/v2/posts/${p.id}/vote`, { key: b.key, body: { option: 1 }, now: T0 + 10 });
  assert.deepEqual(v.data.poll, { options: ["Лес", "Пустыня", "Горы"], votes: [0, 1, 0], my: 1 });
  await api("POST", `/v2/posts/${p.id}/vote`, { key: a.key, body: { option: 1 }, now: T0 + 11 });
  v = await api("POST", `/v2/posts/${p.id}/vote`, { key: b.key, body: { option: 2 }, now: T0 + 12 });
  assert.deepEqual(v.data.poll.votes, [0, 1, 1]);
  v = await api("POST", `/v2/posts/${p.id}/vote`, { key: b.key, body: { option: -1 }, now: T0 + 13 });
  assert.deepEqual(v.data.poll, { options: ["Лес", "Пустыня", "Горы"], votes: [0, 1, 0], my: -1 });
  assert.equal((await api("POST", `/v2/posts/${p.id}/vote`, { key: b.key, body: { option: 3 } })).status, 400);
  const feedPoll = (await api("GET", "/v2/feed", { key: a.key })).data.posts[0].poll;
  assert.deepEqual(feedPoll, { options: ["Лес", "Пустыня", "Горы"], votes: [0, 1, 0], my: 1 });
  // editing keeps the poll in the Discord copy
  calls.length = 0;
  await api("PATCH", `/v2/posts/${p.id}`, { key: a.key, body: { text: "Лучший биом для дома?" }, now: T0 + 20 });
  assert.match(calls.find((c) => c.method === "PATCH" && c.path.includes("/messages/")).body.content, /^Лучший биом для дома\?\n\n📊 1️⃣ Лес/);

  // pins: server moderators in the global feed, at most 3
  const plain = (await api("POST", "/v2/posts", { key: a.key, body: { type: "talk", text: "plain" }, now: T0 + 30 })).data.post;
  assert.equal((await api("POST", `/v2/posts/${plain.id}/pin`, { key: a.key, body: { pinned: true } })).status, 403);
  await env.DB.prepare("UPDATE accounts SET moderator = 1 WHERE id = ?").bind(b.id).run();
  const ids = [p.id, plain.id];
  for (let i = 0; i < 3; i++) ids.push((await api("POST", "/v2/posts", { key: a.key, body: { type: "talk", text: "n" + i }, now: T0 + 40 + i })).data.post.id);
  for (let i = 0; i < 4; i++) {
    const r = await api("POST", `/v2/posts/${ids[i]}/pin`, { key: b.key, body: { pinned: true }, now: T0 + 100 + i });
    assert.equal(r.data.post.pinned, true);
  }
  let f = (await api("GET", "/v2/feed", { key: a.key, now: T0 + 200 })).data;
  assert.deepEqual(f.pinned.map((x) => x.id), [ids[3], ids[2], ids[1]]);
  await api("POST", `/v2/posts/${ids[3]}/pin`, { key: b.key, body: { pinned: false }, now: T0 + 300 });
  f = (await api("GET", "/v2/feed", { key: a.key, now: T0 + 400 })).data;
  assert.deepEqual(f.pinned.map((x) => x.id), [ids[2], ids[1]]);
  assert.equal((await api("GET", "/v2/feed?type=talk", { key: a.key })).data.pinned, undefined);

  // top: reactions and comments of the last day
  await api("POST", `/v2/posts/${ids[4]}/react`, { key: b.key, body: { slot: 0 }, now: T0 + 500 });
  await api("POST", `/v2/posts/${plain.id}/comments`, { key: b.key, body: { text: "c" }, now: T0 + 501 });
  let top = (await api("GET", "/v2/feed?sort=top", { key: a.key, now: T0 + 1000 })).data;
  assert.deepEqual(top.posts.map((x) => x.id), [ids[4], plain.id]);
  assert.equal(top.next, null);
  top = (await api("GET", "/v2/feed?sort=top", { key: a.key, now: T0 + 2 * 86_400_000 })).data;
  assert.equal(top.posts.length, 0);
  top = (await api("GET", "/v2/feed?sort=top&days=7", { key: a.key, now: T0 + 2 * 86_400_000 })).data;
  assert.equal(top.posts.length, 2);
});
