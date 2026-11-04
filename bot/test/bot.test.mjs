import { test } from "node:test";
import assert from "node:assert/strict";
import { Relay } from "../relay.js";
import { makeFilter } from "../filter.js";

const chans = {
  cat: { type: 4 }, forum: { type: 15, parentId: "cat" }, other: { type: 15, parentId: "x" },
  cat2: { type: 4, name: "MineFeed communities 2" }, forum2: { type: 15, parentId: "cat2" }, th3: { type: 11, parentId: "forum2" },
  th: { type: 11, parentId: "forum", name: "Hello", appliedTags: ["t1"] }, th2: { type: 11, parentId: "other" }, text: { type: 0, parentId: "cat" },
};
const filter = makeFilter({ guildId: "g", categories: ["cat"], channels: (id) => chans[id] });

test("filter keeps feed-forum events only", () => {
  const msg = (channel_id, id = "m1") => ({ guild_id: "g", id, channel_id, content: "hi", author: { id: "u", username: "u" } });
  assert.ok(filter("MESSAGE_CREATE", msg("th")));
  assert.equal(filter("MESSAGE_CREATE", msg("th2")), null);
  assert.ok(filter("MESSAGE_CREATE", msg("th3")));
  assert.equal(filter("MESSAGE_CREATE", msg("text")), null);
  assert.equal(filter("MESSAGE_CREATE", { ...msg("th"), guild_id: "other" }), null);
  assert.ok(filter("MESSAGE_CREATE", msg("unknown-archived")));
  const starter = filter("MESSAGE_CREATE", msg("th", "th"));
  assert.deepEqual([starter.parent_id, starter.thread_name, starter.applied_tags], ["forum", "Hello", ["t1"]]);
  assert.ok(filter("THREAD_DELETE", { guild_id: "g", id: "th", parent_id: "forum" }));
  assert.equal(filter("THREAD_DELETE", { guild_id: "g", id: "th2", parent_id: "other" }), null);
  assert.equal(filter("TYPING_START", { guild_id: "g", channel_id: "th" }), null);
});

test("relay batches, retries and keeps order", async () => {
  const sent = [];
  let fail = 1;
  const relay = new Relay({ url: "https://w.test/", secret: "s", flushMs: 1, log: { warn() {}, error() {} },
    fetchImpl: async (url, init) => {
      assert.equal(url, "https://w.test/v2/bot/events");
      assert.equal(init.headers.authorization, "Bearer s");
      if (fail-- > 0) throw new Error("down");
      sent.push(JSON.parse(init.body).events);
      return new Response("{}", { status: 200 });
    } });
  for (let i = 0; i < 150; i++) relay.queue.push({ t: "X", d: { i } });
  assert.equal(await relay.flush(), false);
  clearTimeout(relay.timer); relay.timer = null;
  assert.equal(await relay.flush(), true);
  clearTimeout(relay.timer); relay.timer = null;
  assert.equal(await relay.flush(), true);
  assert.deepEqual(sent.map((b) => b.length), [100, 50]);
  assert.equal(sent[1][49].d.i, 149);
});
