// Community applications in Discord: the /community command opens a form, staff press Approve/Reject
// on the message the Worker posts to the applications channel. All decisions are made by the Worker;
// this file only turns interactions into Worker calls.
const EPHEMERAL = 64;
const ADMINISTRATOR = 1n << 3n;

export const COMMAND = {
  name: "community",
  name_localizations: { ru: "сообщество" },
  description: "Apply for your own MineFeed community",
  description_localizations: { ru: "Подать заявку на своё сообщество в MineFeed" },
  type: 1,
  dm_permission: false,
};

const T = {
  en: {
    not_linked: "First link this Discord to your MineFeed account: in the game open MineFeed → Profile → Discord.",
    already_applied: "Your application is already sent — wait for the answer, you will get a DM.",
    limit: "You already have a community.",
    rate_limited: "Too many applications today, try again tomorrow.",
    applications_off: "Applications are closed right now.",
    bad_name: "Name: 3–32 characters, without «discord», @everyone or @here.",
    bad_description: "Short description: up to 200 characters.",
    failed: "Something went wrong, try again later.",
    sent: (n) => `✅ Application for «${n}» sent! Server staff will review it, the answer comes in a DM and in the game.`,
    title: "MineFeed community", name: "Name", description: "Short description (shown in the game)",
    about: "About the project", links: "Links (site, map, socials)",
    aboutHint: "What is it, who is it for, what are you building…",
  },
  ru: {
    not_linked: "Сначала привяжи этот Discord к аккаунту MineFeed: в игре открой MineFeed → Профиль → Discord.",
    already_applied: "Заявка уже отправлена — дождись ответа, он придёт в личные сообщения.",
    limit: "У тебя уже есть сообщество.",
    rate_limited: "Слишком много заявок за сегодня, попробуй завтра.",
    applications_off: "Заявки сейчас не принимаются.",
    bad_name: "Название: от 3 до 32 символов, без «discord», @everyone и @here.",
    bad_description: "Краткое описание: до 200 символов.",
    failed: "Что-то пошло не так, попробуй позже.",
    sent: (n) => `✅ Заявка на «${n}» отправлена! Её рассмотрят модераторы, ответ придёт в личные сообщения и в игре.`,
    title: "Сообщество MineFeed", name: "Название", description: "Краткое описание (видно в игре)",
    about: "О проекте", links: "Ссылки (сайт, карта, соцсети)",
    aboutHint: "Что это, для кого, что строите…",
  },
};
export const lang = (locale) => (/^ru/i.test(String(locale || "")) ? "ru" : "en");
const t = (i) => T[lang(i.locale)];
const say = (i, code) => t(i)[code] || t(i).failed;

const input = (custom_id, label, { style = 1, min = 0, max, required = false, placeholder } = {}) => ({
  type: 1,
  components: [{ type: 4, custom_id, label, style, min_length: min, max_length: max, required, ...(placeholder ? { placeholder } : {}) }],
});

export function applicationModal(locale) {
  const s = T[lang(locale)];
  return {
    custom_id: "mfapp:form",
    title: s.title,
    components: [
      input("name", s.name, { min: 3, max: 32, required: true }),
      input("description", s.description, { max: 200 }),
      input("about", s.about, { style: 2, max: 1000, placeholder: s.aboutHint }),
      input("links", s.links, { max: 300 }),
    ],
  };
}

export const rejectModal = (id) => ({
  custom_id: `mfapp:reason:${id}`,
  title: "Reject application",
  components: [input("reason", "Reason (sent to the player)", { style: 2, max: 300 })],
});

/** Staff = the MineFeed Moderator role or Administrator. */
export function isStaff(interaction, modRoleId) {
  const perms = interaction.memberPermissions;
  if (perms && typeof perms.has === "function" && perms.has(ADMINISTRATOR)) return true;
  const roles = interaction.member && interaction.member.roles;
  if (!modRoleId || !roles) return false;
  if (Array.isArray(roles)) return roles.includes(modRoleId);
  return !!(roles.cache && roles.cache.has(modRoleId));
}

export function workerClient({ url, secret, fetchImpl = fetch }) {
  const base = url.replace(/\/+$/, "");
  return async (path, body) => {
    const res = await fetchImpl(base + path, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${secret}` },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(60_000),
    });
    let data = {};
    try { data = await res.json(); } catch { /* empty */ }
    return { status: res.status, data };
  };
}

export function makeApplications({ call, modRoleId, log = console }) {
  const ephemeral = (content) => ({ content, flags: EPHEMERAL, allowedMentions: { parse: [] } });

  async function decide(i, id, approve, reason) {
    await i.deferUpdate();
    const r = await call(`/v2/bot/apply/${id}/decide`, { approve, staffId: i.user.id, reason: reason || null });
    if (r.status >= 300) {
      await i.followUp(ephemeral(`❌ ${r.data.message || r.data.code || "HTTP " + r.status}`));
    } else if (!r.data.ok) {
      await i.followUp(ephemeral(`Already ${r.data.status}.`));
    } else if (approve && r.data.community) {
      await i.followUp(ephemeral(`✅ Community «${r.data.community.name}» created.`));
    }
  }

  return async function handle(i) {
    try {
      if (i.isChatInputCommand && i.isChatInputCommand() && i.commandName === COMMAND.name) {
        const r = await call("/v2/bot/apply/check", { discordId: i.user.id });
        if (r.status >= 300 || !r.data.ok) return i.reply(ephemeral(say(i, r.data.code)));
        return i.showModal(applicationModal(i.locale));
      }
      if (i.isModalSubmit && i.isModalSubmit() && i.customId === "mfapp:form") {
        const f = (k) => { try { return i.fields.getTextInputValue(k) || ""; } catch { return ""; } };
        const body = { discordId: i.user.id, name: f("name"), description: f("description"), about: f("about"), links: f("links"), locale: i.locale };
        const r = await call("/v2/bot/apply", body);
        if (r.status >= 300) return i.reply(ephemeral(say(i, r.data.code)));
        return i.reply(ephemeral(t(i).sent(r.data.application.name)));
      }
      const m = /^mfapp:(approve|reject|reason):([a-z0-9]{6,16})$/.exec(i.customId || "");
      if (!m) return;
      if (!isStaff(i, modRoleId)) return i.reply(ephemeral("Only MineFeed moderators can decide on applications."));
      const [, action, id] = m;
      if (action === "approve") return decide(i, id, true);
      if (action === "reject") return i.showModal(rejectModal(id));
      if (action === "reason") {
        let reason = "";
        try { reason = i.fields.getTextInputValue("reason") || ""; } catch { /* optional */ }
        return decide(i, id, false, reason);
      }
    } catch (e) {
      log.error("interaction failed:", e.message);
      try {
        const msg = ephemeral(say(i, "failed"));
        if (i.deferred || i.replied) await i.followUp(msg); else await i.reply(msg);
      } catch { /* the interaction expired */ }
    }
  };
}
