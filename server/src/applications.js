// Community applications: a player applies (from the mod or with /community in Discord),
// server staff press Approve/Reject in the applications channel, the bot forwards the click here.
import { HttpError, randomCode } from "./util.js";
import { rateLimit } from "./accounts.js";
import { dapi } from "./dapi.js";
import * as C from "./communities.js";
import * as F from "./feed.js";
import * as N from "./notify.js";
import { welcomeContent } from "./welcome.js";

const T = {
  en: {
    dmOk: (a) => `✅ Your MineFeed community «${a.name}» is approved and ready. Open MineFeed in the game to invite people.`,
    dmNo: (a) => `❌ Your MineFeed community application «${a.name}» was declined.${a.reason ? "\nReason: " + a.reason : ""}`,
  },
  ru: {
    dmOk: (a) => `✅ Сообщество «${a.name}» в MineFeed одобрено и создано. Открой MineFeed в игре, чтобы пригласить людей.`,
    dmNo: (a) => `❌ Заявка на сообщество «${a.name}» в MineFeed отклонена.${a.reason ? "\nПричина: " + a.reason : ""}`,
  },
};

export const langOf = (raw) => (/^\s*ru/i.test(String(raw || "")) ? "ru" : "en");

function optional(raw, max) {
  return String(raw ?? "").trim() ? F.cleanText(raw, max) : "";
}

export function appOut(a) {
  return {
    id: a.id, name: a.name, description: a.description, status: a.status, reason: a.reason || null,
    communityId: a.community_id || null, createdAt: a.created_at, decidedAt: a.decided_at || null,
  };
}

/** Latest application of the player (any status) or null. */
export async function latest(env, accountId) {
  const a = await env.DB.prepare("SELECT * FROM community_applications WHERE account_id = ? ORDER BY created_at DESC LIMIT 1").bind(accountId).first();
  return a ? appOut(a) : null;
}

async function eligible(env, account) {
  if (!account.discord_id) throw new HttpError(403, "discord_required", "Чтобы подать заявку, привяжи Discord в профиле");
  if (await C.ownsCommunity(env, account.id)) throw new HttpError(409, "limit", "У тебя уже есть сообщество");
  const p = await env.DB.prepare("SELECT 1 AS x FROM community_applications WHERE account_id = ? AND status = 'pending'").bind(account.id).first();
  if (p) throw new HttpError(409, "already_applied", "Заявка уже отправлена — дождись ответа");
}

function staffMessage(a, owner, state) {
  const color = state === "approved" ? 0x55ff55 : state === "rejected" ? 0xff5555 : 0xffaa00;
  const fields = [
    { name: "Applicant", value: `<@${a.discord_id}> · MineFeed **@${owner ? owner.tag : "?"}**`, inline: false },
    ...(a.description ? [{ name: "Description", value: a.description.slice(0, 1024) }] : []),
    ...(a.about ? [{ name: "About", value: a.about.slice(0, 1024) }] : []),
    ...(a.links ? [{ name: "Links", value: a.links.slice(0, 1024) }] : []),
  ];
  if (state === "approved") fields.push({ name: "✅ Approved", value: `by <@${a.decided_by}>` });
  if (state === "rejected") fields.push({ name: "❌ Rejected", value: `by <@${a.decided_by}>${a.reason ? "\n" + a.reason : ""}` });
  return {
    embeds: [{ title: `Community application: ${a.name}`, color, fields, footer: { text: `id ${a.id} · lang ${a.lang}` } }],
    components: state === "pending" ? [{
      type: 1,
      components: [
        { type: 2, style: 3, label: "Approve", custom_id: `mfapp:approve:${a.id}` },
        { type: 2, style: 4, label: "Reject", custom_id: `mfapp:reject:${a.id}` },
      ],
    }] : [],
    allowed_mentions: { parse: [] },
  };
}

/** POST /v2/communities/applications {name, description, about, links, lang} */
export async function submit(env, account, body, lang, now, { checkGuild = true } = {}) {
  await eligible(env, account);
  if (!env.APPLICATIONS_CHANNEL_ID) throw new HttpError(503, "applications_off", "Заявки сейчас не принимаются");
  if (checkGuild) {
    const member = await dapi(env, "GET", `/guilds/${env.DISCORD_GUILD_ID}/members/${account.discord_id}`, { allow404: true });
    if (!member) throw new HttpError(403, "not_in_guild", "Сначала зайди на наш Discord-сервер", { discordUrl: env.DISCORD_INVITE || null });
  }
  const name = C.validName(body.name);
  const description = C.validDescription(body.description);
  const about = optional(body.about, 1000);
  const links = optional(body.links, 300);
  await rateLimit(env, "apply", account.id, now);
  const id = randomCode(8).toLowerCase();
  await env.DB.prepare(
    `INSERT INTO community_applications (id, account_id, discord_id, name, description, about, links, lang, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(id, account.id, account.discord_id, name, description, about, links, langOf(lang), now).run();
  const a = await env.DB.prepare("SELECT * FROM community_applications WHERE id = ?").bind(id).first();
  const msg = await dapi(env, "POST", `/channels/${env.APPLICATIONS_CHANNEL_ID}/messages`, { body: staffMessage(a, account, "pending") });
  await env.DB.prepare("UPDATE community_applications SET staff_message = ? WHERE id = ?").bind(msg.id, id).run();
  return { application: appOut(a) };
}

async function dm(env, discordId, content) {
  try {
    const ch = await dapi(env, "POST", "/users/@me/channels", { body: { recipient_id: discordId } });
    await dapi(env, "POST", `/channels/${ch.id}/messages`, { body: { content, allowed_mentions: { parse: [] } } });
  } catch (e) {
    console.error("dm failed", e.message); // closed DMs are fine, the mod shows a notification anyway
  }
}

// ---------- bot side (BOT_SECRET) ----------

async function byDiscord(env, discordId) {
  return env.DB.prepare("SELECT * FROM accounts WHERE discord_id = ? AND banned = 0").bind(String(discordId || "")).first();
}

/** POST /v2/bot/apply/check {discordId} — may this Discord user open the form? */
export async function botCheck(env, body) {
  const acc = await byDiscord(env, body.discordId);
  if (!acc) return { ok: false, code: "not_linked" };
  try {
    await eligible(env, acc);
  } catch (e) {
    if (e instanceof HttpError) return { ok: false, code: e.code };
    throw e;
  }
  return { ok: true, tag: acc.tag };
}

/** POST /v2/bot/apply {discordId, name, description, about, links, locale} */
export async function botSubmit(env, body, now) {
  const acc = await byDiscord(env, body.discordId);
  if (!acc) throw new HttpError(403, "not_linked", "not linked");
  return submit(env, acc, body, body.locale, now, { checkGuild: false }); // the bot only sees guild members
}

/** POST /v2/bot/apply/:id/decide {approve, staffId, reason} — the bot already checked staff rights. */
export async function decide(env, id, body, now) {
  const a = await env.DB.prepare("SELECT * FROM community_applications WHERE id = ?").bind(String(id)).first();
  if (!a) throw new HttpError(404, "no_application", "Application not found");
  if (a.status !== "pending") return { ok: false, code: "already_decided", status: a.status };
  const owner = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(a.account_id).first();
  const staffId = String(body.staffId || "");
  const t = T[a.lang] || T.en;
  let community = null;

  if (body.approve) {
    if (!owner || owner.banned) throw new HttpError(409, "no_owner", "The applicant's MineFeed account is gone or banned");
    community = (await C.createCommunity(env, owner, { name: a.name, description: a.description, about: a.about, links: a.links, lang: a.lang }, now)).community;
    try {
      const w = await F.publishWelcome(env, community.id, owner, welcomeContent({ ...a, name: community.name, description: community.description }, owner), now);
      await env.DB.prepare("UPDATE communities SET welcome_thread_id = ?, welcome_comment_id = ? WHERE id = ?").bind(w.thread, w.comment, community.id).run();
    } catch (e) {
      console.error("welcome post failed", e.message); // the community itself is ready
    }
  }
  const status = body.approve ? "approved" : "rejected";
  const reason = body.approve ? null : String(body.reason || "").replace(/\s+/g, " ").trim().slice(0, 300) || null;
  await env.DB.prepare("UPDATE community_applications SET status = ?, decided_by = ?, reason = ?, community_id = ?, decided_at = ? WHERE id = ?")
    .bind(status, staffId, reason, community ? community.id : null, now, a.id).run();
  const done = { ...a, status, decided_by: staffId, reason };
  if (owner) {
    await N.add(env, {
      to: owner.id, kind: body.approve ? "app_approved" : "app_rejected", communityId: community ? community.id : null,
      actor: { ext_name: "MineFeed" }, text: body.approve ? a.name : reason || a.name,
    }, now);
  }
  await dm(env, a.discord_id, body.approve ? t.dmOk(done) : t.dmNo(done));
  if (a.staff_message && env.APPLICATIONS_CHANNEL_ID) {
    await dapi(env, "PATCH", `/channels/${env.APPLICATIONS_CHANNEL_ID}/messages/${a.staff_message}`, { body: staffMessage(done, owner, status), allow404: true })
      .catch((e) => console.error("staff message edit failed", e.message));
  }
  return { ok: true, status, community };
}
