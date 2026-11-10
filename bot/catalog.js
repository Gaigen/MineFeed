// The owner explicitly previews and confirms before a private invite becomes public.
import { randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdir, rename } from 'node:fs/promises';
import { dirname } from 'node:path';

const EPHEMERAL = 64;
export const CATALOG_COMMANDS = [
  { name: 'community-publish', description: 'Publish or update your community in the Discord directory', type: 1, dm_permission: false,
    options: [{ type: 3, name: 'code', description: 'An active invite code for your community', required: true }] },
  { name: 'community-unpublish', description: 'Remove your community announcement from the directory', type: 1, dm_permission: false },
];

export function catalogState(path) {
  return {
    async load() {
      try { const s = JSON.parse(await readFile(path, 'utf8')); if (s.version !== 1 || !s.posts || typeof s.posts !== 'object') throw new Error('Invalid catalog state'); return s; }
      catch (e) { if (e.code === 'ENOENT') return { version: 1, posts: {} }; throw e; }
    },
    async save(s) { await mkdir(dirname(path), { recursive: true }); const temp = path + '.tmp'; await writeFile(temp, JSON.stringify(s, null, 2) + '\n', { mode: 0o600 }); await rename(temp, path); },
  };
}
const ep = (content) => ({ content, flags: EPHEMERAL, allowedMentions: { parse: [] } });
const text = (s, n) => [...String(s || '').replace(/@/g, '@\u200b').trim()].slice(0, n).join('');
export function catalogCard(d) {
  const c = d.community;
  const title = `${c.nsfw && !/18\s*\+/.test(c.name) ? '[18+] ' : ''}${text(c.name, 80)}`;
  const lines = [c.description && text(c.description, 200), c.about && `**About**\n${text(c.about, 1000)}`, c.links && `**Links**\n${text(c.links, 300)}`, `**Invite code:** \`${d.code}\``, d.approval ? '**Joining:** requires approval' : '**Joining:** instant', 'In MineFeed, choose **Enter code** to join.'];
  return { title, content: lines.filter(Boolean).join('\n\n') };
}
export function makeCatalog({ call, state, forum, log = console, now = Date.now }) {
  const pending = new Map();
  let queue = Promise.resolve();
  const exclusive = (fn) => { const next = queue.then(fn, fn); queue = next.catch(() => {}); return next; };
  const lookup = (i, code) => call('/v2/bot/catalog', { discordId: i.user.id, ...(code != null ? { code } : {}) });
  const error = (r) => ({ not_linked: 'Link Discord to your MineFeed profile first.', no_community: 'You need to own an approved community.', invalid_invite: 'That code is not active for your community. Create or check it in MineFeed.' }[r?.data?.code] || 'Could not check your community. Please try again.');
  return async function handle(i) {
    const command = i.isChatInputCommand?.() && ['community-publish', 'community-unpublish'].includes(i.commandName);
    const button = i.isButton?.() && String(i.customId || '').startsWith('mfc:');
    if (!command && !button) return false;
    try {
      if (!forum) { await i.reply(ep('The community directory is not configured yet.')); return true; }
      if (command && i.commandName === 'community-publish') {
        await i.deferReply({ flags: EPHEMERAL });
        const code = i.options.getString('code', true);
        const r = await lookup(i, code);
        if (r.status >= 300) { await i.editReply(error(r)); return true; }
        const nonce = randomBytes(12).toString('hex');
        for (const [k, v] of pending) if (v.expires <= now() || v.user === i.user.id) pending.delete(k);
        pending.set(nonce, { user: i.user.id, data: r.data, expires: now() + 10 * 60_000 });
        const card = catalogCard(r.data);
        await i.editReply({ content: `**Preview — this text and invite code will be public.**\n\n${card.content}\n\nConfirm to publish or update **${card.title}**.`, allowedMentions: { parse: [] }, components: [{ type: 1, components: [{ type: 2, style: 3, custom_id: `mfc:publish:${nonce}`, label: 'Publish' }, { type: 2, style: 2, custom_id: `mfc:cancel:${nonce}`, label: 'Cancel' }] }] });
        return true;
      }
      if (command && i.commandName === 'community-unpublish') {
        await i.deferReply({ flags: EPHEMERAL });
        const r = await lookup(i);
        if (r.status >= 300) { await i.editReply(error(r)); return true; }
        const result = await exclusive(async () => {
          const s = await state.load(), id = s.posts[r.data.community.id];
          if (!id) return 'No announcement found.';
          try { await forum.remove(id); } catch (e) { if (![10003, 10008].includes(Number(e.code))) throw e; }
          delete s.posts[r.data.community.id]; await state.save(s);
          return 'Announcement removed. The invite code remains active; revoke it in MineFeed if needed.';
        });
        await i.editReply(result); return true;
      }
      const match = /^mfc:(publish|cancel):([a-f0-9]{24})$/.exec(i.customId || '');
      if (!match) { await i.reply(ep('This confirmation is invalid.')); return true; }
      const p = pending.get(match[2]);
      if (!p || p.user !== i.user.id || p.expires <= now()) { await i.reply(ep('This confirmation expired. Run the command again.')); return true; }
      pending.delete(match[2]);
      await i.deferUpdate();
      if (match[1] === 'cancel') { await i.editReply({ content: 'Cancelled. Nothing was published.', components: [] }); return true; }
      const r = await lookup(i, p.data.code);
      if (r.status >= 300 || JSON.stringify(r.data) !== JSON.stringify(p.data)) {
        await i.editReply({ content: 'The community or invite changed. Run the command again to review it.', components: [] }); return true;
      }
      const url = await exclusive(async () => {
        const s = await state.load(); const cid = r.data.community.id; const card = catalogCard(r.data);
        if (s.posts[cid]) {
          try { return await forum.update(s.posts[cid], card); }
          catch (e) { if (![10003, 10008].includes(Number(e.code))) throw e; delete s.posts[cid]; }
        }
        const post = await forum.create(card);
        s.posts[cid] = post.id;
        try { await state.save(s); } catch (e) { await forum.remove(post.id).catch(() => {}); throw e; }
        return post.url;
      });
      await i.editReply({ content: `Published: ${url}\nThe invite code is public.`, components: [], allowedMentions: { parse: [] } });
      return true;
    } catch (e) {
      log.error('catalog interaction failed:', e.message);
      try { if (i.deferred || i.replied) await i.editReply({ content: 'Could not publish right now. Please try again.', components: [] }); else await i.reply(ep('Could not publish right now. Please try again.')); } catch { /* expired */ }
      return true;
    }
  };
}
