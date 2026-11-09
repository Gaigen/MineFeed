import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Load the Worker as an ES module without changing the repository's package type.
const source = await readFile(new URL('../worker.js', import.meta.url), 'utf8');
const { default: worker } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const discordFetch = globalThis.fetch;
globalThis.fetch = async (url) => {
  if (String(url).includes('/guilds/test-guild/threads/active')) {
    return Response.json({ threads: [] });
  }
  throw new Error(`Unexpected network request: ${url}`);
};
const db = {
  prepare(sql) { return { sql, bind() { return this; }, async all() { return { results: [] }; }, async first() { return null; } }; },
  async batch(statements) { return statements.map(() => ({ results: [] })); },
};
const base = { APP_TOKEN: 'test-token', DB: db, GUILD_ID: 'test-guild', FORUM_CHANNEL_ID: 'forum' };
const active = { ...base, V1_UPDATE_NOTICE: 'MineFeed V2 is out! Update at example.org', V1_UPDATE_PUBLISHED_AT: '2026-11-09T12:00:00Z' };
const ctx = { waitUntil() {} };
function request(path, headers = {}) { return new Request('https://example.test' + path, { headers: { 'X-Feed-Token': 'test-token', ...headers } }); }
async function run(path, env = active, headers = {}) { return worker.fetch(request(path, headers), env, ctx); }

test('disabled by default, and requires both valid configuration values', async () => {
  for (const env of [base, { ...active, V1_UPDATE_NOTICE: '' }, { ...active, V1_UPDATE_PUBLISHED_AT: 'not-a-date' }]) {
    const response = await run('/v1/feed', env);
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json()).posts, []);
  }
});

test('normal first page contains one read-only notice, with stable ETag', async () => {
  const response = await run('/v1/feed?limit=20&uuid=123');
  const etag = response.headers.get('etag');
  const body = await response.json();
  assert.equal(body.posts.length, 1);
  assert.equal(body.posts[0].id, '0');
  assert.equal(body.posts[0].text, active.V1_UPDATE_NOTICE);
  assert.equal(body.posts[0].solved, true);
  assert.equal(body.nextCursor, null);
  assert.equal((await run('/v1/feed?limit=20&uuid=123', active, { 'If-None-Match': etag })).status, 304);
});

test('announcement is not injected into filters or older pages', async () => {
  for (const path of ['/v1/feed?tag=foo', '/v1/feed?mine=1&uuid=123', '/v1/feed?cursor=99']) {
    assert.deepEqual((await (await run(path)).json()).posts, []);
  }
});

test('opening the notice and its empty comments works', async () => {
  assert.equal((await (await run('/v1/posts/0')).json()).post.text, active.V1_UPDATE_NOTICE);
  const comments = await run('/v1/posts/0/comments');
  assert.deepEqual(await comments.json(), { comments: [], nextBefore: null });
  assert.equal((await run('/v1/posts/0/comments', active, { 'If-None-Match': comments.headers.get('etag') })).status, 304);
});

test('normal authentication still applies', async () => {
  const response = await worker.fetch(new Request('https://example.test/v1/feed'), active, ctx);
  assert.equal(response.status, 401);
});

process.on('exit', () => { globalThis.fetch = discordFetch; });

test('existing posts and pagination are preserved with announcement enabled', async () => {
  const { default: freshWorker } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#existing-posts`);
  const threads = Array.from({ length: 21 }, (_, i) => ({ id: String(1000 - i), parent_id: 'forum', message_count: 1 }));
  const rows = threads.map(t => ({ id: t.id, type: 'talk', author: 'Player', uuid: 'abc', text: `Post ${t.id}`, tags: '[]', solved: 0, created_at: '2026-01-01T00:00:00Z', comment_count: 0 }));
  const populatedDb = {
    prepare(sql) { return { sql, args: [], bind(...args) { this.args = args; return this; }, async all() { return { results: [] }; } }; },
    async batch(statements) { return statements.map(stmt => ({ results: stmt.sql.startsWith('SELECT * FROM posts WHERE id IN') ? rows.filter(r => stmt.args.includes(r.id)) : [] })); },
  };
  const oldFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({ threads });
  try {
    const env = { ...active, DB: populatedDb, DISCORD_BOT_TOKEN: 'test-bot-token' };
    const without = await (await freshWorker.fetch(request('/v1/feed?limit=20'), { ...env, V1_UPDATE_NOTICE: '' }, ctx)).json();
    const withNotice = await (await freshWorker.fetch(request('/v1/feed?limit=20'), env, ctx)).json();
    assert.equal(without.posts.length, 20);
    assert.equal(withNotice.posts.length, 21);
    assert.equal(withNotice.posts[0].id, '0');
    assert.deepEqual(withNotice.posts.slice(1), without.posts);
    assert.equal(withNotice.nextCursor, without.nextCursor);
    assert.equal(withNotice.nextCursor, '981');
  } finally { globalThis.fetch = oldFetch; }
});
