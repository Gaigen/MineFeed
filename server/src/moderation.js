// Moderation: player reports, the report queue in the mod, resolving reports and account bans.
// Server moderators (accounts.moderator, synced from the Discord role) see and act everywhere;
// owners/mods of a community see and act on reports from their community.
import { HttpError, randomCode } from "./util.js";
import { rateLimit, DAY } from "./accounts.js";
import { dapi } from "./dapi.js";
import { freshen } from "./images.js";
import * as F from "./feed.js";
import * as C from "./communities.js";
import { closeReports, modLog } from "./modlog.js";

export const REASONS = ["spam", "abuse", "nsfw", "scam", "other"];
export const BAN_DAYS = [1, 7, 30, 0]; // 0 = permanent
const isMod = (role) => role === "owner" || role === "mod";

function note(raw, max) {
  const s = String(raw ?? "").replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2060-\u206f\ufeff]/g, " ")
    .replace(/§./g, "").replace(/\s+/g, " ").trim();
  return [...s].slice(0, max).join("");
}

/** Communities where the account is owner/mod. */
async function modCommunities(env, account) {
  const { results } = await env.DB.prepare(
    `SELECT c.id, c.name FROM members m JOIN communities c ON c.id = m.community_id
     WHERE m.account_id = ? AND m.role IN ('owner', 'mod') AND c.deleted = 0`
  ).bind(account.id).all();
  return results;
}

async function canModerate(env, account, space) {
  if (account.moderator) return true;
  if (space === "global") return false;
  const m = await env.DB.prepare(
    "SELECT m.role FROM members m JOIN communities c ON c.id = m.community_id WHERE m.community_id = ? AND m.account_id = ? AND c.deleted = 0"
  ).bind(space, account.id).first();
  return !!m && isMod(m.role);
}

/** WHERE part + binds for the reports this account may see; null = none. */
async function scope(env, account) {
  if (account.moderator) return { sql: "", binds: [], names: null };
  const mine = await modCommunities(env, account);
  if (!mine.length) return null;
  return {
    sql: ` AND r.space IN (${mine.map(() => "?").join(",")})`,
    binds: mine.map((c) => c.id),
    names: Object.fromEntries(mine.map((c) => [c.id, c.name])),
  };
}

/** Number of reported posts/comments waiting for this account (0 for non-moderators). */
export async function openCount(env, account) {
  const s = await scope(env, account);
  if (!s) return 0;
  const r = await env.DB.prepare(
    `SELECT COUNT(DISTINCT r.post_id || ':' || COALESCE(r.comment_id, '')) AS n FROM reports r WHERE r.status = 'open'${s.sql}`
  ).bind(...s.binds).first();
  return r.n;
}

// ---------- reporting ----------

/** POST /v2/reports {postId, commentId?, reason: spam|abuse|nsfw|scam|other, details?} */
export async function report(env, account, body, now) {
  const post = await F.loadPost(env, body.postId);
  let comment = null;
  if (body.commentId) {
    comment = await env.DB.prepare("SELECT * FROM comments WHERE id = ? AND post_id = ? AND deleted = 0").bind(String(body.commentId), post.id).first();
    if (!comment) throw new HttpError(404, "no_comment", "Комментарий не найден");
  }
  await F.spaceFor(env, account, post.space);
  const targetId = comment ? comment.author_id : post.author_id;
  if (targetId && targetId === account.id) throw new HttpError(400, "own_content", "На своё сообщение жаловаться нельзя");
  const reason = REASONS.includes(body.reason) ? body.reason : "other";
  // old mods send free text in "reason"
  const details = note(body.details ?? (REASONS.includes(body.reason) ? "" : body.reason), 300);
  const dup = await env.DB.prepare(
    "SELECT id FROM reports WHERE reporter_id = ? AND post_id = ? AND comment_id IS ? AND status = 'open'"
  ).bind(account.id, post.id, comment ? comment.id : null).first();
  if (dup) return { ok: true, duplicate: true };
  await rateLimit(env, "report", account.id, now);
  await env.DB.prepare(
    `INSERT INTO reports (id, post_id, comment_id, space, target_id, reporter_id, reason, details, created_at, snapshot)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(randomCode(12).toLowerCase(), post.id, comment ? comment.id : null, post.space, targetId || null, account.id, reason, details, now,
    comment ? comment.text : post.text).run();

  // the staff channel only gets the public feed: community posts stay private
  if (post.space === "global" && env.REPORT_CHANNEL_ID) {
    const link = `https://discord.com/channels/${env.DISCORD_GUILD_ID}/${post.id}` + (comment ? `/${comment.id}` : "");
    const quoted = (comment ? comment.text : post.text).slice(0, 300).replace(/\n/g, "\n> ");
    try {
      await dapi(env, "POST", `/channels/${env.REPORT_CHANNEL_ID}/messages`, {
        body: {
          content: `🚩 **Report** from @${account.tag} (${account.id})\n**Reason:** ${reason}${details ? ` — ${details}` : ""}\n`
            + `**${comment ? "Comment" : "Post"}:** ${link}\n> ${quoted}\n_Resolve it in the mod: MineFeed → Moderation._`,
          allowed_mentions: { parse: [] },
        },
      });
    } catch (e) {
      console.error("report message failed", e.message); // the report is saved anyway
    }
  }
  return { ok: true };
}

// ---------- queue ----------

/** GET /v2/mod/reports — open reports grouped by post/comment, newest first. */
export async function listReports(env, account) {
  const s = await scope(env, account);
  if (!s) throw new HttpError(403, "not_mod", "Только для модераторов");
  const { results: groups } = await env.DB.prepare(
    `SELECT r.post_id, r.comment_id, r.space, COUNT(*) AS n, MAX(r.created_at) AS last, GROUP_CONCAT(r.reason) AS reasons
     FROM reports r WHERE r.status = 'open'${s.sql}
     GROUP BY r.post_id, r.comment_id ORDER BY last DESC LIMIT 50`
  ).bind(...s.binds).all();
  let names = s.names;
  if (!names) {
    const ids = [...new Set(groups.map((g) => g.space).filter((x) => x !== "global"))];
    names = {};
    if (ids.length) {
      const { results } = await env.DB.prepare(`SELECT id, name FROM communities WHERE id IN (${ids.map(() => "?").join(",")})`).bind(...ids).all();
      for (const c of results) names[c.id] = c.name;
    }
  }
  const items = [];
  for (const g of groups) {
    const row = g.comment_id
      ? await env.DB.prepare(`${F.COMMENT_SELECT} WHERE c.id = ?`).bind(g.comment_id).first()
      : await env.DB.prepare(`${F.POST_SELECT} WHERE p.id = ?`).bind(g.post_id).first();
    if (!row || row.deleted) {
      await closeReports(env, { postId: g.post_id, commentId: g.comment_id }, "done", "gone", null, Date.now());
      continue;
    }
    await freshen(env, [row], g.comment_id ? "comments" : "posts");
    const out = g.comment_id ? F.commentOut(row) : F.postOut(row);
    const { results: who } = await env.DB.prepare(
      `SELECT a.tag, r.details, r.snapshot FROM reports r JOIN accounts a ON a.id = r.reporter_id
       WHERE r.post_id = ? AND r.comment_id IS ? AND r.status = 'open' ORDER BY r.created_at DESC LIMIT 5`
    ).bind(g.post_id, g.comment_id).all();
    const target = row.author_id
      ? await env.DB.prepare("SELECT moderator, banned FROM accounts WHERE id = ?").bind(row.author_id).first() : null;
    items.push({
      postId: g.post_id,
      commentId: g.comment_id,
      space: g.space,
      spaceName: g.space === "global" ? null : names[g.space] || "?",
      count: g.n,
      lastAt: g.last,
      reasons: [...new Set(String(g.reasons || "").split(","))].filter(Boolean),
      reporters: who.map((w) => w.tag),
      details: who.map((w) => w.details).filter(Boolean).slice(0, 3),
      target: { text: out.text, image: out.image, author: out.author, createdAt: out.createdAt, type: out.type || null,
        // edited after the report: what the reporters saw
        original: (who.map((w) => w.snapshot).find((t) => t != null && t !== out.text)) ?? null },
      canBan: !!account.moderator && !!target && !target.moderator && !target.banned && row.author_id !== account.id,
      canKick: g.space !== "global" && !!row.author_id && row.author_id !== account.id,
    });
  }
  return { reports: items };
}

/** POST /v2/mod/resolve {postId, commentId?, action: dismiss|delete|kick} */
export async function resolve(env, account, body, now) {
  const post = await env.DB.prepare("SELECT * FROM posts WHERE id = ?").bind(String(body.postId || "")).first();
  if (!post) throw new HttpError(404, "no_post", "Пост не найден");
  if (!(await canModerate(env, account, post.space))) throw new HttpError(403, "forbidden", "Только для модераторов");
  const commentId = body.commentId ? String(body.commentId) : null;
  let targetId = post.author_id;
  if (commentId) {
    const c = await env.DB.prepare("SELECT author_id FROM comments WHERE id = ? AND post_id = ?").bind(commentId, post.id).first();
    if (!c) throw new HttpError(404, "no_comment", "Комментарий не найден");
    targetId = c.author_id;
  }
  const where = { postId: post.id, commentId };
  const f = { target: targetId, postId: post.id, commentId, space: post.space };
  switch (body.action) {
    case "dismiss":
      await closeReports(env, where, "dismissed", "dismissed", account.id, now);
      await modLog(env, account.id, "dismiss", f, now);
      break;
    case "delete":
      if (commentId) await F.deleteComment(env, account, commentId, now);
      else if (!post.deleted) await F.deletePost(env, account, post.id, now);
      await closeReports(env, where, "done", "deleted", account.id, now);
      break;
    case "kick":
      if (post.space === "global" || !targetId) throw new HttpError(400, "bad_action", "Исключить можно только из сообщества");
      await C.kick(env, account, post.space, targetId, true, now);
      await env.DB.prepare(
        "UPDATE reports SET status = 'done', action = 'kicked', resolved_by = ?, resolved_at = ? WHERE target_id = ? AND space = ? AND status = 'open'"
      ).bind(account.id, now, targetId, post.space).run();
      await modLog(env, account.id, "kick", f, now);
      break;
    default:
      throw new HttpError(400, "bad_action", "Неизвестное действие");
  }
  return { ok: true };
}

// ---------- bans ----------

/** POST /v2/users/:id/ban {days: 1|7|30|0, reason, postId?, commentId?, deleteContent?} — server moderators. */
export async function ban(env, account, id, body, now) {
  if (!account.moderator) throw new HttpError(403, "forbidden", "Только для модераторов сервера");
  const who = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(String(id)).first();
  if (!who) throw new HttpError(404, "no_user", "Игрок не найден");
  if (who.id === account.id) throw new HttpError(400, "self", "Себя забанить нельзя");
  if (who.moderator) throw new HttpError(403, "is_mod", "Модератора забанить нельзя");
  const days = Number(body.days);
  if (!BAN_DAYS.includes(days)) throw new HttpError(400, "bad_days", "Срок: 1, 7, 30 дней или навсегда");
  const until = days ? now + days * DAY : null;
  const reason = note(body.reason, 200) || null;
  await env.DB.prepare("UPDATE accounts SET banned = 1, ban_until = ?, ban_reason = ? WHERE id = ?").bind(until, reason, who.id).run();
  if (body.postId && body.deleteContent) {
    try {
      if (body.commentId) await F.deleteComment(env, account, String(body.commentId), now);
      else await F.deletePost(env, account, String(body.postId), now);
    } catch (e) {
      if (!(e instanceof HttpError) || e.status !== 404) throw e;
    }
  }
  await env.DB.prepare(
    "UPDATE reports SET status = 'done', action = 'banned', resolved_by = ?, resolved_at = ? WHERE target_id = ? AND status = 'open'"
  ).bind(account.id, now, who.id).run();
  await modLog(env, account.id, "ban", { target: who.id, postId: body.postId || null, commentId: body.commentId || null,
    reason: `${days ? days + "d" : "forever"}${reason ? ": " + reason : ""}` }, now);
  return { ok: true, until };
}

/** DELETE /v2/users/:id/ban — server moderators. */
export async function unban(env, account, id, now) {
  if (!account.moderator) throw new HttpError(403, "forbidden", "Только для модераторов сервера");
  const r = await env.DB.prepare("UPDATE accounts SET banned = 0, ban_until = NULL, ban_reason = NULL WHERE id = ? AND banned = 1").bind(String(id)).run();
  if (!r.meta.changes) throw new HttpError(404, "not_banned", "Игрок не забанен");
  await modLog(env, account.id, "unban", { target: String(id) }, now);
  return { ok: true };
}

/** Cron: temporary bans that are over. */
export async function expireBans(env, now) {
  await env.DB.prepare("UPDATE accounts SET banned = 0, ban_until = NULL, ban_reason = NULL WHERE banned = 1 AND ban_until IS NOT NULL AND ban_until <= ?")
    .bind(now).run();
}
