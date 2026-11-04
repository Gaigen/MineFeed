// MineFeed API v2. Bindings: DB (D1). Vars: MIN_CLIENT, LATEST_CLIENT, DOWNLOAD_URL, DISCORD_CLIENT_ID.
// Secrets: DISCORD_CLIENT_SECRET, DISCORD_BOT_TOKEN.
import { HttpError, json, errorResponse, readJson } from "./util.js";
import * as A from "./accounts.js";
import * as D from "./discord.js";

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
  if (userMatch && m === "GET") return json(await A.publicProfile(env, decodeURIComponent(userMatch[1]), now));

  // everything below needs an account
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
