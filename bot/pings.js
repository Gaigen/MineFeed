// Self-service ping roles: a button with customId "ping:<key>" toggles that role for whoever clicks it.
export const PING_PREFIX = "ping:";

/** "minefeed:123,retroconsole:456" -> {minefeed: "123", retroconsole: "456"} */
export function parseRoles(s) {
  const out = {};
  for (const part of String(s || "").split(",")) {
    const [k, id] = part.split(":").map((x) => (x || "").trim());
    if (/^[a-z0-9_-]+$/.test(k || "") && /^\d{5,25}$/.test(id || "")) out[k] = id;
  }
  return out;
}

const TEXT = {
  en: { on: (r) => `🔔 You'll get a ping as ${r} when a new version is out. Click again to stop.`, off: (r) => `🔕 No more ${r} pings.`, broken: "This button is not set up.", failed: "Couldn't change your roles, try again later." },
  ru: { on: (r) => `🔔 Пингану тебя ролью ${r}, когда выйдет новая версия. Нажми ещё раз, чтобы отключить.`, off: (r) => `🔕 Пинги ${r} отключены.`, broken: "Кнопка не настроена.", failed: "Не получилось поменять роли, попробуй позже." },
};

/** Returns true when the interaction was a ping button (handled here). */
export function makePings({ roles, log = console }) {
  return async function handle(i) {
    if (!i.isButton || !i.isButton() || !String(i.customId || "").startsWith(PING_PREFIX)) return false;
    const t = String(i.locale || "").startsWith("ru") ? TEXT.ru : TEXT.en;
    const reply = (content) => i.reply({ content, flags: 64, allowedMentions: { parse: [] } }).catch(() => {});
    const roleId = roles[i.customId.slice(PING_PREFIX.length)];
    if (!roleId || !i.member?.roles?.cache) { await reply(t.broken); return true; }
    try {
      const has = i.member.roles.cache.has(roleId);
      if (has) await i.member.roles.remove(roleId, "release pings off");
      else await i.member.roles.add(roleId, "release pings on");
      await reply(has ? t.off(`<@&${roleId}>`) : t.on(`<@&${roleId}>`));
    } catch (e) {
      log.error("ping role failed:", e.message);
      await reply(t.failed);
    }
    return true;
  };
}
