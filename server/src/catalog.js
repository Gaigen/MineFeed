// Only the Discord user linked to the owner can publish their own community.
// A provided invite must belong to that community and still work.
import { HttpError } from './util.js';
import { normalizeCode } from './communities.js';

export async function lookup(env, body, now) {
  const discordId = String(body.discordId || '');
  const owner = await env.DB.prepare('SELECT id FROM accounts WHERE discord_id = ? AND banned = 0').bind(discordId).first();
  if (!owner) throw new HttpError(403, 'not_linked', 'Link your Discord to an active MineFeed profile first.');
  const c = await env.DB.prepare('SELECT id, name, description, about, links, nsfw FROM communities WHERE owner_id = ? AND deleted = 0').bind(owner.id).first();
  if (!c) throw new HttpError(404, 'no_community', 'You do not own an active community.');
  if (body.code == null) return { community: { id: c.id, name: c.name } };
  const code = normalizeCode(body.code);
  const invite = await env.DB.prepare('SELECT code, expires_at, max_uses, uses, approval FROM invite_codes WHERE code = ? AND community_id = ? AND revoked = 0').bind(code, c.id).first();
  if (!invite || (invite.expires_at != null && invite.expires_at <= now) || (invite.max_uses != null && invite.uses >= invite.max_uses)) {
    throw new HttpError(400, 'invalid_invite', 'That invite code is not active for your community.');
  }
  return { community: { id: c.id, name: c.name, description: c.description || '', about: c.about || '', links: c.links || '', nsfw: !!c.nsfw }, code: invite.code, approval: !!invite.approval };
}
