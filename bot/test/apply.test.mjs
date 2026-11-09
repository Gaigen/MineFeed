import { test } from "node:test";
import assert from "node:assert/strict";
import { makeApplications, applicationModal, isStaff, COMMAND } from "../apply.js";

function fake(kind, over = {}) {
  const log = [];
  const i = {
    user: { id: "u1" }, locale: "ru", customId: null, deferred: false, replied: false,
    isChatInputCommand: () => kind === "cmd", isModalSubmit: () => kind === "modal", commandName: "community",
    reply: async (m) => { i.replied = true; log.push(["reply", m]); },
    showModal: async (m) => log.push(["modal", m]),
    deferUpdate: async () => { i.deferred = true; log.push(["defer"]); },
    followUp: async (m) => log.push(["followUp", m]),
    fields: { getTextInputValue: (k) => ({ name: "Шахта", description: "копаем", about: "", links: "", reason: "мало инфы" })[k] },
    ...over,
  };
  return { i, log };
}

function worker(answers) {
  const calls = [];
  return { calls, call: async (path, body) => { calls.push({ path, body }); return answers[path] || { status: 200, data: { ok: true } }; } };
}

test("command: not linked -> ephemeral hint, ok -> modal", async () => {
  let w = worker({ "/v2/bot/apply/check": { status: 200, data: { ok: false, code: "not_linked" } } });
  let f = fake("cmd");
  await makeApplications({ call: w.call })(f.i);
  assert.equal(f.log[0][0], "reply");
  assert.equal(f.log[0][1].flags, 64);
  assert.match(f.log[0][1].content, /Профиль → Discord/);

  w = worker({});
  f = fake("cmd", { locale: "en-US" });
  await makeApplications({ call: w.call })(f.i);
  assert.equal(f.log[0][0], "modal");
  assert.equal(f.log[0][1].title, "MineFeed community");
  assert.equal(f.log[0][1].components.length, 4);
  assert.equal(COMMAND.name_localizations.ru, "сообщество");
});

test("form submit goes to the Worker with the locale", async () => {
  const w = worker({ "/v2/bot/apply": { status: 201, data: { application: { name: "Шахта" } } } });
  const f = fake("modal", { customId: "mfapp:form" });
  await makeApplications({ call: w.call })(f.i);
  assert.deepEqual(w.calls[0].body, { discordId: "u1", name: "Шахта", description: "копаем", about: "", links: "", locale: "ru" });
  assert.match(f.log[0][1].content, /отправлена/);

  const w2 = worker({ "/v2/bot/apply": { status: 400, data: { code: "bad_name" } } });
  const f2 = fake("modal", { customId: "mfapp:form" });
  await makeApplications({ call: w2.call })(f2.i);
  assert.match(f2.log[0][1].content, /от 3 до 32/);
  assert.equal(applicationModal("ru").components[0].components[0].label, "Название");
});

test("buttons: only staff; approve and reject with a reason", async () => {
  const notStaff = fake("button", { customId: "mfapp:approve:abcdef12", member: { roles: ["x"] } });
  let w = worker({});
  await makeApplications({ call: w.call, modRoleId: "mod" })(notStaff.i);
  assert.equal(w.calls.length, 0);
  assert.equal(notStaff.log[0][1].flags, 64);

  const mod = { roles: { cache: new Map([["mod", {}]]) } };
  assert.ok(isStaff({ member: mod }, "mod"));
  assert.ok(isStaff({ memberPermissions: { has: (b) => b === 8n }, member: { roles: [] } }, "mod"));

  w = worker({ "/v2/bot/apply/abcdef12/decide": { status: 200, data: { ok: true, status: "approved", community: { name: "Шахта" } } } });
  const ap = fake("button", { customId: "mfapp:approve:abcdef12", member: mod });
  await makeApplications({ call: w.call, modRoleId: "mod" })(ap.i);
  assert.deepEqual(w.calls[0].body, { approve: true, staffId: "u1", reason: null });
  assert.equal(ap.log[0][0], "defer");

  const rj = fake("button", { customId: "mfapp:reject:abcdef12", member: mod });
  await makeApplications({ call: w.call, modRoleId: "mod" })(rj.i);
  assert.equal(rj.log[0][1].custom_id, "mfapp:reason:abcdef12");

  w = worker({ "/v2/bot/apply/abcdef12/decide": { status: 200, data: { ok: false, code: "already_decided", status: "approved" } } });
  const rs = fake("modal", { customId: "mfapp:reason:abcdef12", member: mod });
  await makeApplications({ call: w.call, modRoleId: "mod" })(rs.i);
  assert.deepEqual(w.calls[0].body, { approve: false, staffId: "u1", reason: "мало инфы" });
  assert.match(rs.log[1][1].content, /Already approved/);
});
