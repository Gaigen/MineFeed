import { HttpError, base64url, randomBytes, recoveryCode, normalizeRecovery, sha256Hex, clientIp } from "./util.js";
import { normalizeTag, tagProblem, validTag, validNick } from "./names.js";

export const DAY = 86_400_000;
export const TAG_COOLDOWN = 14 * DAY;   // how often a tag can be changed
export const TAG_HOLD = 7 * DAY;        // old tag stays reserved for its owner
const TOUCH_EVERY = 6 * 3_600_000;      // update last_used_at at most this often (saves D1 writes)
const KEY_PREFIX = "mfk_";

export const LIMITS = {
  register: { max: 5, windowMs: 3_600_000 },  // per IP per hour
  recover: { max: 10, windowMs: 3_600_000 },  // per IP per hour
  authFail: { max: 30, windowMs: 600_000 },   // bad keys per IP per 10 min
};

export async function rateLimit(env, name, id, now) {
  const { max, windowMs } = LIMITS[name];
  const bucket = `${name}:${id}`;
  const windowStart = now - (now % windowMs);
  const row = await env.DB.prepare(
    `INSERT INTO rate_limits (bucket, window_start, count) VALUES (?1, ?2, 1)
     ON CONFLICT(bucket) DO UPDATE SET
       count = CASE WHEN window_start = ?2 THEN count + 1 ELSE 1 END,
       window_start = ?2
     RETURNING count`
  ).bind(bucket, windowStart).first();
  if (row.count > max) {
    const retry = Math.ceil((windowStart + windowMs - now) / 1000);
    throw new HttpError(429, "rate_limited", "Слишком много попыток, попробуй позже", { retryAfter: retry });
  }
}

function publicAccount(a, now) {
  const nextTagChange = a.tag_changed_at ? a.tag_changed_at + TAG_COOLDOWN : null;
  return {
    id: a.id,
    tag: a.tag,
    nick: a.nick,
    createdAt: a.created_at,
    tagProtected: !!a.tag_protected,
    discordLinked: !!a.discord_id,
    canChangeTagAt: nextTagChange && nextTagChange > now ? nextTagChange : null,
  };
}

async function tagTaken(env, tag, accountId, now) {
  const owner = await env.DB.prepare("SELECT id FROM accounts WHERE tag = ?").bind(tag).first();
  if (owner && owner.id !== accountId) return true;
  const hold = await env.DB.prepare("SELECT account_id, until FROM tag_holds WHERE tag = ?").bind(tag).first();
  return !!(hold && hold.until > now && hold.account_id !== accountId);
}

async function newSession(env, accountId, now) {
  const key = KEY_PREFIX + base64url(randomBytes(32));
  await env.DB.prepare("INSERT INTO sessions (key_hash, account_id, created_at, last_used_at) VALUES (?, ?, ?, ?)")
    .bind(await sha256Hex(key), accountId, now, now).run();
  return key;
}

/** Resolves the account for `Authorization: Bearer mfk_...` or throws 401. */
export async function authenticate(request, env, now) {
  const h = request.headers.get("authorization") || "";
  const m = /^Bearer (mfk_[A-Za-z0-9_-]{43})$/.exec(h);
  if (!m) throw new HttpError(401, "no_auth", "Нужен вход в аккаунт MineFeed");
  const hash = await sha256Hex(m[1]);
  const row = await env.DB.prepare(
    `SELECT a.*, s.key_hash, s.last_used_at FROM sessions s JOIN accounts a ON a.id = s.account_id WHERE s.key_hash = ?`
  ).bind(hash).first();
  if (!row) {
    await rateLimit(env, "authFail", clientIp(request), now);
    throw new HttpError(401, "bad_auth", "Ключ аккаунта недействителен");
  }
  if (row.banned) throw new HttpError(403, "banned", "Аккаунт заблокирован");
  if (now - row.last_used_at > TOUCH_EVERY) {
    await env.DB.prepare("UPDATE sessions SET last_used_at = ? WHERE key_hash = ?").bind(now, hash).run();
  }
  return row;
}

export async function checkTag(env, rawTag, now) {
  const tag = normalizeTag(rawTag);
  const problem = tagProblem(tag);
  if (problem) return { tag, available: false, reason: problem };
  if (await tagTaken(env, tag, null, now)) return { tag, available: false, reason: "Тег уже занят" };
  return { tag, available: true };
}

export async function register(request, env, body, now) {
  await rateLimit(env, "register", clientIp(request), now);
  const tag = validTag(body.tag);
  const nick = validNick(body.nick);
  if (await tagTaken(env, tag, null, now)) throw new HttpError(409, "tag_taken", "Тег уже занят");
  const id = crypto.randomUUID();
  const recovery = recoveryCode();
  try {
    await env.DB.prepare(
      "INSERT INTO accounts (id, tag, nick, created_at, recovery_hash) VALUES (?, ?, ?, ?, ?)"
    ).bind(id, tag, nick, now, await sha256Hex(normalizeRecovery(recovery))).run();
  } catch (e) {
    if (String(e.message).includes("UNIQUE")) throw new HttpError(409, "tag_taken", "Тег уже занят");
    throw e;
  }
  const key = await newSession(env, id, now);
  const acc = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(id).first();
  return { account: publicAccount(acc, now), key, recoveryCode: recovery };
}

export async function me(account, now) {
  return { account: publicAccount(account, now) };
}

export async function updateMe(env, account, body, now) {
  if (body.nick !== undefined) {
    const nick = validNick(body.nick);
    await env.DB.prepare("UPDATE accounts SET nick = ? WHERE id = ?").bind(nick, account.id).run();
  }
  const acc = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(account.id).first();
  return { account: publicAccount(acc, now) };
}

export async function changeTag(env, account, body, now) {
  const tag = validTag(body.tag);
  if (tag === account.tag) return { account: publicAccount(account, now) };
  if (account.tag_changed_at && account.tag_changed_at + TAG_COOLDOWN > now) {
    throw new HttpError(429, "tag_cooldown", "Тег можно менять раз в 14 дней", { canChangeTagAt: account.tag_changed_at + TAG_COOLDOWN });
  }
  if (await tagTaken(env, tag, account.id, now)) throw new HttpError(409, "tag_taken", "Тег уже занят");
  try {
    await env.DB.batch([
      env.DB.prepare("INSERT INTO tag_holds (tag, account_id, until) VALUES (?1, ?2, ?3) ON CONFLICT(tag) DO UPDATE SET account_id = ?2, until = ?3")
        .bind(account.tag, account.id, now + TAG_HOLD),
      env.DB.prepare("DELETE FROM tag_holds WHERE tag = ? AND account_id = ?").bind(tag, account.id),
      // changing the tag drops Discord protection: it belonged to the old tag
      env.DB.prepare("UPDATE accounts SET tag = ?, tag_changed_at = ?, tag_protected = 0 WHERE id = ?").bind(tag, now, account.id),
    ]);
  } catch (e) {
    if (String(e.message).includes("UNIQUE")) throw new HttpError(409, "tag_taken", "Тег уже занят");
    throw e;
  }
  const acc = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(account.id).first();
  return { account: publicAccount(acc, now) };
}

/** Recovery code -> new device key + new recovery code. All old keys are revoked. */
export async function recover(request, env, body, now) {
  await rateLimit(env, "recover", clientIp(request), now);
  const code = normalizeRecovery(body.recoveryCode);
  if (code.length !== 20) throw new HttpError(400, "bad_code", "Неверный код восстановления");
  const acc = await env.DB.prepare("SELECT * FROM accounts WHERE recovery_hash = ?").bind(await sha256Hex(code)).first();
  if (!acc) throw new HttpError(404, "bad_code", "Неверный код восстановления");
  if (acc.banned) throw new HttpError(403, "banned", "Аккаунт заблокирован");
  const recovery = recoveryCode();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM sessions WHERE account_id = ?").bind(acc.id),
    env.DB.prepare("UPDATE accounts SET recovery_hash = ? WHERE id = ?").bind(await sha256Hex(normalizeRecovery(recovery)), acc.id),
  ]);
  const key = await newSession(env, acc.id, now);
  return { account: publicAccount(acc, now), key, recoveryCode: recovery };
}

/** New recovery code (old one stops working). Requires the current key. */
export async function newRecovery(env, account) {
  const recovery = recoveryCode();
  await env.DB.prepare("UPDATE accounts SET recovery_hash = ? WHERE id = ?").bind(await sha256Hex(normalizeRecovery(recovery)), account.id).run();
  return { recoveryCode: recovery };
}

export async function logout(env, account) {
  await env.DB.prepare("DELETE FROM sessions WHERE key_hash = ?").bind(account.key_hash).run();
  return { ok: true };
}

export async function publicProfile(env, rawTag, now) {
  const tag = normalizeTag(rawTag);
  const acc = await env.DB.prepare("SELECT * FROM accounts WHERE tag = ? AND banned = 0").bind(tag).first();
  if (!acc) throw new HttpError(404, "not_found", "Пользователь не найден");
  const { id, tag: t, nick, createdAt, tagProtected } = publicAccount(acc, now);
  return { user: { id, tag: t, nick, createdAt, tagProtected } };
}
