// MineFeed API v2 (accounts stage). Bindings: DB (D1). Vars: MIN_CLIENT, LATEST_CLIENT, DOWNLOAD_URL.
import { HttpError, json, errorResponse, readJson } from "./util.js";
import * as A from "./accounts.js";

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
  if (p === "/v2/tags/check" && m === "GET") return json(await A.checkTag(env, url.searchParams.get("tag"), now));
  if (p === "/v2/auth/register" && m === "POST") return json(await A.register(request, env, await readJson(request), now), 201);
  if (p === "/v2/auth/recover" && m === "POST") return json(await A.recover(request, env, await readJson(request), now));

  const userMatch = /^\/v2\/users\/@?([^/]+)$/.exec(p);
  if (userMatch && m === "GET") return json(await A.publicProfile(env, decodeURIComponent(userMatch[1]), now));

  // everything below needs an account
  if (p.startsWith("/v2/me") || p.startsWith("/v2/auth/")) {
    const account = await A.authenticate(request, env, now);
    if (p === "/v2/me" && m === "GET") return json(await A.me(account, now));
    if (p === "/v2/me" && m === "PATCH") return json(await A.updateMe(env, account, await readJson(request), now));
    if (p === "/v2/me/tag" && m === "POST") return json(await A.changeTag(env, account, await readJson(request), now));
    if (p === "/v2/me/recovery" && m === "POST") return json(await A.newRecovery(env, account));
    if (p === "/v2/auth/logout" && m === "POST") return json(await A.logout(env, account));
  }
  throw new HttpError(404, "not_found", "Не найдено");
}
