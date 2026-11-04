import { HttpError } from "./util.js";

// Same character rules as Discord usernames, so a Discord name always fits as a tag.
export const TAG_RE = /^[a-z0-9_.]{2,32}$/;
export const RESERVED_TAGS = new Set([
  "admin", "administrator", "mod", "moderator", "moderators", "minefeed", "support", "system",
  "staff", "official", "owner", "root", "server", "help", "everyone", "here", "null", "undefined",
  "discord", "mojang", "minecraft", "notch",
]);

export function normalizeTag(raw) {
  return String(raw ?? "").trim().replace(/^@/, "").toLowerCase();
}

/** Returns null if ok, otherwise a user-facing reason. */
export function tagProblem(tag) {
  if (!TAG_RE.test(tag)) return "Тег: 2–32 символа, только латиница a–z, цифры, _ и .";
  if (tag.includes("..")) return "Тег не может содержать две точки подряд";
  if (RESERVED_TAGS.has(tag) || tag.startsWith("minefeed") || tag.startsWith("admin")) return "Этот тег зарезервирован";
  return null;
}

export function validTag(raw) {
  const tag = normalizeTag(raw);
  const p = tagProblem(tag);
  if (p) throw new HttpError(400, "bad_tag", p);
  return tag;
}

// Strip Minecraft formatting (§), control, zero-width and bidi characters.
const STRIP = /[\u0000-\u001f\u007f-\u009f\u00a7\u200b-\u200f\u2028-\u202e\u2060-\u206f\ufeff]/g;

export function validNick(raw) {
  const nick = String(raw ?? "").replace(/\u00a7./gu, "").replace(STRIP, "").replace(/\s+/g, " ").trim();
  const len = [...nick].length;
  if (len < 1 || len > 32) throw new HttpError(400, "bad_nick", "Ник: от 1 до 32 символов");
  return nick;
}
