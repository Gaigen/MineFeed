import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { messageFor } from "../src/messages.js";
import { makeD1 } from "./d1shim.mjs";
import { readFileSync, readdirSync } from "node:fs";

const MIG = new URL("../migrations", import.meta.url).pathname;
const env = () => ({ DB: makeD1(MIG) });
test("API errors default to English and support Russian", async () => {
  for (const [lang, expected] of [[null, "Not found"], ["en-US", "Not found"], ["ru-RU", "Не найдено"]]) {
    const headers = lang ? { "accept-language": lang } : {};
    const res = await worker.fetch(new Request("https://example.test/missing", { headers }), env());
    assert.equal(res.status, 404);
    assert.equal((await res.json()).error, expected);
  }
});
test("tag check reason respects language without changing availability", async () => {
  for (const [lang, reason] of [["en-US", "This tag is reserved"], ["ru-RU", "Этот тег зарезервирован"]]) {
    const res = await worker.fetch(new Request("https://example.test/v2/tags/check?tag=admin", { headers: { "accept-language": lang } }), env());
    const data = await res.json();
    assert.equal(data.available, false);
    assert.equal(data.reason, reason);
  }
});
test("dynamic errors translate without losing limits", () => {
  assert.equal(messageFor("В опросе от 2 до 5 вариантов", "en-US"), "A poll must have 2–5 options");
  assert.equal(messageFor("Слишком длинный текст (максимум 123)", "en-US"), "Text is too long (maximum 123 characters)");
  assert.equal(messageFor("Вариант длиннее 80 символов", "en-US"), "A poll option exceeds 80 characters");
  assert.equal(messageFor("Не больше 10 кодов — удали ненужные", "en-US"), "Maximum 10 codes; delete unused codes");
  assert.equal(messageFor("В опросе от 2 до 5 вариантов", "ru-RU"), "В опросе от 2 до 5 вариантов");
});
test("all Russian string-literal API errors have English translations", () => {
  const dir = new URL("../src/", import.meta.url);
  for (const name of readdirSync(dir).filter(n => n.endsWith(".js") && n !== "messages.js")) {
    const source = readFileSync(new URL(name, dir), "utf8");
    for (const line of source.split("\n")) {
      if (!/HttpError|return \"Тег|return \"Этот тег|reason: \"Тег|error: \"Время|error: \"Профиль|finish\(env, state, \"error\"/.test(line)) continue;
      for (const m of line.matchAll(/"([^"\n]*[А-Яа-яЁё][^"\n]*)"/g)) {
        assert.notEqual(messageFor(m[1], "en"), "Something went wrong. Please try again.", `${name}: ${m[1]}`);
      }
    }
  }
});
test("English resource files contain no Cyrillic", () => {
  for (const path of ["../../src", "../../fabric-26.1.2/src", "../../neoforge-1.20.1/src", "../../neoforge-26.1.2/src"]) {
    const json = JSON.parse(readFileSync(new URL(`${path}/main/resources/assets/feed/lang/en_us.json`, import.meta.url), "utf8"));
    for (const [key, value] of Object.entries(json)) assert.doesNotMatch(value, /[А-Яа-яЁё]/, `${path}: ${key}`);
  }
});
