import { test } from "node:test";
import assert from "node:assert/strict";
import { makeD1 } from "./d1shim.mjs";
import { route } from "../src/index.js";
import worker from "../src/index.js";
import { DAY } from "../src/accounts.js";

const MIG = new URL("../migrations", import.meta.url).pathname;
let clock = 1_800_000_000_000;

function setup() {
  const env = { DB: makeD1(MIG), MIN_CLIENT: "0.2.0" };
  // route() throws HttpError; emulate the top-level handler
  const api = async (...a) => {
    const [method, path, opts = {}] = a;
    const headers = { "cf-connecting-ip": opts.ip || "1.1.1.1" };
    if (opts.key) headers.authorization = `Bearer ${opts.key}`;
    const req = new Request("https://x.test" + path, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) });
    const { HttpError, errorResponse } = await import("../src/util.js");
    let res;
    try { res = await route(req, env, opts.now ?? clock); } catch (e) { if (!(e instanceof HttpError)) throw e; res = errorResponse(e); }
    return { status: res.status, data: await res.json() };
  };
  return { env, api };
}

test("register → me → public profile", async () => {
  const { api } = setup();
  const r = await api("POST", "/v2/auth/register", { body: { tag: "@Aboba", nick: "  Абоба  " } });
  assert.equal(r.status, 201);
  assert.equal(r.data.account.tag, "aboba");
  assert.equal(r.data.account.nick, "Абоба");
  assert.match(r.data.key, /^mfk_[A-Za-z0-9_-]{43}$/);
  assert.match(r.data.recoveryCode, /^([A-Z2-9]{4}-){4}[A-Z2-9]{4}$/);
  const me = await api("GET", "/v2/me", { key: r.data.key });
  assert.equal(me.status, 200);
  assert.equal(me.data.account.id, r.data.account.id);
  const pub = await api("GET", "/v2/users/@aboba");
  assert.equal(pub.data.user.nick, "Абоба");
  assert.equal(pub.data.user.id, r.data.account.id);
});

test("key is never stored in plain text", async () => {
  const { api, env } = setup();
  const r = await api("POST", "/v2/auth/register", { body: { tag: "steve", nick: "Стив" } });
  const rows = env.DB.raw.prepare("SELECT * FROM sessions").all();
  assert.equal(rows.length, 1);
  assert.notEqual(rows[0].key_hash, r.data.key);
  const dump = JSON.stringify(env.DB.raw.prepare("SELECT * FROM accounts").all());
  assert.ok(!dump.includes(r.data.recoveryCode.replace(/-/g, "")));
});

test("auth errors", async () => {
  const { api } = setup();
  assert.equal((await api("GET", "/v2/me")).status, 401);
  assert.equal((await api("GET", "/v2/me", { key: "mfk_" + "A".repeat(43) })).data.code, "bad_auth");
  assert.equal((await api("GET", "/v2/me", { key: "garbage" })).data.code, "no_auth");
});

test("tag validation, uniqueness, reserved, case-insensitive", async () => {
  const { api } = setup();
  let ip = 0;
  for (const bad of ["a", "has space", "UPPER!", "x".repeat(33), "dots..dots", "admin", "minefeed_bot", "кириллица"]) {
    const r = await api("POST", "/v2/auth/register", { body: { tag: bad, nick: "n" }, ip: "10.0.0." + ip++ });
    assert.equal(r.status, 400, bad);
  }
  assert.equal((await api("POST", "/v2/auth/register", { body: { tag: "kot.boss", nick: "Котик" } })).status, 201);
  const dup = await api("POST", "/v2/auth/register", { body: { tag: "KOT.BOSS", nick: "Другой" }, ip: "2.2.2.2" });
  assert.equal(dup.status, 409);
  const chk = await api("GET", "/v2/tags/check?tag=Kot.Boss");
  assert.equal(chk.data.available, false);
  assert.equal((await api("GET", "/v2/tags/check?tag=free_tag")).data.available, true);
});

test("nick: duplicates allowed, formatting stripped, length limits", async () => {
  const { api } = setup();
  const a = await api("POST", "/v2/auth/register", { body: { tag: "steve_mc", nick: "Стив" } });
  const b = await api("POST", "/v2/auth/register", { body: { tag: "steve2007", nick: "Стив" } });
  assert.equal(a.status, 201); assert.equal(b.status, 201);
  const c = await api("PATCH", "/v2/me", { key: a.data.key, body: { nick: "§cКрасный\u200b Ник\n" } });
  assert.equal(c.data.account.nick, "cКрасный Ник");
  assert.equal((await api("PATCH", "/v2/me", { key: a.data.key, body: { nick: "   " } })).status, 400);
  assert.equal((await api("PATCH", "/v2/me", { key: a.data.key, body: { nick: "x".repeat(33) } })).status, 400);
});

test("tag change: cooldown and hold of the old tag", async () => {
  const { api } = setup();
  const a = await api("POST", "/v2/auth/register", { body: { tag: "aboba", nick: "A" } });
  const b = await api("POST", "/v2/auth/register", { body: { tag: "bob", nick: "B" } });
  const ch = await api("POST", "/v2/me/tag", { key: a.data.key, body: { tag: "aboba2" } });
  assert.equal(ch.status, 200); assert.equal(ch.data.account.tag, "aboba2");
  assert.ok(ch.data.account.canChangeTagAt > clock);
  // someone else cannot grab the old tag during the hold
  assert.equal((await api("POST", "/v2/me/tag", { key: b.data.key, body: { tag: "aboba" } })).status, 409);
  assert.equal((await api("GET", "/v2/tags/check?tag=aboba")).data.available, false);
  // owner is on cooldown
  const again = await api("POST", "/v2/me/tag", { key: a.data.key, body: { tag: "aboba3" }, now: clock + 13 * DAY });
  assert.equal(again.data.code, "tag_cooldown");
  // after the hold, the old tag is free for others
  assert.equal((await api("POST", "/v2/me/tag", { key: b.data.key, body: { tag: "aboba" }, now: clock + 8 * DAY })).status, 200);
  // after cooldown owner can change again
  assert.equal((await api("POST", "/v2/me/tag", { key: a.data.key, body: { tag: "aboba3" }, now: clock + 15 * DAY })).status, 200);
});

test("owner may take back own held tag within cooldown? no — cooldown applies", async () => {
  const { api } = setup();
  const a = await api("POST", "/v2/auth/register", { body: { tag: "first", nick: "A" } });
  await api("POST", "/v2/me/tag", { key: a.data.key, body: { tag: "second" } });
  const back = await api("POST", "/v2/me/tag", { key: a.data.key, body: { tag: "first" }, now: clock + 15 * DAY });
  assert.equal(back.status, 200);
  assert.equal(back.data.account.tag, "first");
});

test("recovery rotates key and code", async () => {
  const { api } = setup();
  const a = await api("POST", "/v2/auth/register", { body: { tag: "lost", nick: "L" } });
  const rec = await api("POST", "/v2/auth/recover", { body: { recoveryCode: a.data.recoveryCode.toLowerCase().replace(/-/g, " ") } });
  assert.equal(rec.status, 200);
  assert.equal(rec.data.account.id, a.data.account.id);
  assert.notEqual(rec.data.key, a.data.key);
  assert.equal((await api("GET", "/v2/me", { key: a.data.key })).status, 401, "old key revoked");
  assert.equal((await api("GET", "/v2/me", { key: rec.data.key })).status, 200);
  assert.equal((await api("POST", "/v2/auth/recover", { body: { recoveryCode: a.data.recoveryCode } })).status, 404, "old code is one-time");
  const nr = await api("POST", "/v2/me/recovery", { key: rec.data.key });
  assert.equal((await api("POST", "/v2/auth/recover", { body: { recoveryCode: rec.data.recoveryCode } })).status, 404);
  assert.equal((await api("POST", "/v2/auth/recover", { body: { recoveryCode: nr.data.recoveryCode } })).status, 200);
});

test("rate limits: register per IP and window reset", async () => {
  const { api } = setup();
  for (let i = 0; i < 5; i++) assert.equal((await api("POST", "/v2/auth/register", { body: { tag: "u" + i + "x", nick: "n" }, ip: "9.9.9.9" })).status, 201);
  const sixth = await api("POST", "/v2/auth/register", { body: { tag: "u6x", nick: "n" }, ip: "9.9.9.9" });
  assert.equal(sixth.status, 429); assert.ok(sixth.data.retryAfter > 0);
  assert.equal((await api("POST", "/v2/auth/register", { body: { tag: "u7x", nick: "n" }, ip: "8.8.8.8" })).status, 201);
  assert.equal((await api("POST", "/v2/auth/register", { body: { tag: "u8x", nick: "n" }, ip: "9.9.9.9", now: clock + 3_600_000 })).status, 201);
});

test("logout revokes only this key; banned accounts blocked", async () => {
  const { api, env } = setup();
  const a = await api("POST", "/v2/auth/register", { body: { tag: "bye", nick: "B" } });
  assert.equal((await api("POST", "/v2/auth/logout", { key: a.data.key })).status, 200);
  assert.equal((await api("GET", "/v2/me", { key: a.data.key })).status, 401);
  const b = await api("POST", "/v2/auth/register", { body: { tag: "bad", nick: "B" } });
  env.DB.raw.prepare("UPDATE accounts SET banned = 1").run();
  assert.equal((await api("GET", "/v2/me", { key: b.data.key })).status, 403);
  assert.equal((await api("GET", "/v2/users/bad")).status, 404);
});

test("meta, 404, bad json, worker wrapper", async () => {
  const { api, env } = setup();
  const m = await api("GET", "/v2/meta");
  assert.equal(m.data.api, 2); assert.equal(m.data.minClient, "0.2.0");
  assert.equal((await api("GET", "/v2/nope")).status, 404);
  const req = new Request("https://x.test/v2/auth/register", { method: "POST", body: "{not json", headers: { "cf-connecting-ip": "3.3.3.3" } });
  const res = await worker.fetch(req, env);
  assert.equal(res.status, 400);
  assert.equal((await res.json()).code, "bad_json");
});
