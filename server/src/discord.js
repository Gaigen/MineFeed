// Optional Discord link (OAuth2, scope "identify"). The mod starts a flow, opens the browser,
// and polls with a secret that never appears in the browser URL.
import { HttpError, base64url, randomBytes, sha256Hex, clientIp } from "./util.js";
import { rateLimit, publicAccount, newSession } from "./accounts.js";
import { normalizeTag, tagProblem } from "./names.js";

const DISCORD = "https:" + "//discord.com";
export const FLOW_TTL = 10 * 60_000;

// Browser pages: English by default (shared English-first community), Russian for ru browsers.
const PAGES = {
  expired: { en: ["Link expired", "Start again in the game: Profile → Discord."], ru: ["Ссылка устарела", "Начни заново в игре: Профиль → Discord."] },
  denied: { en: ["Cancelled", "You didn't allow access. You can close this tab and go back to Minecraft."], ru: ["Отменено", "Ты не разрешил доступ. Можно закрыть вкладку и вернуться в Minecraft."] },
  failed: { en: ["Something went wrong", "Discord didn't respond. Go back to the game and try again."], ru: ["Не получилось", "Discord не ответил. Вернись в игру и попробуй ещё раз."] },
  not_linked: { en: ["Profile not found", "No MineFeed profile is linked to this Discord. Go back to the game: you can create a profile or sign in with a recovery code."], ru: ["Профиль не найден", "К этому Discord не привязан профиль MineFeed. Вернись в игру: можно создать профиль или войти по коду восстановления."] },
  banned: { en: ["Account banned", "Sign-in is not possible."], ru: ["Аккаунт заблокирован", "Вход невозможен."] },
  login_ok: { en: ["Done!", "Signed in as @{tag}. Close this tab and go back to Minecraft."], ru: ["Готово!", "Вход в @{tag} подтверждён. Закрой вкладку и вернись в Minecraft."] },
  taken: { en: ["Discord already linked", "This Discord is already linked to another MineFeed profile. Unlink it there first."], ru: ["Discord уже занят", "Этот Discord уже привязан к другому профилю MineFeed. Сначала отвяжи его там."] },
  no_profile: { en: ["Profile not found", "Go back to the game and try again."], ru: ["Профиль не найден", "Вернись в игру и попробуй ещё раз."] },
  link_ok: { en: ["Discord linked!", "Profile @{tag} is now linked to Discord. Close this tab and go back to Minecraft."], ru: ["Discord привязан!", "Профиль @{tag} связан с Discord. Закрой вкладку и вернись в Minecraft."] },
};

function lang(request) {
  return /^\s*ru\b/i.test(request.headers.get("accept-language") || "") ? "ru" : "en";
}

function say(request, status, id, vars = {}) {
  const l = lang(request);
  const [title, body] = PAGES[id][l];
  return page(status, title, esc(body).replace(/\{(\w+)\}/g, (_, k) => esc(vars[k] ?? "")), l);
}

function redirectUri(request) {
  return new URL(request.url).origin + "/v2/discord/callback";
}

function configured(env) {
  if (!env.DISCORD_CLIENT_ID || !env.DISCORD_CLIENT_SECRET) {
    throw new HttpError(503, "discord_off", "Привязка Discord сейчас недоступна");
  }
}

/** POST /v2/discord/start {kind}. `account` is required for kind=link. */
export async function start(request, env, body, account, now) {
  configured(env);
  const kind = body.kind === "login" ? "login" : "link";
  if (kind === "link" && !account) throw new HttpError(401, "no_auth", "Нужен вход в аккаунт MineFeed");
  await rateLimit(env, "discordStart", clientIp(request), now);
  const state = base64url(randomBytes(24));
  const pollSecret = base64url(randomBytes(32));
  await env.DB.batch([
    env.DB.prepare("DELETE FROM discord_flows WHERE created_at < ?").bind(now - FLOW_TTL),
    env.DB.prepare("INSERT INTO discord_flows (state, kind, account_id, poll_hash, created_at) VALUES (?, ?, ?, ?, ?)")
      .bind(state, kind, kind === "link" ? account.id : null, await sha256Hex(pollSecret), now),
  ]);
  const origin = new URL(request.url).origin;
  return { state, pollSecret, url: `${origin}/v2/discord/go/${state}`, expiresAt: now + FLOW_TTL };
}

/** GET /v2/discord/go/:state — short link the mod opens; redirects to Discord's consent page. */
export async function go(request, env, state, now) {
  configured(env);
  const flow = await env.DB.prepare("SELECT state, created_at, status FROM discord_flows WHERE state = ?").bind(state).first();
  if (!flow || flow.created_at + FLOW_TTL < now || flow.status !== "pending") {
    return say(request, 410, "expired");
  }
  const q = new URLSearchParams({
    response_type: "code",
    client_id: env.DISCORD_CLIENT_ID,
    scope: "identify",
    redirect_uri: redirectUri(request),
    state,
    prompt: "none",
  });
  return new Response(null, { status: 302, headers: { location: `${DISCORD}/oauth2/authorize?${q}`, "cache-control": "no-store" } });
}

async function finish(env, state, status, code = null, text = null, accountId = undefined) {
  if (accountId !== undefined) {
    await env.DB.prepare("UPDATE discord_flows SET status = ?, error_code = ?, error_text = ?, account_id = ? WHERE state = ?")
      .bind(status, code, text, accountId, state).run();
  } else {
    await env.DB.prepare("UPDATE discord_flows SET status = ?, error_code = ?, error_text = ? WHERE state = ?")
      .bind(status, code, text, state).run();
  }
}

/** Exchange the code and fetch the Discord user. Uses env.fetch in tests. */
async function discordUser(request, env, code) {
  const f = env.DISCORD_FETCH || fetch;
  const tokenRes = await f(`${DISCORD}/api/v10/oauth2/token`, {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      authorization: "Basic " + btoa(`${env.DISCORD_CLIENT_ID}:${env.DISCORD_CLIENT_SECRET}`),
    },
    body: new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: redirectUri(request) }),
  });
  if (!tokenRes.ok) return null;
  const token = await tokenRes.json();
  const userRes = await f(`${DISCORD}/api/v10/users/@me`, { headers: { authorization: `Bearer ${token.access_token}` } });
  // we only needed one look at the profile; drop the token right away
  try {
    await f(`${DISCORD}/api/v10/oauth2/token/revoke`, {
      method: "POST",
      headers: {
        "content-type": "application/x-www-form-urlencoded",
        authorization: "Basic " + btoa(`${env.DISCORD_CLIENT_ID}:${env.DISCORD_CLIENT_SECRET}`),
      },
      body: new URLSearchParams({ token: token.access_token, token_type_hint: "access_token" }),
    });
  } catch { /* best effort */ }
  if (!userRes.ok) return null;
  const u = await userRes.json();
  if (!u || !u.id) return null;
  // new-style handles are unique and fit our tag rules; legacy name#1234 is not a handle
  const legacy = u.discriminator && u.discriminator !== "0";
  const handle = legacy ? null : normalizeTag(u.username);
  return { id: String(u.id), username: handle && !tagProblem(handle) ? handle : null, display: u.global_name || u.username };
}

/** GET /v2/discord/callback?code&state — Discord sends the browser here. */
export async function callback(request, env, now) {
  configured(env);
  const url = new URL(request.url);
  const state = url.searchParams.get("state") || "";
  const flow = await env.DB.prepare("SELECT * FROM discord_flows WHERE state = ?").bind(state).first();
  if (!flow || flow.created_at + FLOW_TTL < now || flow.status !== "pending") {
    return say(request, 410, "expired");
  }
  if (url.searchParams.get("error")) {
    await finish(env, state, "error", "denied", "Доступ не разрешён");
    return say(request, 200, "denied");
  }
  const user = await discordUser(request, env, url.searchParams.get("code") || "");
  if (!user) {
    await finish(env, state, "error", "discord_failed", "Discord не ответил, попробуй ещё раз");
    return say(request, 502, "failed");
  }
  const owner = await env.DB.prepare("SELECT id, tag, banned FROM accounts WHERE discord_id = ?").bind(user.id).first();

  if (flow.kind === "login") {
    if (!owner) {
      await finish(env, state, "error", "not_linked", "К этому Discord не привязан профиль MineFeed");
      return say(request, 404, "not_linked");
    }
    if (owner.banned) {
      await finish(env, state, "error", "banned", "Аккаунт заблокирован");
      return say(request, 403, "banned");
    }
    await finish(env, state, "done", null, null, owner.id);
    return say(request, 200, "login_ok", { tag: owner.tag });
  }

  // link
  if (owner && owner.id !== flow.account_id) {
    await finish(env, state, "error", "discord_taken", "Этот Discord уже привязан к другому профилю");
    return say(request, 409, "taken");
  }
  const acc = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(flow.account_id).first();
  if (!acc) {
    await finish(env, state, "error", "not_found", "Профиль не найден");
    return say(request, 404, "no_profile");
  }
  // your current tag becomes protected if it is your Discord handle
  const protect = user.username && user.username === acc.tag ? 1 : acc.tag_protected && acc.discord_id === user.id ? 1 : 0;
  try {
    await env.DB.prepare("UPDATE accounts SET discord_id = ?, discord_username = ?, tag_protected = ? WHERE id = ?")
      .bind(user.id, user.username, protect, acc.id).run();
  } catch (e) {
    if (String(e.message).includes("UNIQUE")) {
      await finish(env, state, "error", "discord_taken", "Этот Discord уже привязан к другому профилю");
      return say(request, 409, "taken");
    }
    throw e;
  }
  await finish(env, state, "done");
  return say(request, 200, "link_ok", { tag: acc.tag });
}

/** POST /v2/discord/poll {state, pollSecret} — one-time result for the mod. */
export async function poll(env, body, now) {
  const state = String(body.state || "");
  const secret = String(body.pollSecret || "");
  const flow = await env.DB.prepare("SELECT * FROM discord_flows WHERE state = ?").bind(state).first();
  if (!flow || flow.poll_hash !== (await sha256Hex(secret))) throw new HttpError(404, "flow_not_found", "Привязка не найдена, начни заново");
  if (flow.status === "pending") {
    if (flow.created_at + FLOW_TTL < now) {
      await env.DB.prepare("DELETE FROM discord_flows WHERE state = ?").bind(state).run();
      return { status: "error", code: "expired", error: "Время вышло, начни заново" };
    }
    return { status: "pending" };
  }
  await env.DB.prepare("DELETE FROM discord_flows WHERE state = ?").bind(state).run();
  if (flow.status === "error") return { status: "error", code: flow.error_code, error: flow.error_text };
  const acc = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(flow.account_id).first();
  if (!acc || acc.banned) return { status: "error", code: "not_found", error: "Профиль не найден" };
  const out = { status: "done", kind: flow.kind, account: await withClaim(env, acc, now) };
  if (flow.kind === "login") out.key = await newSession(env, acc.id, now);
  return out;
}

/** DELETE /v2/me/discord */
export async function unlink(env, account, now) {
  await env.DB.prepare("UPDATE accounts SET discord_id = NULL, discord_username = NULL, tag_protected = 0 WHERE id = ?")
    .bind(account.id).run();
  const acc = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(account.id).first();
  return { account: publicAccount(acc, now) };
}

/** Account JSON plus `claimableTag`: your Discord handle, if you could switch to it now. */
export async function withClaim(env, acc, now) {
  const pub = publicAccount(acc, now);
  pub.claimableTag = null;
  const h = acc.discord_username;
  if (h && h !== acc.tag) {
    const owner = await env.DB.prepare("SELECT id, tag_protected FROM accounts WHERE tag = ?").bind(h).first();
    if (!owner || !owner.tag_protected) pub.claimableTag = h;
  }
  return pub;
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/** Tiny retro page shown in the browser after Discord. `body` must already be escaped. */
export function page(status, title, body, l = "en") {
  const html = `<!doctype html><html lang="${l}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MineFeed — ${esc(title)}</title><style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#000;color:#d6d6d6;font:16px/1.5 "Courier New",monospace}
.box{max-width:440px;margin:24px;padding:24px 28px;border:2px solid #8a8a8a;box-shadow:6px 6px 0 #2e2e2e}
h1{margin:0 0 12px;font-size:18px;color:#ffff55;letter-spacing:1px}h1:before{content:"_ "}p{margin:0}small{display:block;margin-top:16px;color:#8a8a8a}
</style></head><body><div class="box"><h1>${esc(title).toUpperCase()}</h1><p>${body}</p><small>MINEFEED</small></div></body></html>`;
  return new Response(html, {
    status,
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-frame-options": "DENY", "referrer-policy": "no-referrer" },
  });
}
