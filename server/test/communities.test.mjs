import { test } from "node:test";
import assert from "node:assert/strict";
import { makeD1 } from "./d1shim.mjs";
import { route } from "../src/index.js";
import { HttpError, errorResponse } from "../src/util.js";
import { channelName } from "../src/communities.js";

const MIG = new URL("../migrations", import.meta.url).pathname;
const T0 = 1_800_000_000_000;
let seq = 1_600_000_000_000_000_000n;

function setup({ categoryFull = false } = {}) {
  const calls = [];
  const guildMembers = new Set(["d-alice", "d-bob"]);
  const roles = {}; // discordId -> Set(roleId)
  const channels = [{ id: "cat1", type: 4 }];
  if (categoryFull) for (let i = 0; i < 50; i++) channels.push({ id: "x" + i, type: 0, parent_id: "cat1" });
  const env = {
    DB: makeD1(MIG),
    DISCORD_GUILD_ID: "g", COMMUNITY_CATEGORY_ID: "cat1", MOD_ROLE_ID: "srvmod", DISCORD_INVITE: "https://discord.gg/x",
    FEED_FORUM_ID: "900", FEED_TAGS: "{}", COMMUNITY_MAX_MEMBERS: "3",
    BOT_SECRET: "bot-secret-0123456789", APPLICATIONS_CHANNEL_ID: "apps",
    DISCORD_FETCH: async (url, init = {}) => {
      const u = new URL(url);
      const p = u.pathname.replace("/api/v10", "");
      const body = init.body ? JSON.parse(init.body) : null;
      calls.push({ m: init.method || "GET", p, body });
      let mm;
      if (p === "/guilds/g/channels" && init.method === "GET") return Response.json(channels);
      if (p === "/guilds/g/channels" && init.method === "POST") {
        const id = String(seq++);
        channels.push({ id, type: body.type, parent_id: body.parent_id });
        return Response.json({ id, type: body.type, available_tags: (body.available_tags || []).map((t, i) => ({ ...t, id: id + "t" + i })) });
      }
      if (p === "/guilds/g/roles" && init.method === "POST") return Response.json({ id: "r" + seq++ });
      if ((mm = /^\/guilds\/g\/members\/([^/]+)$/.exec(p))) return guildMembers.has(mm[1]) ? Response.json({ user: { id: mm[1] } }) : new Response("{}", { status: 404 });
      if ((mm = /^\/guilds\/g\/members\/([^/]+)\/roles\/([^/]+)$/.exec(p))) {
        const set = roles[mm[1]] || (roles[mm[1]] = new Set());
        init.method === "PUT" ? set.add(mm[2]) : set.delete(mm[2]);
        return new Response(null, { status: 204 });
      }
      if (p === "/users/@me/channels" && init.method === "POST") return Response.json({ id: "dm-" + body.recipient_id });
      if (/^\/channels\/[^/]+\/messages$/.test(p) && init.method === "POST") return Response.json({ id: String(seq++) });
      if (/^\/channels\/\d+\/webhooks$/.test(p)) return Response.json({ id: "wh" + p.split("/")[2], token: "tok" });
      if (/^\/webhooks\//.test(p)) { const id = String(seq++); return Response.json({ id, channel_id: u.searchParams.get("thread_id") || id }); }
      if (init.method === "DELETE" || init.method === "PATCH") return new Response(null, { status: 204 });
      return new Response("{}", { status: 404 });
    },
  };
  const api = async (method, path, opts = {}) => {
    const headers = { "cf-connecting-ip": opts.ip || "1.1.1.1" };
    if (opts.key) headers.authorization = `Bearer ${opts.key}`;
    if (opts.bot) headers.authorization = `Bearer ${env.BOT_SECRET}`;
    const req = new Request("https://x.test" + path, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) });
    let res;
    try { res = await route(req, env, opts.now ?? T0); } catch (e) { if (!(e instanceof HttpError)) throw e; res = errorResponse(e); }
    return { status: res.status, data: await res.json() };
  };
  const user = async (tag, discordId) => {
    const r = await api("POST", "/v2/auth/register", { body: { tag, nick: tag }, ip: "9.9.9." + tag.length });
    if (discordId) await env.DB.prepare("UPDATE accounts SET discord_id = ? WHERE id = ?").bind(discordId, r.data.account.id).run();
    return { key: r.data.key, id: r.data.account.id };
  };
  // the normal way: application from the mod, Approve pressed in Discord
  const community = async (owner, name, extra = {}) => {
    const app = await api("POST", "/v2/communities/applications", { key: owner.key, body: { name, ...extra } });
    assert.equal(app.status, 201, JSON.stringify(app.data));
    const d = await api("POST", `/v2/bot/apply/${app.data.application.id}/decide`, { bot: true, body: { approve: true, staffId: "d-staff" } });
    assert.equal(d.data.status, "approved", JSON.stringify(d.data));
    return d.data.community.id;
  };
  return { env, api, user, community, calls, roles, guildMembers, channels };
}

test("channel names", () => {
  assert.equal(channelName("Проект Шахта!"), "проект-шахта");
  assert.equal(channelName("!!!"), "community");
});

test("players apply, staff approve in Discord", async () => {
  const { api, user, env, calls, roles, channels } = setup();
  const apply = (u, body) => api("POST", "/v2/communities/applications", { key: u.key, body });
  const nod = await user("nodiscord");
  assert.equal((await apply(nod, { name: "Test" })).data.code, "discord_required");
  const out = await user("outsider", "d-out");
  const r0 = await apply(out, { name: "Test" });
  assert.equal(r0.data.code, "not_in_guild");
  assert.equal(r0.data.discordUrl, "https://discord.gg/x");

  const a = await user("alice", "d-alice");
  assert.equal((await api("POST", "/v2/communities", { key: a.key, body: { name: "Direct" } })).data.code, "apply_instead");
  const r = await apply(a, { name: "Проект Шахта", description: "копаем", about: "Большая шахта до бедрока", links: "https://example.com", lang: "ru-RU" });
  assert.equal(r.status, 201);
  const appId = r.data.application.id;
  assert.equal(r.data.application.status, "pending");
  const staff = calls.find((c) => c.m === "POST" && c.p === "/channels/apps/messages");
  assert.equal(staff.body.components[0].components[0].custom_id, `mfapp:approve:${appId}`);
  assert.equal(staff.body.components[0].components[1].custom_id, `mfapp:reject:${appId}`);
  assert.equal((await apply(a, { name: "Again" })).data.code, "already_applied");
  let mine = (await api("GET", "/v2/communities", { key: a.key })).data;
  assert.equal(mine.application.status, "pending");
  assert.equal(mine.canApply, false);

  // bot side
  assert.equal((await api("POST", "/v2/bot/apply/check", { body: { discordId: "d-alice" } })).status, 401);
  assert.deepEqual((await api("POST", "/v2/bot/apply/check", { bot: true, body: { discordId: "d-alice" } })).data, { ok: false, code: "already_applied" });
  assert.deepEqual((await api("POST", "/v2/bot/apply/check", { bot: true, body: { discordId: "d-nobody" } })).data, { ok: false, code: "not_linked" });
  assert.deepEqual((await api("POST", "/v2/bot/apply/check", { bot: true, body: { discordId: "d-bob" } })).data, { ok: false, code: "not_linked" });

  const d = await api("POST", `/v2/bot/apply/${appId}/decide`, { bot: true, body: { approve: true, staffId: "d-staff" } });
  assert.equal(d.data.status, "approved");
  assert.equal(d.data.community.role, "owner");
  const forum = calls.find((c) => c.m === "POST" && c.body && c.body.type === 15);
  assert.equal(forum.body.parent_id, "cat1");
  assert.equal(forum.body.name, "проект-шахта");
  const ow = Object.fromEntries(forum.body.permission_overwrites.map((o) => [o.id, o]));
  assert.equal(ow.g.deny, String(1n << 10n));
  assert.ok(ow.srvmod);
  assert.equal(roles["d-alice"].size, 2); // mod + member role
  assert.equal((await api("POST", `/v2/bot/apply/${appId}/decide`, { bot: true, body: { approve: false } })).data.code, "already_decided");

  // welcome thread in Discord only (creator + "about" comment), pinned and locked; nothing in the mod feed
  const hooks = calls.filter((c) => c.m === "POST" && c.p.startsWith("/webhooks/"));
  assert.match(hooks[0].body.content, /Создатель: alice \(@alice\)/);
  assert.match(hooks[1].body.content, /О проекте/);
  assert.match(hooks[1].body.content, /example\.com/);
  const lock = calls.find((c) => c.m === "PATCH" && c.body && c.body.locked);
  assert.deepEqual(lock.body, { flags: 2, locked: true });
  const feed = (await api("GET", `/v2/feed?space=${d.data.community.id}`, { key: a.key })).data;
  assert.equal(feed.posts.length, 0);
  // notification, DM, staff message without buttons
  const n = await env.DB.prepare("SELECT kind FROM notifications WHERE account_id = ?").bind(a.id).first();
  assert.equal(n.kind, "app_approved");
  assert.ok(calls.find((c) => c.p === "/channels/dm-d-alice/messages" && /одобрено/.test(c.body.content)));
  const edit = calls.find((c) => c.m === "PATCH" && c.p.startsWith("/channels/apps/messages/"));
  assert.deepEqual(edit.body.components, []);

  // editing the community rewrites the welcome post and its "about" comment
  const cid = d.data.community.id;
  const thread = hooks[0] && calls.find((c) => c.m === "PATCH" && c.body && c.body.locked).p.split("/")[2];
  calls.length = 0;
  const up = await api("PATCH", `/v2/communities/${cid}`, { key: a.key, body: { name: "Шахта 2", about: "Теперь глубже" } });
  assert.equal(up.status, 200, JSON.stringify(up.data));
  assert.equal(up.data.community.about, "Теперь глубже");
  assert.ok(calls.find((c) => c.m === "PATCH" && c.p === `/channels/${thread}` && c.body.name === "Шахта 2"));
  const edits = calls.filter((c) => c.m === "PATCH" && c.p.startsWith("/webhooks/"));
  assert.equal(edits.length, 2);
  assert.match(edits[0].body.content, /Добро пожаловать в «Шахта 2»/);
  assert.match(edits[1].body.content, /Теперь глубже/);
  assert.match(edits[1].body.content, /example\.com/);
  calls.length = 0;
  await api("PATCH", `/v2/communities/${cid}`, { key: a.key, body: { about: "", links: "" } });
  assert.ok(calls.find((c) => c.m === "DELETE" && c.p.startsWith("/webhooks/")));
  calls.length = 0;
  await api("PATCH", `/v2/communities/${cid}`, { key: a.key, body: { about: "Снова" } });
  const posted = calls.find((c) => c.m === "POST" && c.p.startsWith("/webhooks/"));
  assert.match(posted.body.content, /Снова/);
  assert.ok(calls.find((c) => c.m === "PATCH" && c.body && c.body.locked === false));
  const row = await env.DB.prepare("SELECT welcome_comment_id FROM communities WHERE id = ?").bind(cid).first();
  assert.ok(row.welcome_comment_id);
  // the owner's skin becomes the webhook avatar
  assert.equal(posted.body.avatar_url, undefined);
  await api("PATCH", "/v2/me", { key: a.key, body: { skin: "069A79F4-44E9-4726-A5BE-FCA90E38AAF5" } });
  calls.length = 0;
  await api("PATCH", `/v2/communities/${cid}`, { key: a.key, body: { about: "Ещё раз" } });
  await api("POST", "/v2/posts", { key: a.key, body: { space: cid, type: "talk", text: "hi" } });
  const hp = calls.find((c) => c.m === "POST" && c.p.startsWith("/webhooks/") && c.body.content === "hi");
  assert.equal(hp.body.avatar_url, "https://mc-heads.net/avatar/069a79f444e94726a5befca90e38aaf5/128");
  assert.equal((await api("GET", "/v2/users/alice")).data.user.avatar, "skin:069a79f444e94726a5befca90e38aaf5");
  await env.DB.prepare("UPDATE accounts SET discord_avatar = ?, discord_id = '123456789' WHERE id = ?").bind("a_" + "1".repeat(32), a.id).run();
  let me = (await api("PATCH", "/v2/me", { key: a.key, body: { avatar: "discord" } })).data.account;
  assert.equal(me.avatar, `discord:123456789/a_${"1".repeat(32)}`);
  await env.DB.prepare("UPDATE accounts SET discord_id = 'd-alice' WHERE id = ?").bind(a.id).run();
  me = (await api("PATCH", "/v2/me", { key: a.key, body: { avatar: "none" } })).data.account;
  assert.equal(me.avatar, null);
  assert.equal((await api("PATCH", "/v2/me", { key: a.key, body: { avatar: "cat" } })).data.code, "bad_avatar");
  assert.equal((await api("PATCH", "/v2/me", { key: a.key, body: { skin: "nope" } })).data.code, "bad_skin");

  mine = (await api("GET", "/v2/communities", { key: a.key })).data;
  assert.equal(mine.communities.length, 1);
  assert.equal(mine.canApply, false);
  assert.equal((await apply(a, { name: "Second" })).data.code, "limit");
  assert.equal(channels.filter((c) => c.type === 15).length, 1);

  // rejection with a reason, then a new application from Discord
  const b = await user("bob", "d-bob");
  const rb = await apply(b, { name: "Bobland", lang: "en" });
  const rej = await api("POST", `/v2/bot/apply/${rb.data.application.id}/decide`, { bot: true, body: { approve: false, staffId: "d-staff", reason: "  too   vague " } });
  assert.equal(rej.data.status, "rejected");
  mine = (await api("GET", "/v2/communities", { key: b.key })).data;
  assert.equal(mine.application.status, "rejected");
  assert.equal(mine.application.reason, "too vague");
  assert.equal(mine.canApply, true);
  assert.ok(calls.find((c) => c.p === "/channels/dm-d-bob/messages" && /declined/.test(c.body.content)));
  assert.deepEqual((await api("POST", "/v2/bot/apply/check", { bot: true, body: { discordId: "d-bob" } })).data, { ok: true, tag: "bob" });
  const viaBot = await api("POST", "/v2/bot/apply", { bot: true, body: { discordId: "d-bob", name: "Bobland 2", about: "x".repeat(1000), locale: "en-US" } });
  assert.equal(viaBot.status, 201);
  assert.equal(channels.filter((c) => c.type === 15).length, 1);
});

test("server moderators create directly; full category -> a new one", async () => {
  const { api, user, env, calls } = setup({ categoryFull: true });
  const m = await user("modder", "d-alice");
  await env.DB.prepare("UPDATE accounts SET moderator = 1 WHERE id = ?").bind(m.id).run();
  assert.equal((await api("POST", "/v2/communities", { key: m.key, body: { name: "Big" } })).status, 201);
  const cat = calls.find((c) => c.m === "POST" && c.body && c.body.type === 4);
  assert.equal(cat.body.name, "MineFeed communities 2");
  const forum = calls.find((c) => c.m === "POST" && c.body && c.body.type === 15);
  assert.notEqual(forum.body.parent_id, "cat1");
});

test("codes, joining, approval, members and privacy", async () => {
  const { api, user, community, env, roles } = setup();
  const a = await user("alice", "d-alice");
  const b = await user("bob", "d-bob");
  const c = await user("carol");
  const d = await user("dave");
  const e = await user("eve");
  const cid = await community(a, "Kuznya");

  // non-members can't see it, can't post into it
  assert.equal((await api("GET", `/v2/communities/${cid}`, { key: b.key })).status, 404);
  assert.equal((await api("GET", `/v2/feed?space=${cid}`, { key: b.key })).status, 404);
  assert.equal((await api("POST", "/v2/posts", { key: b.key, body: { space: cid, type: "talk", text: "x" } })).status, 404);
  assert.equal((await api("POST", `/v2/communities/${cid}/codes`, { key: b.key, body: {} })).status, 404);

  // codes
  const rnd = (await api("POST", `/v2/communities/${cid}/codes`, { key: a.key, body: { note: "for Discord", maxUses: 1 } })).data.code;
  assert.match(rnd.code, /^MF-[A-Z2-9]{4}-[A-Z2-9]{4}$/);
  const custom = await api("POST", `/v2/communities/${cid}/codes`, { key: a.key, body: { code: "kuznec", approval: true, expiresInDays: 7 } });
  assert.equal(custom.data.code.code, "KUZNEC");
  assert.equal((await api("POST", `/v2/communities/${cid}/codes`, { key: a.key, body: { code: "kuznec" } })).data.code, "code_taken");
  assert.equal((await api("POST", `/v2/communities/${cid}/codes`, { key: a.key, body: { code: "a b" } })).status, 400);
  // 0 = no limit / forever (the mod's Gson drops JSON nulls)
  const edited = await api("PATCH", `/v2/communities/${cid}/codes/KUZNEC`, { key: a.key, body: { maxUses: 0, expiresInDays: 0, note: "lichno" } });
  assert.equal(edited.data.code.maxUses, null);
  assert.equal(edited.data.code.expiresAt, null);
  assert.equal(edited.data.code.note, "lichno");
  await api("PATCH", `/v2/communities/${cid}/codes/KUZNEC`, { key: a.key, body: { expiresInDays: 7, note: "" } });

  // instant join by code; Discord role for linked players
  let j = await api("POST", "/v2/join", { key: b.key, body: { code: rnd.code.toLowerCase() } });
  assert.equal(j.data.status, "joined");
  assert.ok(roles["d-bob"].size === 1);
  assert.equal((await api("POST", "/v2/join", { key: c.key, body: { code: rnd.code } })).data.code, "code_used_up");
  assert.equal((await api("POST", "/v2/join", { key: c.key, body: { code: "NOPE-NOPE" } })).status, 404);
  assert.equal((await api("POST", "/v2/join", { key: c.key, body: { code: "KUZNEC" }, now: T0 + 8 * 86_400_000 })).data.code, "code_expired");

  // approval
  j = await api("POST", "/v2/join", { key: c.key, body: { code: "KUZNEC" } });
  assert.equal(j.data.status, "pending");
  const na = await api("GET", "/v2/notifications", { key: a.key });
  assert.equal(na.data.items[0].kind, "request");
  assert.equal(na.data.items[0].communityName, "Kuznya");
  assert.equal((await api("GET", `/v2/communities`, { key: c.key })).data.pending[0].name, "Kuznya");
  const reqs = await api("GET", `/v2/communities/${cid}/requests`, { key: a.key });
  assert.equal(reqs.data.requests[0].account.id, c.id);
  assert.equal((await api("POST", `/v2/communities/${cid}/requests/${c.id}`, { key: b.key, body: { approve: true } })).status, 403);
  assert.equal((await api("POST", `/v2/communities/${cid}/requests/${c.id}`, { key: a.key, body: { approve: true } })).data.status, "joined");
  assert.equal((await api("GET", "/v2/notifications", { key: c.key })).data.items[0].kind, "accepted");

  // community full (max 3)
  assert.equal((await api("POST", "/v2/join", { key: d.key, body: { code: "KUZNEC" } })).data.code, "full");

  // posting, unread, mentions only reach members
  const post = (await api("POST", "/v2/posts", { key: b.key, body: { space: cid, type: "talk", text: "hi @alice @eve" }, now: T0 + 1000 })).data.post;
  assert.equal(post.space, cid);
  const sa = await api("GET", `/v2/sync?since=${T0}&space=${cid}`, { key: a.key, now: T0 + 2000 });
  assert.deepEqual(sa.data.communities, [{ id: cid, unread: 1 }]);
  assert.ok(sa.data.posts.some((p) => p.id === post.id && !p.pinned));
  await api("GET", `/v2/feed?space=${cid}`, { key: a.key, now: T0 + 3000 });
  assert.equal((await api("GET", `/v2/communities`, { key: a.key })).data.communities[0].unread, 0);
  assert.equal((await api("GET", "/v2/notifications", { key: e.key })).data.items.length, 0);
  assert.equal((await api("GET", "/v2/feed", { key: e.key })).data.posts.length, 0); // not in the global feed

  // roles & moderation
  assert.equal((await api("DELETE", `/v2/posts/${post.id}`, { key: c.key })).status, 403);
  assert.equal((await api("PATCH", `/v2/communities/${cid}/members/${c.id}`, { key: b.key, body: { role: "mod" } })).status, 403);
  assert.equal((await api("PATCH", `/v2/communities/${cid}/members/${c.id}`, { key: a.key, body: { role: "mod" } })).data.role, "mod");
  assert.equal((await api("DELETE", `/v2/posts/${post.id}`, { key: c.key })).status, 200); // community mod
  assert.equal((await api("DELETE", `/v2/communities/${cid}/members/${a.id}`, { key: c.key })).status, 403);
  const members = await api("GET", `/v2/communities/${cid}/members`, { key: b.key });
  assert.deepEqual(members.data.members.map((m) => m.role), ["owner", "mod", "member"]);
  assert.equal(members.data.members[0].via, undefined); // only mods see how people joined
  assert.ok("avatar" in members.data.members[0].account);

  // kick + ban; leave
  assert.equal((await api("DELETE", `/v2/communities/${cid}/members/${b.id}?ban=1`, { key: c.key })).status, 200);
  assert.equal(roles["d-bob"].size, 0);
  const again = (await api("POST", `/v2/communities/${cid}/codes`, { key: a.key, body: {} })).data.code.code;
  assert.equal((await api("POST", "/v2/join", { key: b.key, body: { code: again } })).data.code, "banned_here");
  assert.equal((await api("POST", `/v2/communities/${cid}/leave`, { key: a.key })).data.code, "owner_cannot_leave");
  assert.equal((await api("POST", `/v2/communities/${cid}/leave`, { key: c.key })).status, 200);
  assert.equal((await env.DB.prepare("SELECT members FROM communities WHERE id = ?").bind(cid).first()).members, 1);

  // rename + delete
  assert.equal((await api("PATCH", `/v2/communities/${cid}`, { key: a.key, body: { name: "Kuznya 2" } })).data.community.name, "Kuznya 2");
  assert.equal((await api("DELETE", `/v2/communities/${cid}`, { key: a.key })).status, 200);
  assert.equal((await api("GET", `/v2/communities/${cid}`, { key: a.key })).status, 404);
  assert.equal((await api("GET", "/v2/communities", { key: a.key })).data.canApply, true);
});

test("Discord link later gives roles; bot MEMBER_JOIN too", async () => {
  const { api, user, community, env, roles } = setup();
  const a = await user("alice", "d-alice");
  const z = await user("zed");
  const cid = await community(a, "Kuznya");
  const code = (await api("POST", `/v2/communities/${cid}/codes`, { key: a.key, body: {} })).data.code.code;
  await api("POST", "/v2/join", { key: z.key, body: { code } });
  await env.DB.prepare("UPDATE accounts SET discord_id = 'd-zed' WHERE id = ?").bind(z.id).run();
  const req = new Request("https://x.test/v2/bot/events", { method: "POST", headers: { authorization: "Bearer bot-secret-0123456789" }, body: JSON.stringify({ events: [{ t: "MEMBER_JOIN", d: { discordId: "d-zed" } }] }) });
  const res = await (await route(req, env, T0)).json();
  assert.deepEqual(res.results, ["roles:1"]);
  assert.equal(roles["d-zed"].size, 1);
});

test("moderation: reports queue, resolve, bans", async () => {
  const { api, user, community, env } = setup();
  const DAYMS = 86_400_000;
  const a = await user("alice", "d-alice");   // community owner
  const b = await user("bob", "d-bob");
  const c = await user("carol");
  const m = await user("staffer");
  await env.DB.prepare("UPDATE accounts SET moderator = 1 WHERE id = ?").bind(m.id).run();
  const cid = await community(a, "Kuznya");
  const code = (await api("POST", `/v2/communities/${cid}/codes`, { key: a.key, body: {} })).data.code.code;
  await api("POST", "/v2/join", { key: b.key, body: { code } });
  await api("POST", "/v2/join", { key: c.key, body: { code } });

  const gpost = (await api("POST", "/v2/posts", { key: b.key, body: { type: "talk", text: "buy gold" } })).data.post;
  const cpost = (await api("POST", "/v2/posts", { key: b.key, body: { space: cid, type: "talk", text: "rude" } })).data.post;
  const com = (await api("POST", `/v2/posts/${cpost.id}/comments`, { key: c.key, body: { text: "meh" } })).data.comment;

  // reporting: no self-reports, duplicates are ignored
  assert.equal((await api("POST", "/v2/reports", { key: b.key, body: { postId: gpost.id, reason: "spam" } })).data.code, "own_content");
  assert.equal((await api("POST", "/v2/reports", { key: c.key, body: { postId: gpost.id, reason: "spam" } })).status, 200);
  assert.equal((await api("POST", "/v2/reports", { key: c.key, body: { postId: gpost.id, reason: "spam" } })).data.duplicate, true);
  await api("POST", "/v2/reports", { key: a.key, body: { postId: gpost.id, reason: "scam", details: "  fake  shop " } });
  await api("POST", "/v2/reports", { key: c.key, body: { postId: cpost.id, reason: "abuse" } });
  await api("POST", "/v2/reports", { key: b.key, body: { postId: cpost.id, commentId: com.id, reason: "other", details: "x" } });

  // who sees what
  assert.equal((await api("GET", "/v2/mod/reports", { key: c.key })).data.code, "not_mod");
  const own = (await api("GET", "/v2/mod/reports", { key: a.key })).data.reports;
  assert.equal(own.length, 2); // community owner: only the community
  assert.ok(own.every((r) => r.space === cid && r.spaceName === "Kuznya" && r.canKick && !r.canBan));
  const all = (await api("GET", "/v2/mod/reports", { key: m.key })).data.reports;
  assert.equal(all.length, 3);
  const g = all.find((r) => r.postId === gpost.id);
  assert.equal(g.count, 2);
  assert.deepEqual(g.reasons.sort(), ["scam", "spam"]);
  assert.deepEqual(g.details, ["fake shop"]);
  assert.equal(g.target.author.tag, "bob");
  assert.equal(g.canBan, true);
  assert.equal((await api("GET", "/v2/communities", { key: a.key })).data.reportsOpen, 2);
  assert.equal((await api("GET", "/v2/communities", { key: m.key })).data.reportsOpen, 3);

  // resolve: community mods can't touch the global feed; dismiss / delete
  assert.equal((await api("POST", "/v2/mod/resolve", { key: a.key, body: { postId: gpost.id, action: "dismiss" } })).status, 403);
  assert.equal((await api("POST", "/v2/mod/resolve", { key: a.key, body: { postId: cpost.id, commentId: com.id, action: "dismiss" } })).status, 200);
  assert.equal((await api("POST", "/v2/mod/resolve", { key: a.key, body: { postId: cpost.id, action: "delete" } })).status, 200);
  assert.equal((await api("GET", `/v2/posts/${cpost.id}`, { key: a.key })).status, 404);
  assert.equal((await api("GET", "/v2/mod/reports", { key: a.key })).data.reports.length, 0);
  const log = (await env.DB.prepare("SELECT action FROM mod_log ORDER BY id").all()).results.map((r) => r.action);
  assert.deepEqual(log, ["dismiss", "delete_post"]);

  // bans: only server moderators, not other moderators; temporary bans end by themselves
  assert.equal((await api("POST", `/v2/users/${b.id}/ban`, { key: a.key, body: { days: 1 } })).status, 403);
  assert.equal((await api("POST", `/v2/users/${m.id}/ban`, { key: m.key, body: { days: 1 } })).data.code, "self");
  assert.equal((await api("POST", `/v2/users/${b.id}/ban`, { key: m.key, body: { days: 3 } })).data.code, "bad_days");
  const ban = await api("POST", `/v2/users/${b.id}/ban`, { key: m.key, body: { days: 7, reason: "spam shop", postId: gpost.id, deleteContent: true } });
  assert.equal(ban.data.until, T0 + 7 * DAYMS);
  const denied = await api("GET", "/v2/feed", { key: b.key });
  assert.equal(denied.status, 403);
  assert.equal(denied.data.code, "banned");
  assert.equal(denied.data.until, T0 + 7 * DAYMS);
  assert.equal(denied.data.reason, "spam shop");
  assert.equal((await api("GET", `/v2/posts/${gpost.id}`, { key: c.key })).status, 404); // deleted with the ban
  assert.equal((await api("GET", "/v2/mod/reports", { key: m.key })).data.reports.length, 0);
  assert.equal((await api("GET", `/v2/users/${b.id}`)).status, 404);
  const seen = await api("GET", `/v2/users/${b.id}`, { key: m.key });
  assert.equal(seen.data.user.banned, true);
  assert.equal(seen.data.user.banReason, "spam shop");
  assert.equal((await api("GET", "/v2/feed", { key: b.key, now: T0 + 8 * DAYMS })).status, 200); // expired
  await api("POST", `/v2/users/${b.id}/ban`, { key: m.key, body: { days: 0 } });
  assert.equal((await api("GET", "/v2/feed", { key: b.key, now: T0 + 400 * DAYMS })).status, 403);
  assert.equal((await api("DELETE", `/v2/users/${b.id}/ban`, { key: m.key })).status, 200);
  assert.equal((await api("GET", "/v2/feed", { key: b.key })).status, 200);
  assert.equal((await api("DELETE", `/v2/users/${b.id}/ban`, { key: m.key })).data.code, "not_banned");

  // kicking from a community through a report
  const p2 = (await api("POST", "/v2/posts", { key: c.key, body: { space: cid, type: "talk", text: "again" } })).data.post;
  await api("POST", "/v2/reports", { key: b.key, body: { postId: p2.id, reason: "abuse" } });
  assert.equal((await api("POST", "/v2/mod/resolve", { key: a.key, body: { postId: p2.id, action: "kick" } })).status, 200);
  assert.equal((await api("GET", `/v2/communities/${cid}`, { key: c.key })).status, 404);
  assert.equal((await api("POST", "/v2/join", { key: c.key, body: { code } })).data.code, "banned_here");
});

test("18+ communities", async () => {
  const { api, user, community, env, calls } = setup();
  const a = await user("alice", "d-alice");
  const b = await user("bob", "d-bob");
  const cid = await community(a, "Nochnye");
  const forum = (await env.DB.prepare("SELECT forum_id FROM communities WHERE id = ?").bind(cid).first()).forum_id;

  // the owner turns it on (after confirming the age): the Discord forum becomes age-restricted
  assert.equal((await api("PATCH", `/v2/communities/${cid}`, { key: a.key, body: { nsfw: true } })).data.code, "need_18");
  await api("PATCH", "/v2/me", { key: a.key, body: { showNsfw: true } });
  const on = await api("PATCH", `/v2/communities/${cid}`, { key: a.key, body: { nsfw: true } });
  assert.equal(on.data.community.nsfw, true);
  assert.ok(calls.some((c) => c.m === "PATCH" && c.p === `/channels/${forum}` && c.body.nsfw === true));
  // only a server moderator can take it off
  assert.equal((await api("PATCH", `/v2/communities/${cid}`, { key: a.key, body: { nsfw: false } })).data.code, "nsfw_locked");

  // posts inside are not marked one by one, and "nsfw" outside an 18+ community is refused
  const p = await api("POST", "/v2/posts", { key: a.key, body: { space: cid, type: "talk", text: "x" } });
  assert.equal(p.status, 201);
  assert.equal(p.data.post.nsfw, undefined);

  // joining asks for the 18+ setting
  const code = (await api("POST", `/v2/communities/${cid}/codes`, { key: a.key, body: {} })).data.code.code;
  assert.equal((await api("POST", "/v2/join", { key: b.key, body: { code } })).data.code, "need_18");
  await api("PATCH", "/v2/me", { key: b.key, body: { showNsfw: true } });
  assert.equal((await api("POST", "/v2/join", { key: b.key, body: { code } })).data.status, "joined");

  // a server moderator (here the owner is made one) can take the mark off
  await env.DB.prepare("UPDATE accounts SET moderator = 1 WHERE id = ?").bind(a.id).run();
  const off = await api("PATCH", `/v2/communities/${cid}`, { key: a.key, body: { nsfw: false } });
  assert.equal(off.data.community.nsfw, false);
});
