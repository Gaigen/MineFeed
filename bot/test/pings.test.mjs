import { test } from "node:test";
import assert from "node:assert/strict";
import { parseRoles, makePings } from "../pings.js";

test("role list from env", () => {
  assert.deepEqual(parseRoles("minefeed:123456,retroconsole:654321, bad, x:abc"), { minefeed: "123456", retroconsole: "654321" });
  assert.deepEqual(parseRoles(""), {});
});

function fakeButton(customId, has, locale = "en-US") {
  const log = [];
  const roles = new Set(has ? ["111111"] : []);
  const i = {
    customId, locale, isButton: () => true,
    member: { roles: { cache: roles, add: async (r) => { roles.add(r); log.push(["add", r]); }, remove: async (r) => { roles.delete(r); log.push(["remove", r]); } } },
    reply: async (m) => { log.push(["reply", m]); },
  };
  return { i, log, roles };
}

test("ping button toggles the role and answers privately", async () => {
  const handle = makePings({ roles: { retroconsole: "111111" } });
  const on = fakeButton("ping:retroconsole", false);
  assert.equal(await handle(on.i), true);
  assert.ok(on.roles.has("111111"));
  assert.equal(on.log.at(-1)[1].flags, 64);
  assert.match(on.log.at(-1)[1].content, /🔔/);
  const off = fakeButton("ping:retroconsole", true, "ru");
  await handle(off.i);
  assert.ok(!off.roles.has("111111"));
  assert.match(off.log.at(-1)[1].content, /отключены/);
});

test("other interactions are left to the next handler", async () => {
  const handle = makePings({ roles: {} });
  assert.equal(await handle({ isButton: () => true, customId: "mfapp:approve:abc123" }), false);
  assert.equal(await handle({ isButton: () => false }), false);
  const broken = fakeButton("ping:nope", false);
  assert.equal(await handle(broken.i), true);
  assert.match(broken.log.at(-1)[1].content, /not set up/);
});
