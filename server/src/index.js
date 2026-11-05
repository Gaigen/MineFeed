// MineFeed API v2. Bindings: DB (D1). Vars: MIN_CLIENT, LATEST_CLIENT, DOWNLOAD_URL, DISCORD_CLIENT_ID.
// Secrets: DISCORD_CLIENT_SECRET, DISCORD_BOT_TOKEN.
import { HttpError, json, errorResponse, readJson } from "./util.js";
import * as A from "./accounts.js";
import * as D from "./discord.js";
import * as F from "./feed.js";
import * as U from "./users.js";
import * as N from "./notify.js";
import * as B from "./bot.js";
import * as C from "./communities.js";
import * as AP from "./applications.js";

const API = 2;

export default {
  async fetch(request, env) {
    try {
      return await route(request, env, Date.now());
    } catch (e) {
      if (e instanceof HttpError) return errorResponse(e);
      console.error(e);
      return json({ error: "Внутренняя ошибка сервера", code: "internal" }, 500);
    }
  },
  async scheduled(_event, env) {
    await N.prune(env, Date.now());
  },
};

export async function route(request, env, now) {
  const url = new URL(request.url);
  const p = url.pathname.replace(/\/+$/, "") || "/";
  const m = request.method;

  if (p === "/v2/meta" && m === "GET") {
    return json({
      api: API,
      minClient: env.MIN_CLIENT || "0.2.0",
      latestClient: env.LATEST_CLIENT || env.MIN_CLIENT || "0.2.0",
      downloadUrl: env.DOWNLOAD_URL || null,
      discordUrl: env.DISCORD_INVITE || null,
    }, 200, { "cache-control": "public, max-age=300" });
  }
  if (p === "/v2/tags/check" && m === "GET") {
    return json(await A.checkTag(env, url.searchParams.get("tag"), now, await optionalAccount(request, env, now)));
  }
  if (p === "/v2/discord/start" && m === "POST") {
    return json(await D.start(request, env, await readJson(request), await optionalAccount(request, env, now), now));
  }
  const goMatch = /^\/v2\/discord\/go\/([A-Za-z0-9_-]{16,64})$/.exec(p);
  if (goMatch && m === "GET") return D.go(request, env, goMatch[1], now);
  if (p === "/v2/discord/callback" && m === "GET") return D.callback(request, env, now);
  if (p === "/v2/discord/poll" && m === "POST") return json(await D.poll(env, await readJson(request), now));
  if (p === "/v2/auth/register" && m === "POST") return json(await A.register(request, env, await readJson(request), now), 201);
  if (p === "/v2/auth/recover" && m === "POST") return json(await A.recover(request, env, await readJson(request), now));

  const userMatch = /^\/v2\/users\/@?([^/]+)$/.exec(p);
  if (userMatch && m === "GET") return json(await U.profile(env, decodeURIComponent(userMatch[1])));

  if (p === "/v2/bot/events" && m === "POST") {
    await B.checkBot(request, env);
    return json(await B.events(env, await readJson(request, 256 * 1024), now));
  }
  if (p.startsWith("/v2/bot/apply") && m === "POST") {
    await B.checkBot(request, env);
    if (p === "/v2/bot/apply/check") return json(await AP.botCheck(env, await readJson(request)));
    if (p === "/v2/bot/apply") return json(await AP.botSubmit(env, await readJson(request, 8192), now), 201);
    const am = /^\/v2\/bot\/apply\/([a-z0-9]{6,16})\/decide$/.exec(p);
    if (am) return json(await AP.decide(env, am[1], await readJson(request), now));
  }

  // everything below needs an account
  if (p.startsWith("/v2/feed") || p.startsWith("/v2/sync") || p.startsWith("/v2/posts") || p.startsWith("/v2/comments/")
      || p.startsWith("/v2/notifications") || p === "/v2/reports" || p.startsWith("/v2/communities") || p === "/v2/join"
      || p === "/v2/users") {
    const account = await A.authenticate(request, env, now);
    const q = url.searchParams;
    if (p === "/v2/feed" && m === "GET") return json(await F.feed(env, account, q, now));
    if (p === "/v2/users" && m === "GET") return json(await U.search(env, q));
    if (p === "/v2/sync" && m === "GET") return json(await F.sync(env, account, q, now));
    if (p === "/v2/posts" && m === "POST") return json(await F.createPost(env, account, await readJson(request, 2_100_000), now), 201);
    if (p === "/v2/reports" && m === "POST") return json(await F.report(env, account, await readJson(request), now));
    if (p === "/v2/notifications" && m === "GET") return json(await N.list(env, account, q));
    if (p === "/v2/notifications/seen" && m === "POST") return json(await N.seen(env, account, await readJson(request), now));
    if (p === "/v2/join" && m === "POST") return json(await C.join(request, env, account, await readJson(request), now));
    if (p === "/v2/communities/applications" && m === "POST") {
      const body = await readJson(request, 8192);
      return json(await AP.submit(env, account, body, body.lang || request.headers.get("accept-language"), now), 201);
    }
    if (p === "/v2/communities" && m === "GET") return json(await C.mine(env, account));
    if (p === "/v2/communities" && m === "POST") return json(await C.create(env, account, await readJson(request), now), 201);
    const cc = /^\/v2\/communities\/([a-z0-9]{6,16})(?:\/(codes|requests|members|leave)(?:\/([^/]{1,64}))?)?$/.exec(p);
    if (cc) {
      const [, cid, sub, arg] = cc;
      const key = arg ? decodeURIComponent(arg) : null;
      if (!sub && m === "GET") return json(await C.get(env, account, cid));
      if (!sub && m === "PATCH") return json(await C.update(env, account, cid, await readJson(request)));
      if (!sub && m === "DELETE") return json(await C.remove(env, account, cid, now));
      if (sub === "leave" && !key && m === "POST") return json(await C.leave(env, account, cid));
      if (sub === "codes" && !key && m === "GET") return json(await C.listCodes(env, account, cid, now));
      if (sub === "codes" && !key && m === "POST") return json(await C.createCode(env, account, cid, await readJson(request), now), 201);
      if (sub === "codes" && key && m === "PATCH") return json(await C.updateCode(env, account, cid, key, await readJson(request), now));
      if (sub === "codes" && key && m === "DELETE") return json(await C.revokeCode(env, account, cid, key));
      if (sub === "requests" && !key && m === "GET") return json(await C.listRequests(env, account, cid));
      if (sub === "requests" && key && m === "POST") return json(await C.decide(env, account, cid, key, await readJson(request), now));
      if (sub === "members" && !key && m === "GET") return json(await C.listMembers(env, account, cid));
      if (sub === "members" && key && m === "PATCH") return json(await C.setMemberRole(env, account, cid, key, await readJson(request)));
      if (sub === "members" && key && m === "DELETE") return json(await C.kick(env, account, cid, key, q.get("ban") === "1", now));
    }
    const cm = /^\/v2\/comments\/(\d{1,20})$/.exec(p);
    if (cm && m === "DELETE") return json(await F.deleteComment(env, account, cm[1], now));
    const pm = /^\/v2\/posts\/(\d{1,20})(\/[a-z]+)?$/.exec(p);
    if (pm) {
      const [, id, sub] = pm;
      if (!sub && m === "GET") return json(await F.getPost(env, account, id, now));
      if (!sub && m === "DELETE") return json(await F.deletePost(env, account, id, now));
      if (sub === "/comments" && m === "GET") return json(await F.comments(env, account, id, q));
      if (sub === "/comments" && m === "POST") return json(await F.createComment(env, account, id, await readJson(request), now), 201);
      if (sub === "/react" && m === "POST") return json(await F.react(env, account, id, await readJson(request), now));
      if (sub === "/solve" && m === "POST") return json(await F.solve(env, account, id, await readJson(request), now));
    }
  }
  if (p.startsWith("/v2/me") || p.startsWith("/v2/auth/")) {
    const account = await A.authenticate(request, env, now);
    if (p === "/v2/me" && m === "GET") return json({ account: await D.withClaim(env, account, now) });
    if (p === "/v2/me/discord" && m === "DELETE") return json(await D.unlink(env, account, now));
    if (p === "/v2/me/lost-tag" && m === "DELETE") return json(await A.ackLostTag(env, account, now));
    if (p === "/v2/me" && m === "PATCH") return json(await A.updateMe(env, account, await readJson(request), now));
    if (p === "/v2/me/tag" && m === "POST") return json(await A.changeTag(env, account, await readJson(request), now));
    if (p === "/v2/me/recovery" && m === "POST") return json(await A.newRecovery(env, account));
    if (p === "/v2/auth/logout" && m === "POST") return json(await A.logout(env, account));
  }
  throw new HttpError(404, "not_found", "Не найдено");
}

/** Account if a Bearer key is sent, otherwise null (anonymous call). */
async function optionalAccount(request, env, now) {
  if (!request.headers.get("authorization")) return null;
  return A.authenticate(request, env, now);
}
