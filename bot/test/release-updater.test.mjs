import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PROJECTS } from '../releases.js';
import { makeUpdatingAnnouncer, parseMcField } from '../release-updater.js';
import { fileReleaseState } from '../release-state.js';

const project = { ...PROJECTS.find(p => p.key === 'retroconsole'), modrinth: null, curseforge: null };
const gh = (mc, when, version = '0.3.4', loader = 'fabric') => ({ tag_name: `v${version}-mc${mc}`, html_url: `https://github.com/Gaigen/retroconsole/releases/tag/v${version}-mc${mc}`, published_at: when, assets: [{ name: `retroconsole-${version}+${mc}-${loader}.jar` }] });
const memory = () => { let data = { version: 1, targets: {} }; return { load: async () => structuredClone(data), save: async d => { data = structuredClone(d); }, snapshot: () => structuredClone(data) }; };
const channel = (old = []) => {
  let count = 1; const messages = old.map(x => ({ ...x }));
  const sent = [], edits = []; let failure;
  return { sent, edits, messages, setFailure: e => { failure = e; },
    recentMessages: async () => messages.slice(-100).reverse(),
    send: async m => { const id = `new${count++}`; sent.push(m); messages.push({ id, footer: m.embeds[0].footer.text, minecraft: m.embeds[0].fields.find(f => f.name === 'Minecraft')?.value, loaders: m.embeds[0].fields.find(f => f.name === 'Loaders')?.value }); return { id }; },
    edit: async (id, msg) => { if (failure) throw failure; if (!messages.some(m => m.id === id)) { const e = new Error('Unknown Message'); e.code = 10008; throw e; } edits.push({ id, msg }); const m = messages.find(m => m.id === id); m.minecraft = msg.embeds[0].fields.find(f => f.name === 'Minecraft')?.value; m.loaders = msg.embeds[0].fields.find(f => f.name === 'Loaders')?.value; },
  };
};
function setup({ releases, c, store, now, since = Date.parse('2026-11-01T00:00:00Z'), history = false }) {
  return makeUpdatingAnnouncer({ projects: [project], roles: { retroconsole: '123' }, targets: [{ id: 'channel', name: 'announcements', channel: c, since, ping: true, history }], stateStore: store, now: () => now.value, log: { log() {}, warn() {} },
    fetchJson: async url => url.includes('/compare/') ? { status: 200, data: { commits: [] } } : { status: 200, data: releases.value } });
}
const time = date => ({ value: Date.parse(date) });
const old = () => ({ id: 'old1', footer: 'RetroConsole 0.3.4', minecraft: '1.21.1', loaders: 'Fabric · NeoForge', createdTimestamp: Date.parse('2026-11-08T23:40:00Z') });

test('adopts existing post, fixes missed second MC, and survives restart without spam', async () => {
  const now = time('2026-11-09T01:00:00Z'); const releases = { value: [gh('1.21.1','2026-11-08T23:32:00Z'), gh('26.1.2','2026-11-09T00:03:00Z')] };
  const c = channel([old()]), store = memory();
  await setup({ releases, c, store, now })();
  assert.equal(c.edits.length, 1); assert.equal(c.sent.length, 0);
  assert.equal(c.edits[0].msg.embeds[0].fields.find(f => f.name === 'Minecraft').value, '1.21.1 · 26.1.2');
  assert.equal(c.edits[0].msg.components[0].components.find(b => b.label === 'GitHub').url, 'https://github.com/Gaigen/retroconsole/releases');
  await setup({ releases, c, store, now })(); assert.equal(c.edits.length, 1); assert.equal(c.sent.length, 0);
});

test('late build for an old version updates, then deleted post causes one replacement', async () => {
  const now = time('2026-11-09T01:00:00Z'); const releases = { value: [gh('1.21.1','2026-11-08T23:32:00Z')] };
  const c = channel([old()]), store = memory(); const tick = setup({ releases, c, store, now });
  await tick(); assert.equal(c.edits.length, 0);
  releases.value.push(gh('1.7.10','2026-12-09T12:00:00Z')); now.value = Date.parse('2026-12-09T13:00:00Z');
  await tick(); assert.equal(c.edits.length, 1); assert.equal(c.sent.length, 0);
  c.messages.splice(0); releases.value.push(gh('1.20.1','2027-01-09T12:00:00Z')); now.value = Date.parse('2027-01-09T13:00:00Z');
  await tick(); await tick(); assert.equal(c.sent.length, 1); assert.match(c.sent[0].embeds[0].fields[0].value, /1\.20\.1/);
});

test('old unseen versions are baseline, only a genuinely new build makes a post', async () => {
  const now = time('2026-11-09T01:00:00Z'), releases = { value: [gh('1.21.1','2026-10-01T00:00:00Z')] };
  const c = channel(), store = memory(), tick = setup({ releases, c, store, now });
  await tick(); assert.equal(c.sent.length, 0);
  releases.value.push(gh('1.7.10', '2026-12-01T00:00:00Z')); now.value = Date.parse('2026-12-01T01:00:00Z');
  await tick(); await tick(); assert.equal(c.sent.length, 1);
});

test('transient edit errors do not create duplicate posts, and are retried', async () => {
  const now = time('2026-11-09T01:00:00Z'); const releases = { value: [gh('1.21.1','2026-11-08T23:32:00Z'),gh('26.1.2','2026-11-09T00:03:00Z')] };
  const c = channel([old()]), store = memory(), tick = setup({ releases, c, store, now });
  c.setFailure(Object.assign(new Error('timeout'), { code: 'ETIMEDOUT' }));
  await assert.rejects(tick(), /timeout/); assert.equal(c.sent.length, 0);
  c.setFailure(null); await tick(); assert.equal(c.edits.length, 1); assert.equal(c.sent.length, 0);
});

test('recent release settles, and source outages never remove already recorded builds', async () => {
  const now = time('2026-11-09T01:00:00Z'); const releases = { value: [gh('1.21.1','2026-11-08T23:32:00Z')] };
  const c = channel([old()]), store = memory(), tick = setup({ releases, c, store, now });
  await tick(); releases.value.push(gh('26.1.2','2026-11-09T00:58:00Z'));
  await tick(); assert.equal(c.edits.length, 0);
  now.value += 5 * 60_000; await tick(); assert.equal(c.edits.length, 1);
  releases.value = [gh('26.1.2','2026-11-09T00:58:00Z')]; await tick();
  assert.equal(c.edits.length, 1); assert.equal(store.snapshot().targets.channel.projects.retroconsole.versions['0.3.4'].entries.length, 2);
});

test('Minecraft ranges are interpreted for legacy post migration', () => {
  assert.deepEqual(parseMcField('1.21.1 – 1.21.3 · 26.1.2'), ['1.21.1','1.21.2','1.21.3','26.1.2']);
});

test('file state survives restart and rejects corrupt files', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'releases-')); const path = join(dir, 'state.json');
  try { const store = fileReleaseState(path); const data = await store.load(); data.targets.a = { projects: {} }; await store.save(data);
    assert.deepEqual(await store.load(), data); await writeFile(path, '{oops'); await assert.rejects(store.load()); assert.equal(await readFile(path,'utf8'), '{oops');
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('bootstrapping waits for source outages rather than assuming an empty history', async () => {
  const now = time('2026-11-09T01:00:00Z'); const c = channel([old()]), store = memory();
  let fail = true;
  const tick = makeUpdatingAnnouncer({ projects: [project], targets: [{ id: 'c', name: 'c', channel: c, since: 0 }], stateStore: store, now: () => now.value, log: { log() {}, warn() {} },
    fetchJson: async () => fail ? { status: 500 } : { status: 200, data: [gh('1.21.1','2026-11-08T23:32:00Z'),gh('26.1.2','2026-11-09T00:03:00Z')] } });
  await tick(); assert.equal(c.sent.length, 0); assert.equal(c.edits.length, 0);
  fail = false; await tick(); assert.equal(c.edits.length, 1); assert.equal(c.sent.length, 0);
});

test('a failed state save after a replacement send is recovered without another ping', async () => {
  const now = time('2026-11-09T01:00:00Z'), releases = { value: [gh('1.21.1','2026-11-08T23:32:00Z')] };
  const c = channel([old()]), base = memory(); let fail = false;
  const store = { load: base.load, save: async d => { if (fail) { fail = false; throw new Error('disk'); } await base.save(d); } };
  const tick = setup({ releases, c, store, now }); await tick();
  c.messages.splice(0); releases.value.push(gh('26.1.2','2026-11-09T00:03:00Z')); fail = true;
  await assert.rejects(tick(), /disk/); assert.equal(c.sent.length, 1);
  await tick(); assert.equal(c.sent.length, 1); assert.equal(c.edits.length, 1);
});

test('history bootstrap does not repost older releases outside the last 100', async () => {
  const now = time('2026-11-09T01:00:00Z'); const releases = { value: [gh('1.21.1','2026-09-01T00:00:00Z','0.2.0'), gh('1.21.1','2026-11-08T23:32:00Z')] };
  const c = channel([old()]), store = memory();
  await setup({ releases, c, store, now, since: 0, history: true })();
  assert.equal(c.sent.length, 0);
});

test('editing preserves changelog and unrelated components, without another mention', async () => {
  const { releaseEditPayload } = await import('../release-message.js');
  const raw = { title: 'RetroConsole 0.3.4', description: 'Original changelog', timestamp: '2026-11-08T23:32:00Z', url: 'https://old', fields: [{ name: 'Extra', value: 'keep' }, { name: 'Minecraft', value: '1.21.1' }, { name: 'Loaders', value: 'Fabric' }] };
  const buttons = { type: 1, components: [{ type: 2, style: 5, label: 'GitHub', url: 'https://old' }, { type: 2, style: 5, label: 'Other', url: 'https://keep' }] };
  const message = { embeds: [{ toJSON: () => structuredClone(raw) }], components: [{ toJSON: () => structuredClone(buttons) }] };
  const desired = { embeds: [{ url: 'https://new', fields: [{ name: 'Minecraft', value: '1.21.1 · 26.1.2' }, { name: 'Loaders', value: 'Fabric · NeoForge' }] }], components: [{ components: [{ type: 2, style: 5, label: 'GitHub', url: 'https://new' }] }] };
  const update = releaseEditPayload(message, desired);
  assert.equal(update.embeds[0].description, raw.description); assert.equal(update.embeds[0].timestamp, raw.timestamp);
  assert.deepEqual(update.embeds[0].fields.map(f => f.value), ['keep', '1.21.1 · 26.1.2', 'Fabric · NeoForge']);
  assert.equal(update.components[0].components[0].url, 'https://new'); assert.equal(update.components[0].components[1].url, 'https://keep');
  assert.equal(update.content, undefined); assert.deepEqual(update.allowedMentions, { parse: [] });
});

test('persisted message id allows an update beyond the 100-message window', async () => {
  const now = time('2026-11-09T01:00:00Z'), releases = { value: [gh('1.21.1','2026-11-08T23:32:00Z')] };
  const c = channel([old()]), store = memory(), tick = setup({ releases, c, store, now }); await tick();
  for (let i = 0; i < 105; i++) c.messages.push({ id: `other${i}`, footer: 'Other', minecraft: '', loaders: '' });
  releases.value.push(gh('26.1.2','2026-11-09T00:03:00Z'));
  await tick(); assert.equal(c.sent.length, 0); assert.equal(c.edits[0].id, 'old1');
});

test('restart does not change a persistent default cutoff or miss a release while offline', async () => {
  const now = time('2026-11-09T01:00:00Z'), releases = { value: [] }, c = channel(), store = memory();
  const make = () => makeUpdatingAnnouncer({ projects: [project], targets: [{ id: 'c', name: 'c', channel: c, since: now.value, dynamicSince: true, ping: true }], stateStore: store, now: () => now.value, log: {log() {}, warn() {}}, fetchJson: async () => ({ status: 200, data: releases.value }) });
  await make()(); releases.value.push(gh('1.21.1','2026-11-10T00:00:00Z')); now.value = Date.parse('2026-11-11T00:00:00Z');
  await make()(); assert.equal(c.sent.length, 1);
});
