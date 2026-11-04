import { test } from "node:test";
import assert from "node:assert/strict";
import { makeD1 } from "./d1shim.mjs";
import { route } from "../src/index.js";
import { HttpError, errorResponse } from "../src/util.js";
import { FLOW_TTL } from "../src/discord.js";

const MIG = new URL("../migrations", import.meta.url).pathname;
const T0 = 1_800_000_000_000;

function setup() {
  const users = {}; // code -> discord user
  const calls = [];
  const env = {
    DB: makeD1(MIG),
    DISCORD_CLIENT_ID: "123",
    DISCORD_CLIENT_SECRET: "sek",
    DISCORD_FETCH: async (url, init = {}) => {
      calls.push(url);
      if (url.endsWith("/oauth2/token")) {
        const code = new URLSearchParams(init.body).get("code");
        if (!users[code]) return new Response("{}", { status: 400 });
        return Response.json({ access_token: "at_" + code });
      }
      if (url.endsWith("/users/@me")) {
        const code = init.headers.authorization.replace("Bearer at_", "");
        return Response.json(users[code]);
      }
      if (url.endsWith("/revoke")) return new Response("", { status: 200 });
      return new Response("", { status: 404 });
    },
  };
  const raw = async (method, path, opts = {}) => {
    const headers = { "cf-connecting-ip": opts.ip || "1.1.1.1" };
    if (opts.key) headers.authorization = `Bearer ${opts.key}`;
    const req = new Request("https://x.test" + path, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) });
    try { return await route(req, env, opts.now ?? T0); } catch (e) { if (!(e instanceof HttpError)) throw e; return errorResponse(e); }
  };
  const api = async (method, path, opts) => {
    const res = await raw(method, path, opts);
    return { status: res.status, data: await res.json() };
  };
  const register = async (tag, ip) => (await api("POST", "/v2/auth/register", { body: { tag, nick: tag }, ip })).data;
  // full browser round-trip: start -> go -> callback; returns poll body
  const flow = async ({ kind, key, discord, deny }) => {
    const s = (await api("POST", "/v2/discord/start", { key, body: { kind } })).data;
    const go = await raw("GET", new URL(s.url).pathname);
    const code = "c" + Math.random().toString(36).slice(2);
    users[code] = discord;
    const cb = await raw("GET", `/v2/discord/callback?state=${s.state}` + (deny ? "&error=access_denied" : `&code=${code}`));
    const html = await cb.text();
    const p = await api("POST", "/v2/discord/poll", { body: { state: s.state, pollSecret: s.pollSecret } });
    return { s, go, cb, html, p };
  };
  return { env, raw, api, register, flow, calls };
}

test("link: go redirects to Discord, callback links, poll is one-time, handle == tag is protected", async () => {
  const t = setup();
  const a = await t.register("aboba");
  const { s, go, cb, html, p } = await t.flow({ kind: "link", key: a.key, discord: { id: "111", username: "aboba", discriminator: "0" } });
  assert.equal(go.status, 302);
  const loc = new URL(go.headers.get("location"));
  assert.equal(loc.host, "discord.com");
  assert.equal(loc.searchParams.get("client_id"), "123");
  assert.equal(loc.searchParams.get("scope"), "identify");
  assert.equal(loc.searchParams.get("state"), s.state);
  assert.equal(loc.searchParams.get("redirect_uri"), "https://x.test/v2/discord/callback");
  assert.ok(!go.headers.get("location").includes(s.pollSecret), "poll secret never in browser URL");
  assert.equal(cb.status, 200);
  assert.match(html, /DISCORD ПРИВЯЗАН/);
  assert.equal(p.data.status, "done");
  assert.equal(p.data.account.discordLinked, true);
  assert.equal(p.data.account.discordUsername, "aboba");
  assert.equal(p.data.account.tagProtected, true);
  assert.equal(p.data.key, undefined, "link never returns a key");
  assert.ok(t.calls.some((u) => u.endsWith("/revoke")), "discord token revoked");
  const again = await t.api("POST", "/v2/discord/poll", { body: { state: s.state, pollSecret: s.pollSecret } });
  assert.equal(again.status, 404);
});

test("link requires a key; poll with wrong secret is rejected", async () => {
  const t = setup();
  assert.equal((await t.api("POST", "/v2/discord/start", { body: { kind: "link" } })).status, 401);
  const a = await t.register("aboba");
  const s = (await t.api("POST", "/v2/discord/start", { key: a.key, body: { kind: "link" } })).data;
  assert.equal((await t.api("POST", "/v2/discord/poll", { body: { state: s.state, pollSecret: "nope" } })).status, 404);
  assert.deepEqual((await t.api("POST", "/v2/discord/poll", { body: { state: s.state, pollSecret: s.pollSecret } })).data, { status: "pending" });
});

test("claim: verified Discord handle takes an unprotected tag, old owner gets a replacement", async () => {
  const t = setup();
  const steve = await t.register("steve", "2.2.2.2");
  const real = await t.register("not_steve", "3.3.3.3");
  const { p } = await t.flow({ kind: "link", key: real.key, discord: { id: "222", username: "steve", discriminator: "0" } });
  assert.equal(p.data.account.tagProtected, false);
  assert.equal(p.data.account.claimableTag, "steve");
  const chk = await t.api("GET", "/v2/tags/check?tag=steve", { key: real.key });
  assert.deepEqual(chk.data, { tag: "steve", available: true, claim: true });
  assert.equal((await t.api("GET", "/v2/tags/check?tag=steve")).data.available, false, "anonymous still sees it taken");

  const r = await t.api("POST", "/v2/me/tag", { key: real.key, body: { tag: "steve" } });
  assert.equal(r.status, 200);
  assert.equal(r.data.account.tag, "steve");
  assert.equal(r.data.account.tagProtected, true);

  const old = (await t.api("GET", "/v2/me", { key: steve.key })).data.account;
  assert.equal(old.tag, "steve_2");
  assert.equal(old.lostTag, "steve");
  assert.equal(old.canChangeTagAt, null);
  const ack = await t.api("DELETE", "/v2/me/lost-tag", { key: steve.key });
  assert.equal(ack.data.account.lostTag, null);
  // the displaced owner may change right away
  assert.equal((await t.api("POST", "/v2/me/tag", { key: steve.key, body: { tag: "steve.old" } })).status, 200);

  // protected now: nobody (even a renamed Discord with the same handle) can take it
  const other = await t.register("someone", "4.4.4.4");
  await t.flow({ kind: "link", key: other.key, discord: { id: "333", username: "steve", discriminator: "0" } });
  const chk2 = await t.api("GET", "/v2/tags/check?tag=steve", { key: other.key });
  assert.equal(chk2.data.available, false);
  assert.equal(chk2.data.protected, true);
  const r2 = await t.api("POST", "/v2/me/tag", { key: other.key, body: { tag: "steve" } });
  assert.equal(r2.status, 409);
  assert.equal(r2.data.code, "tag_protected");
});

test("claim ignores cooldown; claiming a held tag works", async () => {
  const t = setup();
  const a = await t.register("first", "2.2.2.2");
  await t.api("POST", "/v2/me/tag", { key: a.key, body: { tag: "second" } }); // 'first' is now held for a
  const b = await t.register("bbb", "3.3.3.3");
  await t.api("POST", "/v2/me/tag", { key: b.key, body: { tag: "bbb2" } }); // b on cooldown
  await t.flow({ kind: "link", key: b.key, discord: { id: "444", username: "first", discriminator: "0" } });
  const r = await t.api("POST", "/v2/me/tag", { key: b.key, body: { tag: "first" } });
  assert.equal(r.status, 200);
  assert.equal(r.data.account.tag, "first");
  const r3 = await t.api("POST", "/v2/me/tag", { key: b.key, body: { tag: "third" } });
  assert.equal(r3.data.code, "tag_cooldown", "normal changes still have a cooldown");
});

test("login via Discord gives a new key; unknown Discord is refused", async () => {
  const t = setup();
  const a = await t.register("aboba");
  await t.flow({ kind: "link", key: a.key, discord: { id: "111", username: "aboba", discriminator: "0" } });
  const { p, cb } = await t.flow({ kind: "login", discord: { id: "111", username: "aboba", discriminator: "0" } });
  assert.equal(cb.status, 200);
  assert.equal(p.data.status, "done");
  assert.match(p.data.key, /^mfk_/);
  assert.equal((await t.api("GET", "/v2/me", { key: p.data.key })).data.account.tag, "aboba");
  assert.equal((await t.api("GET", "/v2/me", { key: a.key })).status, 200, "other PCs stay signed in");

  const none = await t.flow({ kind: "login", discord: { id: "999", username: "x_x", discriminator: "0" } });
  assert.equal(none.cb.status, 404);
  assert.deepEqual([none.p.data.status, none.p.data.code], ["error", "not_linked"]);
});

test("one Discord per profile, deny, legacy names, unlink, expiry", async () => {
  const t = setup();
  const a = await t.register("aaa", "2.2.2.2");
  const b = await t.register("bbb", "3.3.3.3");
  await t.flow({ kind: "link", key: a.key, discord: { id: "111", username: "aaa", discriminator: "0" } });
  const dup = await t.flow({ kind: "link", key: b.key, discord: { id: "111", username: "aaa", discriminator: "0" } });
  assert.equal(dup.p.data.code, "discord_taken");

  const denied = await t.flow({ kind: "link", key: b.key, deny: true });
  assert.equal(denied.p.data.code, "denied");

  const legacy = await t.flow({ kind: "link", key: b.key, discord: { id: "555", username: "Bob", discriminator: "1234" } });
  assert.equal(legacy.p.data.account.discordLinked, true);
  assert.equal(legacy.p.data.account.discordUsername, null);
  assert.equal(legacy.p.data.account.claimableTag, null);

  const u = await t.api("DELETE", "/v2/me/discord", { key: a.key });
  assert.equal(u.data.account.discordLinked, false);
  assert.equal(u.data.account.tagProtected, false);

  const s = (await t.api("POST", "/v2/discord/start", { key: a.key, body: { kind: "link" } })).data;
  const late = await t.raw("GET", `/v2/discord/go/${s.state}`, { now: T0 + FLOW_TTL + 1 });
  assert.equal(late.status, 410);
  const pl = await t.api("POST", "/v2/discord/poll", { body: { state: s.state, pollSecret: s.pollSecret }, now: T0 + FLOW_TTL + 1 });
  assert.equal(pl.data.code, "expired");
});

test("html page escapes Discord-provided text", async () => {
  const { page } = await import("../src/discord.js");
  const html = await page(200, "<script>", "ok").text();
  assert.ok(!html.includes("<script>"));
});
