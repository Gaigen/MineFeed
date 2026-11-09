// V1 compatibility endpoint after retirement: no D1, Discord, cron or external calls.
// Keep the original APP_TOKEN secret so existing clients can still read the notice.
const FALLBACK = 'MineFeed V1 is no longer supported. MineFeed V2 is coming: github.com/Gaigen/MineFeed/releases';
const json = (obj, status = 200, headers = {}) => new Response(JSON.stringify(obj), {
  status, headers: { 'content-type': 'application/json; charset=utf-8', ...headers },
});
async function cachedJson(obj, request) {
  const body = JSON.stringify(obj);
  const hash = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(body));
  const etag = '"' + Array.from(new Uint8Array(hash), (b) => b.toString(16).padStart(2, '0')).join('') + '"';
  if (request.headers.get('If-None-Match') === etag) return new Response(null, { status: 304, headers: { etag } });
  return new Response(body, { headers: { 'content-type': 'application/json; charset=utf-8', etag } });
}
function notice(env) {
  const text = String(env.V1_UPDATE_NOTICE || FALLBACK).trim().slice(0, 500);
  const date = String(env.V1_UPDATE_PUBLISHED_AT || '2026-11-09T00:00:00Z');
  return { id: '0', type: 'talk', author: 'MineFeed', uuid: '', text, tags: [], solved: true,
    createdAt: date, commentCount: 0, reactions: { fire: 0, zzz: 0 }, myReaction: null };
}
export default {
  async fetch(request, env) {
    const u = new URL(request.url), p = u.pathname, method = request.method;
    if (p === '/v1/health') return json({ ok: true, legacy: true });
    if (!env.APP_TOKEN || request.headers.get('X-Feed-Token') !== env.APP_TOKEN) return json({ error: 'unauthorized' }, 401);
    if (method === 'GET' && p === '/v1/feed') return cachedJson({ posts: u.searchParams.get('cursor') ? [] : [notice(env)], nextCursor: null }, request);
    if (method === 'GET' && p === '/v1/posts/0') return json({ post: notice(env) });
    if (method === 'GET' && p === '/v1/posts/0/comments') return cachedJson({ comments: [], nextBefore: null }, request);
    if (method === 'GET' && p === '/v1/notifications') return json({ notifications: [], latest: u.searchParams.get('after') || null });
    if (method === 'GET' && p === '/v1/tags') return json({ tags: [] });
    if (method === 'GET') return json({ error: 'not_found' }, 404);
    return json({ error: String(env.V1_UPDATE_NOTICE || FALLBACK).trim().slice(0, 500), code: 'v1_retired' }, 410);
  },
};
