export class HttpError extends Error {
  constructor(status, code, message, extra) {
    super(message);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

export function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });
}

export function errorResponse(e) {
  // `error` keeps compatibility with how the mod already shows server errors
  return json({ error: e.message, code: e.code, ...(e.extra || {}) }, e.status);
}

export async function readJson(request, maxBytes = 4096) {
  const text = await request.text();
  if (text.length > maxBytes) throw new HttpError(413, "too_large", "Слишком большой запрос");
  if (!text) return {};
  try {
    const v = JSON.parse(text);
    if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error();
    return v;
  } catch {
    throw new HttpError(400, "bad_json", "Некорректный JSON");
  }
}

const B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I

export function randomBytes(n) {
  const a = new Uint8Array(n);
  crypto.getRandomValues(a);
  return a;
}

export function base64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Human-friendly code like KQ7M-2XHP-9TRA-4WZD-LN8C (100 bits). */
export function recoveryCode() {
  const bytes = randomBytes(20);
  let out = "";
  for (let i = 0; i < 20; i++) {
    out += B32[bytes[i] & 31];
    if (i % 4 === 3 && i < 19) out += "-";
  }
  return out;
}

/** Random chars from the no-0/O/1/I alphabet. */
export function randomCode(n) {
  const bytes = randomBytes(n);
  let out = "";
  for (let i = 0; i < n; i++) out += B32[bytes[i] & 31];
  return out;
}

export function normalizeRecovery(code) {
  return String(code || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
}

export async function sha256Hex(text) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function clientIp(request) {
  return request.headers.get("cf-connecting-ip") || "local";
}

/** "||secret||" (Discord spoiler) -> "▒▒▒" where the text can't be hidden: thread titles, notification quotes. */
export function hideSpoilers(text) {
  return String(text || "").replace(/\|\|[\s\S]+?\|\|/g, "▒▒▒");
}
