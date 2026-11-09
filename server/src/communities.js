// Private communities: a closed Discord forum + roles "<name> · mod" and "<name> · member".
// Owner/mods manage invite codes, join requests and members from the mod.
import { HttpError, randomCode, clientIp } from "./util.js";
import { rateLimit, DAY } from "./accounts.js";
import { dapi } from "./dapi.js";
import * as N from "./notify.js";
import * as F from "./feed.js";
import { welcomeContent } from "./welcome.js";
import { keyOf } from "./avatars.js";

export const MAX_OWNED = 1;
export const MAX_CODES = 20;
const CATEGORY_LIMIT = 50; // Discord: channels per category

const BIT = (n) => 1n << BigInt(n);
const PERM = {
  VIEW: BIT(10), SEND: BIT(11), MANAGE_MESSAGES: BIT(13), EMBED: BIT(14), ATTACH: BIT(15),
  HISTORY: BIT(16), REACT: BIT(6), MANAGE_THREADS: BIT(34), SEND_IN_THREADS: BIT(38),
};
const MEMBER_PERMS = PERM.VIEW | PERM.SEND | PERM.EMBED | PERM.ATTACH | PERM.HISTORY | PERM.REACT | PERM.SEND_IN_THREADS;
const MOD_PERMS = MEMBER_PERMS | PERM.MANAGE_MESSAGES | PERM.MANAGE_THREADS;
const FORUM_TAGS = [["rip", "RIP", "💀"], ["help", "Help", "❓"], ["gg", "GG", "🏆"], ["talk", "Talk", "💬"], ["solved", "Solved", "✅"]];

const STRIP = /[\u0000-\u001f\u007f-\u009f\u00a7\u200b-\u200f\u2028-\u202e\u2060-\u206f\ufeff]/g;

function clean(raw) {
  return String(raw ?? "").replace(/\u00a7./gu, "").replace(STRIP, "").replace(/\s+/g, " ").trim();
}

export function validName(raw) {
  const name = clean(raw);
  const len = [...name].length;
  if (len < 3 || len > 32) throw new HttpError(400, "bad_name", "Название: от 3 до 32 символов");
  if (/discord|@everyone|@here/i.test(name)) throw new HttpError(400, "bad_name", "Такое название использовать нельзя");
  return name;
}

export function validDescription(raw) {
  const d = clean(raw);
  if ([...d].length > 200) throw new HttpError(400, "bad_description", "Описание: максимум 200 символов");
  return d;
}

export function channelName(name) {
  const s = name.toLowerCase().replace(/\s+/g, "-").replace(/[^\p{L}\p{N}_-]/gu, "").replace(/-+/g, "-").replace(/^-|-$/g, "");
  return [...(s || "community")].slice(0, 90).join("");
}

export function normalizeCode(raw) {
  return String(raw ?? "").trim().toUpperCase().replace(/\s+/g, "");
}

function validCode(raw) {
  const code = normalizeCode(raw);
  if (!/^[A-Z0-9](?:[A-Z0-9-]{2,14})[A-Z0-9]$/.test(code)) {
    throw new HttpError(400, "bad_code", "Код: 4–16 символов, латиница, цифры и дефис");
  }
  return code;
}

function maxMembers(env) {
  const n = Number.parseInt(env.COMMUNITY_MAX_MEMBERS, 10);
  return Number.isFinite(n) && n > 0 ? n : 200;
}

const isMod = (role) => role === "owner" || role === "mod";

function out(env, c, role, extra = {}) {
  const guild = env.DISCORD_GUILD_ID;
  return {
    id: c.id, name: c.name, description: c.description, about: c.about || "", links: c.links || "", members: c.members,
    ownerId: c.owner_id, createdAt: c.created_at, role, nsfw: !!c.nsfw,
    discordUrl: guild && c.forum_id ? `https://discord.com/channels/${guild}/${c.forum_id}` : null, ...extra,
  };
}

/** Community + caller's role; 404 for non-members (existence is not revealed). */
async function access(env, account, id, need = "member") {
  const row = await env.DB.prepare(
    `SELECT c.*, m.role AS my_role FROM communities c JOIN members m ON m.community_id = c.id AND m.account_id = ?
     WHERE c.id = ? AND c.deleted = 0`
  ).bind(account.id, String(id)).first();
  if (!row) throw new HttpError(404, "no_community", "Сообщество не найдено");
  if (need === "mod" && !isMod(row.my_role)) throw new HttpError(403, "forbidden", "Нужны права модератора сообщества");
  if (need === "owner" && row.my_role !== "owner") throw new HttpError(403, "forbidden", "Это может только владелец");
  return row;
}

// ---------- Discord helpers ----------

async function setRole(env, discordId, roleId, on) {
  if (!discordId || !roleId) return;
  try {
    await dapi(env, on ? "PUT" : "DELETE", `/guilds/${env.DISCORD_GUILD_ID}/members/${discordId}/roles/${roleId}`, { allow404: true });
  } catch (e) {
    console.error("role sync failed", e.message); // never blocks the action in the mod
  }
}

async function applyRoles(env, c, discordId, role) {
  await setRole(env, discordId, c.member_role_id, !!role);
  await setRole(env, discordId, c.mod_role_id, isMod(role));
}

/** After Discord link/unlink or when a linked player joins the server: bring roles in line. */
export async function syncRoles(env, accountId, discordId, linked) {
  if (!discordId) return 0;
  const { results } = await env.DB.prepare(
    `SELECT c.*, m.role AS my_role FROM members m JOIN communities c ON c.id = m.community_id WHERE m.account_id = ? AND c.deleted = 0`
  ).bind(accountId).all();
  for (const c of results) await applyRoles(env, c, discordId, linked ? c.my_role : null);
  return results.length;
}

async function pickCategory(env, now) {
  if (env.COMMUNITY_CATEGORY_ID) {
    await env.DB.prepare("INSERT OR IGNORE INTO community_categories (id, created_at) VALUES (?, 0)").bind(env.COMMUNITY_CATEGORY_ID).run();
  }
  const { results: cats } = await env.DB.prepare("SELECT id FROM community_categories ORDER BY created_at, id").all();
  const channels = await dapi(env, "GET", `/guilds/${env.DISCORD_GUILD_ID}/channels`);
  const exists = new Set(channels.filter((c) => c.type === 4).map((c) => c.id));
  const count = {};
  for (const c of channels) if (c.parent_id) count[c.parent_id] = (count[c.parent_id] || 0) + 1;
  for (const { id } of cats) if (exists.has(id) && (count[id] || 0) < CATEGORY_LIMIT) return id;
  const cat = await dapi(env, "POST", `/guilds/${env.DISCORD_GUILD_ID}/channels`, {
    body: {
      name: `MineFeed communities ${cats.length + 1}`, type: 4,
      permission_overwrites: [{ id: env.DISCORD_GUILD_ID, type: 0, allow: "0", deny: String(PERM.VIEW) }],
    },
  });
  await env.DB.prepare("INSERT INTO community_categories (id, created_at) VALUES (?, ?)").bind(cat.id, now).run();
  return cat.id;
}

// ---------- communities ----------

export async function ownsCommunity(env, accountId) {
  const owned = await env.DB.prepare("SELECT COUNT(*) AS n FROM communities WHERE owner_id = ? AND deleted = 0").bind(accountId).first();
  return owned.n >= MAX_OWNED;
}

/** POST /v2/communities {name, description} — server moderators only; players send an application. */
export async function create(env, account, body, now) {
  if (!account.moderator) throw new HttpError(403, "apply_instead", "Сообщества создаются по заявке");
  await rateLimit(env, "community", account.id, now);
  return createCommunity(env, account, { name: body.name, description: body.description }, now);
}

/** Forum + roles + rows. The owner gets the Discord roles if their Discord is linked. */
export async function createCommunity(env, account, fields, now) {
  const name = validName(fields.name);
  const description = validDescription(fields.description);
  if (await ownsCommunity(env, account.id)) throw new HttpError(409, "limit", "Можно создать только одно сообщество");

  const g = env.DISCORD_GUILD_ID;
  const made = { roles: [], channel: null };
  try {
    const category = await pickCategory(env, now);
    const modRole = await dapi(env, "POST", `/guilds/${g}/roles`, { body: { name: `${name} · mod`, permissions: "0", mentionable: false } });
    made.roles.push(modRole.id);
    const memberRole = await dapi(env, "POST", `/guilds/${g}/roles`, { body: { name: `${name} · member`, permissions: "0", mentionable: false } });
    made.roles.push(memberRole.id);
    const overwrites = [
      { id: g, type: 0, allow: "0", deny: String(PERM.VIEW) },
      { id: memberRole.id, type: 0, allow: String(MEMBER_PERMS), deny: "0" },
      { id: modRole.id, type: 0, allow: String(MOD_PERMS), deny: "0" },
    ];
    if (env.MOD_ROLE_ID) overwrites.push({ id: env.MOD_ROLE_ID, type: 0, allow: String(MOD_PERMS), deny: "0" });
    const forum = await dapi(env, "POST", `/guilds/${g}/channels`, {
      body: {
        name: channelName(name), type: 15, parent_id: category,
        topic: description || `MineFeed community «${name}»`,
        permission_overwrites: overwrites,
        available_tags: FORUM_TAGS.map(([k, n, e]) => ({ name: n, emoji_name: e, moderated: k === "solved" })),
      },
    });
    made.channel = forum.id;
    const byName = Object.fromEntries((forum.available_tags || []).map((t) => [t.name, t.id]));
    const tags = Object.fromEntries(FORUM_TAGS.map(([k, n]) => [k, byName[n]]).filter(([, v]) => v));
    const id = randomCode(10).toLowerCase();
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO communities (id, name, description, about, links, lang, owner_id, forum_id, category_id, mod_role_id, member_role_id, members, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)`
      ).bind(id, name, description, String(fields.about || ""), String(fields.links || ""), fields.lang === "ru" ? "ru" : "en",
        account.id, forum.id, category, modRole.id, memberRole.id, now),
      env.DB.prepare("INSERT INTO members (community_id, account_id, role, joined_at, read_at) VALUES (?, ?, 'owner', ?, ?)").bind(id, account.id, now, now),
      env.DB.prepare("INSERT INTO spaces (id, forum_id, tags) VALUES (?, ?, ?)").bind(id, forum.id, JSON.stringify(tags)),
    ]);
    const c = await env.DB.prepare("SELECT * FROM communities WHERE id = ?").bind(id).first();
    if (account.discord_id) await applyRoles(env, c, account.discord_id, "owner");
    return { community: out(env, c, "owner") };
  } catch (e) {
    // undo half-made Discord objects
    if (made.channel) await dapi(env, "DELETE", `/channels/${made.channel}`, { allow404: true }).catch(() => {});
    for (const r of made.roles) await dapi(env, "DELETE", `/guilds/${g}/roles/${r}`, { allow404: true }).catch(() => {});
    throw e;
  }
}

/** GET /v2/communities — mine (with unread) and pending requests. */
export async function mine(env, account) {
  const { results } = await env.DB.prepare(
    `SELECT c.*, m.role AS my_role,
       (SELECT COUNT(*) FROM posts p WHERE p.space = c.id AND p.deleted = 0 AND p.created_at > m.read_at
          AND (p.author_id IS NULL OR p.author_id <> m.account_id)) AS unread
     FROM members m JOIN communities c ON c.id = m.community_id
     WHERE m.account_id = ? AND c.deleted = 0 ORDER BY m.joined_at`
  ).bind(account.id).all();
  const { results: pending } = await env.DB.prepare(
    `SELECT c.id, c.name FROM join_requests r JOIN communities c ON c.id = r.community_id WHERE r.account_id = ? AND c.deleted = 0`
  ).bind(account.id).all();
  const requests = {};
  const modOf = results.filter((c) => isMod(c.my_role)).map((c) => c.id);
  if (modOf.length) {
    const { results: rq } = await env.DB.prepare(
      `SELECT community_id, COUNT(*) AS n FROM join_requests WHERE community_id IN (${modOf.map(() => "?").join(",")}) GROUP BY community_id`
    ).bind(...modOf).all();
    for (const r of rq) requests[r.community_id] = r.n;
  }
  const app = await env.DB.prepare("SELECT id, name, status, reason, community_id, created_at FROM community_applications WHERE account_id = ? ORDER BY created_at DESC LIMIT 1").bind(account.id).first();
  return {
    application: app ? { id: app.id, name: app.name, status: app.status, reason: app.reason, communityId: app.community_id, createdAt: app.created_at } : null,
    communities: results.map((c) => out(env, c, c.my_role, { unread: c.unread, ...(isMod(c.my_role) ? { requests: requests[c.id] || 0 } : {}) })),
    pending,
    canApply: !!account.discord_id && !results.some((c) => c.my_role === "owner") && !(app && app.status === "pending"),
  };
}

/** GET /v2/communities/:id */
export async function get(env, account, id) {
  const c = await access(env, account, id);
  return { community: out(env, c, c.my_role) };
}

/** PATCH /v2/communities/:id {name?, description?} — owner. */
export async function update(env, account, id, body) {
  const c = await access(env, account, id, "owner");
  const name = body.name === undefined ? c.name : validName(body.name);
  const description = body.description === undefined ? c.description : validDescription(body.description);
  const about = body.about === undefined ? c.about : optional(body.about, 1000);
  const links = body.links === undefined ? c.links : optional(body.links, 300);
  const nsfw = body.nsfw === undefined ? !!c.nsfw : body.nsfw === true;
  if (!nsfw && c.nsfw && !account.moderator) {
    // the forum may already hold 18+ posts: only a server moderator who checked it may take the mark off
    throw new HttpError(403, "nsfw_locked", "Снять метку 18+ может только модератор сервера");
  }
  if (nsfw && !c.nsfw && !account.show_nsfw) throw new HttpError(403, "need_18", "Сначала включи «Показывать 18+» в настройках профиля");
  if (nsfw !== !!c.nsfw) {
    // Discord: age-restricted channel (it asks for the age itself before showing it)
    await dapi(env, "PATCH", `/channels/${c.forum_id}`, { body: { nsfw }, allow404: true });
  }
  if (name !== c.name) {
    await dapi(env, "PATCH", `/guilds/${env.DISCORD_GUILD_ID}/roles/${c.mod_role_id}`, { body: { name: `${name} · mod` }, allow404: true });
    await dapi(env, "PATCH", `/guilds/${env.DISCORD_GUILD_ID}/roles/${c.member_role_id}`, { body: { name: `${name} · member` }, allow404: true });
  }
  if (name !== c.name || description !== c.description) {
    await dapi(env, "PATCH", `/channels/${c.forum_id}`, {
      body: { name: channelName(name), topic: description || `MineFeed community «${name}»` }, allow404: true,
    });
  }
  const next = { ...c, name, description, about, links, nsfw: nsfw ? 1 : 0 };
  let commentId = c.welcome_comment_id;
  if (c.welcome_thread_id && (name !== c.name || description !== c.description || about !== c.about || links !== c.links)) {
    try {
      commentId = await F.updateWelcome(env, c.id, account, { threadId: c.welcome_thread_id, commentId }, welcomeContent(next, account));
    } catch (e) {
      console.error("welcome edit failed", e.message); // the settings are saved anyway
    }
  }
  await env.DB.prepare("UPDATE communities SET name = ?, description = ?, about = ?, links = ?, welcome_comment_id = ?, nsfw = ? WHERE id = ?")
    .bind(name, description, about, links, commentId, nsfw ? 1 : 0, c.id).run();
  return { community: out(env, next, c.my_role) };
}

function optional(raw, max) {
  return String(raw ?? "").trim() ? F.cleanText(raw, max) : "";
}

/** DELETE /v2/communities/:id — owner. Removes the forum, roles and all posts. */
export async function remove(env, account, id, now) {
  const c = await access(env, account, id, "owner");
  await dapi(env, "DELETE", `/channels/${c.forum_id}`, { allow404: true });
  for (const r of [c.mod_role_id, c.member_role_id]) await dapi(env, "DELETE", `/guilds/${env.DISCORD_GUILD_ID}/roles/${r}`, { allow404: true });
  await env.DB.batch([
    env.DB.prepare("UPDATE communities SET deleted = 1 WHERE id = ?").bind(c.id),
    env.DB.prepare("DELETE FROM members WHERE community_id = ?").bind(c.id),
    env.DB.prepare("DELETE FROM invite_codes WHERE community_id = ?").bind(c.id),
    env.DB.prepare("DELETE FROM join_requests WHERE community_id = ?").bind(c.id),
    env.DB.prepare("DELETE FROM community_bans WHERE community_id = ?").bind(c.id),
    env.DB.prepare("DELETE FROM spaces WHERE id = ?").bind(c.id),
    env.DB.prepare("UPDATE posts SET deleted = 1, updated_at = ? WHERE space = ? AND deleted = 0").bind(now, c.id),
  ]);
  return { ok: true };
}

// ---------- invite codes ----------

function codeOut(r, now) {
  const expired = r.expires_at !== null && r.expires_at <= now;
  const usedUp = r.max_uses !== null && r.uses >= r.max_uses;
  return {
    code: r.code, note: r.note, uses: r.uses, maxUses: r.max_uses, expiresAt: r.expires_at,
    approval: !!r.approval, createdAt: r.created_at, dead: expired || usedUp,
  };
}

function codeFields(body, now, base = {}) {
  const f = { ...base };
  if (body.note !== undefined) {
    f.note = clean(body.note);
    if ([...f.note].length > 40) throw new HttpError(400, "bad_note", "Заметка: максимум 40 символов");
  }
  if (body.maxUses !== undefined) {
    if (body.maxUses === null || body.maxUses === 0) f.max_uses = null;
    else if (Number.isInteger(body.maxUses) && body.maxUses >= 1 && body.maxUses <= 1000) f.max_uses = body.maxUses;
    else throw new HttpError(400, "bad_max_uses", "Лимит: от 1 до 1000 или без лимита");
  }
  if (body.expiresInDays !== undefined) {
    if (body.expiresInDays === null || body.expiresInDays === 0) f.expires_at = null;
    else if (Number.isInteger(body.expiresInDays) && body.expiresInDays >= 1 && body.expiresInDays <= 365) f.expires_at = now + body.expiresInDays * DAY;
    else throw new HttpError(400, "bad_expiry", "Срок: от 1 до 365 дней или бессрочно");
  }
  if (body.approval !== undefined) f.approval = body.approval ? 1 : 0;
  return f;
}

/** GET /v2/communities/:id/codes — mods. */
export async function listCodes(env, account, id, now) {
  const c = await access(env, account, id, "mod");
  const { results } = await env.DB.prepare("SELECT * FROM invite_codes WHERE community_id = ? AND revoked = 0 ORDER BY created_at DESC").bind(c.id).all();
  return { codes: results.map((r) => codeOut(r, now)) };
}

/** POST /v2/communities/:id/codes {code?, note, maxUses, expiresInDays, approval} — mods. */
export async function createCode(env, account, id, body, now) {
  const c = await access(env, account, id, "mod");
  const active = await env.DB.prepare("SELECT COUNT(*) AS n FROM invite_codes WHERE community_id = ? AND revoked = 0").bind(c.id).first();
  if (active.n >= MAX_CODES) throw new HttpError(409, "limit", `Не больше ${MAX_CODES} кодов — удали ненужные`);
  const code = body.code ? validCode(body.code) : `MF-${randomCode(4)}-${randomCode(4)}`;
  const f = codeFields(body, now, { note: "", max_uses: null, expires_at: null, approval: 0 });
  // a revoked code may be taken again
  await env.DB.prepare("DELETE FROM invite_codes WHERE code = ? AND revoked = 1").bind(code).run();
  try {
    await env.DB.prepare(
      `INSERT INTO invite_codes (code, community_id, note, max_uses, expires_at, approval, created_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(code, c.id, f.note, f.max_uses, f.expires_at, f.approval, account.id, now).run();
  } catch (e) {
    if (String(e.message).includes("UNIQUE")) throw new HttpError(409, "code_taken", "Такой код уже занят");
    throw e;
  }
  const r = await env.DB.prepare("SELECT * FROM invite_codes WHERE code = ?").bind(code).first();
  return { code: codeOut(r, now) };
}

async function ownCode(env, c, raw) {
  const r = await env.DB.prepare("SELECT * FROM invite_codes WHERE code = ? AND community_id = ? AND revoked = 0").bind(normalizeCode(raw), c.id).first();
  if (!r) throw new HttpError(404, "no_code", "Код не найден");
  return r;
}

/** PATCH /v2/communities/:id/codes/:code — mods. */
export async function updateCode(env, account, id, rawCode, body, now) {
  const c = await access(env, account, id, "mod");
  const r = await ownCode(env, c, rawCode);
  const f = codeFields(body, now, r);
  await env.DB.prepare("UPDATE invite_codes SET note = ?, max_uses = ?, expires_at = ?, approval = ? WHERE code = ?")
    .bind(f.note, f.max_uses, f.expires_at, f.approval, r.code).run();
  return { code: codeOut({ ...r, ...f }, now) };
}

/** DELETE /v2/communities/:id/codes/:code — mods. */
export async function revokeCode(env, account, id, rawCode) {
  const c = await access(env, account, id, "mod");
  const r = await ownCode(env, c, rawCode);
  await env.DB.prepare("UPDATE invite_codes SET revoked = 1 WHERE code = ?").bind(r.code).run();
  return { ok: true };
}

// ---------- joining ----------

async function addMember(env, c, account, via, now) {
  await env.DB.batch([
    env.DB.prepare("INSERT INTO members (community_id, account_id, role, via, joined_at, read_at) VALUES (?, ?, 'member', ?, ?, 0)")
      .bind(c.id, account.id, via, now),
    env.DB.prepare("UPDATE communities SET members = members + 1 WHERE id = ?").bind(c.id),
    env.DB.prepare("DELETE FROM join_requests WHERE community_id = ? AND account_id = ?").bind(c.id, account.id),
  ]);
  if (account.discord_id) await applyRoles(env, c, account.discord_id, "member");
}

/** POST /v2/join {code} */
export async function join(request, env, account, body, now) {
  await rateLimit(env, "join", account.id, now);
  await rateLimit(env, "joinIp", clientIp(request), now);
  const code = normalizeCode(body.code);
  const r = code ? await env.DB.prepare("SELECT * FROM invite_codes WHERE code = ? AND revoked = 0").bind(code).first() : null;
  const c = r && await env.DB.prepare("SELECT * FROM communities WHERE id = ? AND deleted = 0").bind(r.community_id).first();
  if (!r || !c) throw new HttpError(404, "bad_code", "Код не найден");
  if (r.expires_at !== null && r.expires_at <= now) throw new HttpError(410, "code_expired", "Срок действия кода истёк");
  if (r.max_uses !== null && r.uses >= r.max_uses) throw new HttpError(410, "code_used_up", "Код больше не действует");
  const pub = { id: c.id, name: c.name, description: c.description, members: c.members, nsfw: !!c.nsfw };
  const m = await env.DB.prepare("SELECT role FROM members WHERE community_id = ? AND account_id = ?").bind(c.id, account.id).first();
  if (m) return { status: "member", community: pub };
  const banned = await env.DB.prepare("SELECT 1 AS x FROM community_bans WHERE community_id = ? AND account_id = ?").bind(c.id, account.id).first();
  if (banned) throw new HttpError(403, "banned_here", "Тебя исключили из этого сообщества");
  const pending = await env.DB.prepare("SELECT 1 AS x FROM join_requests WHERE community_id = ? AND account_id = ?").bind(c.id, account.id).first();
  if (pending) return { status: "pending", community: pub };
  if (c.members >= maxMembers(env)) throw new HttpError(409, "full", "В сообществе нет мест");
  if (c.nsfw && !account.show_nsfw) {
    throw new HttpError(403, "need_18", "Это сообщество 18+. Включи «Показывать 18+» в настройках профиля");
  }

  const used = await env.DB.prepare("UPDATE invite_codes SET uses = uses + 1 WHERE code = ? AND (max_uses IS NULL OR uses < max_uses)").bind(r.code).run();
  if (!used.meta.changes) throw new HttpError(410, "code_used_up", "Код больше не действует");
  const via = r.note || r.code;
  if (r.approval) {
    await env.DB.prepare("INSERT OR IGNORE INTO join_requests (community_id, account_id, via, created_at) VALUES (?, ?, ?, ?)")
      .bind(c.id, account.id, via, now).run();
    const { results: mods } = await env.DB.prepare("SELECT account_id FROM members WHERE community_id = ? AND role IN ('owner', 'mod')").bind(c.id).all();
    for (const mod of mods) {
      await N.add(env, { to: mod.account_id, kind: "request", communityId: c.id, actor: account, text: c.name }, now);
    }
    return { status: "pending", community: pub };
  }
  await addMember(env, c, account, via, now);
  return { status: "joined", community: { ...pub, members: c.members + 1 } };
}

/** GET /v2/communities/:id/requests — mods. */
export async function listRequests(env, account, id) {
  const c = await access(env, account, id, "mod");
  const { results } = await env.DB.prepare(
    `SELECT r.*, a.tag, a.nick, a.avatar, a.skin, a.discord_id, a.discord_avatar FROM join_requests r JOIN accounts a ON a.id = r.account_id WHERE r.community_id = ? ORDER BY r.created_at`
  ).bind(c.id).all();
  return { requests: results.map((r) => ({ account: { id: r.account_id, tag: r.tag, nick: r.nick, avatar: keyOf(r) }, via: r.via, createdAt: r.created_at })) };
}

/** POST /v2/communities/:id/requests/:accountId {approve} — mods. */
export async function decide(env, account, id, accountId, body, now) {
  const c = await access(env, account, id, "mod");
  const req = await env.DB.prepare("SELECT * FROM join_requests WHERE community_id = ? AND account_id = ?").bind(c.id, String(accountId)).first();
  if (!req) throw new HttpError(404, "no_request", "Заявка не найдена");
  if (!body.approve) {
    await env.DB.prepare("DELETE FROM join_requests WHERE community_id = ? AND account_id = ?").bind(c.id, req.account_id).run();
    return { ok: true, status: "denied" };
  }
  if (c.members >= maxMembers(env)) throw new HttpError(409, "full", "В сообществе нет мест");
  const who = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(req.account_id).first();
  if (!who || who.banned) {
    await env.DB.prepare("DELETE FROM join_requests WHERE community_id = ? AND account_id = ?").bind(c.id, req.account_id).run();
    throw new HttpError(404, "no_request", "Заявка не найдена");
  }
  await addMember(env, c, who, req.via, now);
  await N.add(env, { to: who.id, kind: "accepted", communityId: c.id, actor: account, text: c.name }, now);
  return { ok: true, status: "joined" };
}

// ---------- members ----------

/** GET /v2/communities/:id/members — any member; "via" only for mods. */
export async function listMembers(env, account, id) {
  const c = await access(env, account, id);
  const { results } = await env.DB.prepare(
    `SELECT m.*, a.tag, a.nick, a.avatar, a.skin, a.discord_id, a.discord_avatar FROM members m JOIN accounts a ON a.id = m.account_id WHERE m.community_id = ?
     ORDER BY CASE m.role WHEN 'owner' THEN 0 WHEN 'mod' THEN 1 ELSE 2 END, m.joined_at`
  ).bind(c.id).all();
  const mod = isMod(c.my_role);
  return {
    members: results.map((m) => ({
      account: { id: m.account_id, tag: m.tag, nick: m.nick, avatar: keyOf(m) }, role: m.role, joinedAt: m.joined_at, ...(mod ? { via: m.via } : {}),
    })),
  };
}

async function target(env, c, accountId) {
  const t = await env.DB.prepare(
    "SELECT m.role, a.id, a.discord_id FROM members m JOIN accounts a ON a.id = m.account_id WHERE m.community_id = ? AND m.account_id = ?"
  ).bind(c.id, String(accountId)).first();
  if (!t) throw new HttpError(404, "no_member", "Участник не найден");
  return t;
}

/** PATCH /v2/communities/:id/members/:accountId {role: mod|member} — owner. */
export async function setMemberRole(env, account, id, accountId, body) {
  const c = await access(env, account, id, "owner");
  const role = body.role === "mod" ? "mod" : body.role === "member" ? "member" : null;
  if (!role) throw new HttpError(400, "bad_role", "Роль: mod или member");
  const t = await target(env, c, accountId);
  if (t.role === "owner") throw new HttpError(400, "bad_role", "Роль владельца не меняется");
  await env.DB.prepare("UPDATE members SET role = ? WHERE community_id = ? AND account_id = ?").bind(role, c.id, t.id).run();
  await setRole(env, t.discord_id, c.mod_role_id, role === "mod");
  return { ok: true, role };
}

async function dropMember(env, c, t) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM members WHERE community_id = ? AND account_id = ?").bind(c.id, t.id),
    env.DB.prepare("UPDATE communities SET members = MAX(members - 1, 0) WHERE id = ?").bind(c.id),
  ]);
  await applyRoles(env, c, t.discord_id, null);
}

/** DELETE /v2/communities/:id/members/:accountId?ban=1 — owner kicks anyone, mods kick members. */
export async function kick(env, account, id, accountId, ban, now) {
  const c = await access(env, account, id, "mod");
  const t = await target(env, c, accountId);
  if (t.id === account.id) throw new HttpError(400, "self", "Чтобы выйти, используй «Покинуть»");
  if (t.role === "owner" || (t.role === "mod" && c.my_role !== "owner")) throw new HttpError(403, "forbidden", "Недостаточно прав");
  await dropMember(env, c, t);
  if (ban) {
    await env.DB.prepare("INSERT OR IGNORE INTO community_bans (community_id, account_id, created_at) VALUES (?, ?, ?)").bind(c.id, t.id, now).run();
  }
  return { ok: true };
}

/** POST /v2/communities/:id/leave */
export async function leave(env, account, id) {
  const c = await access(env, account, id);
  if (c.my_role === "owner") throw new HttpError(409, "owner_cannot_leave", "Владелец не может выйти — можно удалить сообщество");
  await dropMember(env, c, { id: account.id, discord_id: account.discord_id });
  return { ok: true };
}
