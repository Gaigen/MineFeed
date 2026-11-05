// Pictures in posts. Files live in Discord (free storage): the mod uploads a PNG/JPEG with the post,
// the webhook attaches it, D1 keeps [{url, w, h}]. Discord CDN links are signed and expire (~24h),
// so links are refreshed with the bot token when a post is read.
import { HttpError } from "./util.js";
import { dapi } from "./dapi.js";

export const MAX_IMAGE_BYTES = 1_500_000;
export const MAX_SIDE = 4096;
const REFRESH_MARGIN_MS = 2 * 3_600_000;
const CDN = /^https:\/\/(cdn\.discordapp\.com|media\.discordapp\.net)\/attachments\//;

function b64decode(s) {
  if (typeof s !== "string" || !/^[A-Za-z0-9+/=\s]+$/.test(s)) throw new HttpError(400, "bad_image", "Картинка повреждена");
  const bin = atob(s.replace(/\s+/g, ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** {w, h, type, ext} for PNG/JPEG bytes, else null. */
export function sniff(b) {
  if (b.length > 24 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) {
    const w = (b[16] << 24 | b[17] << 16 | b[18] << 8 | b[19]) >>> 0;
    const h = (b[20] << 24 | b[21] << 16 | b[22] << 8 | b[23]) >>> 0;
    return { w, h, type: "image/png", ext: "png" };
  }
  if (b.length > 4 && b[0] === 0xff && b[1] === 0xd8) {
    let i = 2;
    while (i + 9 < b.length) {
      if (b[i] !== 0xff) return null;
      const m = b[i + 1];
      if (m === 0xff) { i++; continue; }
      if (m === 0x01 || (m >= 0xd0 && m <= 0xd8)) { i += 2; continue; }
      if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) {
        return { h: b[i + 5] << 8 | b[i + 6], w: b[i + 7] << 8 | b[i + 8], type: "image/jpeg", ext: "jpg" };
      }
      i += 2 + (b[i + 2] << 8 | b[i + 3]);
    }
  }
  return null;
}

/** Validates {data: base64, type} from the mod. Returns a file for dapi(). */
export function parseUpload(img) {
  if (!img || typeof img !== "object") throw new HttpError(400, "bad_image", "Картинка повреждена");
  const data = b64decode(img.data);
  if (data.length > MAX_IMAGE_BYTES) throw new HttpError(413, "image_too_large", "Картинка слишком большая");
  const info = sniff(data);
  if (!info) throw new HttpError(400, "bad_image", "Поддерживаются только PNG и JPEG");
  if (!info.w || !info.h || info.w > MAX_SIDE || info.h > MAX_SIDE) throw new HttpError(400, "bad_image", "Неподходящий размер картинки");
  return { name: `screenshot.${info.ext}`, type: info.type, data, w: info.w, h: info.h };
}

/** Discord message attachments -> stored JSON (or null). */
export function fromAttachments(atts) {
  const list = (atts || [])
    .filter((a) => /^image\/(png|jpeg|gif)/.test(a.content_type || "") && a.width && a.height)
    .slice(0, 4)
    .map((a) => ({ url: a.url, w: a.width, h: a.height }));
  return list.length ? JSON.stringify(list) : null;
}

function expiresAt(url) {
  try {
    const ex = new URL(url).searchParams.get("ex");
    return ex ? parseInt(ex, 16) * 1000 : 0;
  } catch {
    return 0;
  }
}

/**
 * Refreshes expired Discord links in rows (posts or comments) in place and saves them.
 * Never fails the read: on errors the old links are kept.
 */
export async function freshen(env, rows, table, now = Date.now()) {
  const stale = [];
  for (const r of rows) {
    if (!r || r.deleted || !r.image) continue;
    let list;
    try { list = JSON.parse(r.image); } catch { continue; }
    if (!Array.isArray(list)) continue;
    if (list.some((i) => CDN.test(i.url || "") && expiresAt(i.url) < now + REFRESH_MARGIN_MS)) stale.push({ r, list });
  }
  if (!stale.length || !env.DISCORD_BOT_TOKEN) return rows;
  const urls = [...new Set(stale.flatMap(({ list }) => list.map((i) => i.url).filter((u) => CDN.test(u))))].slice(0, 50);
  let map;
  try {
    const res = await dapi(env, "POST", "/attachments/refresh-urls", { body: { attachment_urls: urls } });
    map = new Map((res?.refreshed_urls || []).map((x) => [x.original, x.refreshed]));
  } catch (e) {
    console.error("refresh-urls", e.message);
    return rows;
  }
  const updates = [];
  for (const { r, list } of stale) {
    let changed = false;
    for (const i of list) {
      const n = map.get(i.url);
      if (n && n !== i.url) { i.url = n; changed = true; }
    }
    if (changed) {
      r.image = JSON.stringify(list);
      updates.push(env.DB.prepare(`UPDATE ${table} SET image = ? WHERE id = ?`).bind(r.image, r.id));
    }
  }
  if (updates.length) await env.DB.batch(updates);
  return rows;
}
