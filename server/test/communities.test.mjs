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
      if (/^\/channels\/\d+\/webhooks$/.test(p)) return Response.json({ id: "wh" + p.split("/")[2], token: "tok" });
      if (/^\/webhooks\//.test(p)) { const id = String(seq++); return Response.json({ id, channel_id: u.searchParams.get("thread_id") || id }); }
      if (init.method === "DELETE" || init.method === "PATCH") return new Response(null, { status: 204 });
      return new Response("{}", { status: 404 });
    },
  };
  const api = async (method, path, opts = {}) => {
    const headers = { "cf-connecting-ip": opts.ip || "1.1.1.1" };
    if (opts.key) headers.authorization = `Bearer ${opts.key}`;
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
  return { env, api, user, calls, roles, guildMembers, channels };
}

test("channel names", () => {
  assert.equal(channelName("Проект Шахта!"), "проект-шахта");
  assert.equal(channelName("!!!"), "community");
});

test("create needs Discord on the server, one per owner", async () => {
  const { api, user, calls, roles, channels } = setup();
  const nod = await user("nodiscord");
  assert.equal((await api("POST", "/v2/communities", { key: nod.key, body: { name: "Test" } })).data.code, "discord_required");
  const out = await user("outsider", "d-out");
  const r0 = await api("POST", "/v2/communities", { key: out.key, body: { name: "Test" } });
  assert.equal(r0.data.code, "not_in_guild");
  assert.equal(r0.data.discordUrl, "https://discord.gg/x");

  const a = await user("alice", "d-alice");
  const r = await api("POST", "/v2/communities", { key: a.key, body: { name: "Проект Шахта", description: "копаем" } });
  assert.equal(r.status, 201);
  assert.equal(r.data.community.role, "owner");
  const forum = calls.find((c) => c.m === "POST" && c.body && c.body.type === 15);
  assert.equal(forum.body.parent_id, "cat1");
  assert.equal(forum.body.name, "проект-шахта");
  const ow = Object.fromEntries(forum.body.permission_overwrites.map((o) => [o.id, o]));
  assert.equal(ow.g.deny, String(1n << 10n));
  assert.ok(ow.srvmod);
  assert.equal(roles["d-alice"].size, 2); // mod + member role
  assert.equal((await api("POST", "/v2/communities", { key: a.key, body: { name: "Second" } })).data.code, "limit");
  const mine = await api("GET", "/v2/communities", { key: a.key });
  assert.equal(mine.data.communities.length, 1);
  assert.equal(mine.data.canCreate, false);
  assert.equal(channels.filter((c) => c.type === 15).length, 1);
});

test("full category -> a new one is created", async () => {
  const { api, user, calls } = setup({ categoryFull: true });
  const a = await user("alice", "d-alice");
  assert.equal((await api("POST", "/v2/communities", { key: a.key, body: { name: "Big" } })).status, 201);
  const cat = calls.find((c) => c.m === "POST" && c.body && c.body.type === 4);
  assert.equal(cat.body.name, "MineFeed communities 2");
  const forum = calls.find((c) => c.m === "POST" && c.body && c.body.type === 15);
  assert.notEqual(forum.body.parent_id, "cat1");
});

test("codes, joining, approval, members and privacy", async () => {
  const { api, user, env, roles } = setup();
  const a = await user("alice", "d-alice");
  const b = await user("bob", "d-bob");
  const c = await user("carol");
  const d = await user("dave");
  const e = await user("eve");
  const cid = (await api("POST", "/v2/communities", { key: a.key, body: { name: "Kuznya" } })).data.community.id;

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
  assert.equal(sa.data.posts.length, 1);
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
  assert.equal((await api("GET", "/v2/communities", { key: a.key })).data.canCreate, true);
});

test("Discord link later gives roles; bot MEMBER_JOIN too", async () => {
  const { api, user, env, roles } = setup();
  env.BOT_SECRET = "bot-secret-0123456789";
  const a = await user("alice", "d-alice");
  const z = await user("zed");
  const cid = (await api("POST", "/v2/communities", { key: a.key, body: { name: "Kuznya" } })).data.community.id;
  const code = (await api("POST", `/v2/communities/${cid}/codes`, { key: a.key, body: {} })).data.code.code;
  await api("POST", "/v2/join", { key: z.key, body: { code } });
  await env.DB.prepare("UPDATE accounts SET discord_id = 'd-zed' WHERE id = ?").bind(z.id).run();
  const req = new Request("https://x.test/v2/bot/events", { method: "POST", headers: { authorization: "Bearer bot-secret-0123456789" }, body: JSON.stringify({ events: [{ t: "MEMBER_JOIN", d: { discordId: "d-zed" } }] }) });
  const res = await (await route(req, env, T0)).json();
  assert.deepEqual(res.results, ["roles:1"]);
  assert.equal(roles["d-zed"].size, 1);
});
