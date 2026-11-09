import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const src = await readFile(new URL('../legacy-notice.js', import.meta.url), 'utf8');
const { default: worker } = await import(`data:text/javascript;base64,${Buffer.from(src).toString('base64')}`);
const env = { APP_TOKEN: 'secret', V1_UPDATE_NOTICE: 'V1 retired. V2 coming.', V1_UPDATE_PUBLISHED_AT: '2026-11-09T00:00:00Z' };
function req(path, opts = {}) { return new Request('https://example.test' + path, { ...opts, headers: { 'X-Feed-Token': 'secret', ...opts.headers } }); }
const run = (path, opts) => worker.fetch(req(path, opts), env);
test('notice is the only feed item, with stable ETag and no dependencies', async () => {
  for (const path of ['/v1/feed', '/v1/feed?uuid=test&mine=1', '/v1/feed?tag=test']) {
    const r = await run(path), e = r.headers.get('etag'), body = await r.json();
    assert.equal(body.posts.length, 1); assert.equal(body.posts[0].text, env.V1_UPDATE_NOTICE);
    assert.equal(body.posts[0].id, '0'); assert.equal(body.nextCursor, null);
    assert.equal((await run(path, { headers: { 'If-None-Match': e } })).status, 304);
  }
  assert.deepEqual((await (await run('/v1/feed?cursor=123')).json()).posts, []);
});
test('notice details and empty comments are compatible', async () => {
  assert.equal((await (await run('/v1/posts/0')).json()).post.id, '0');
  const r = await run('/v1/posts/0/comments');
  assert.deepEqual(await r.json(), { comments: [], nextBefore: null });
  assert.equal((await run('/v1/posts/0/comments', { headers: { 'If-None-Match': r.headers.get('etag') } })).status, 304);
});
test('old background polls are cheap, and writes show a clear retirement message', async () => {
  assert.deepEqual(await (await run('/v1/notifications?uuid=123&after=55')).json(), { notifications: [], latest: '55' });
  assert.deepEqual(await (await run('/v1/tags')).json(), { tags: [] });
  const r = await run('/v1/posts', { method: 'POST' }); assert.equal(r.status, 410); assert.equal((await r.json()).error, env.V1_UPDATE_NOTICE);
});
test('original authentication remains required', async () => {
  assert.equal((await worker.fetch(new Request('https://example.test/v1/feed'), env)).status, 401);
  assert.equal((await worker.fetch(new Request('https://example.test/v1/health'), env)).status, 200);
});
