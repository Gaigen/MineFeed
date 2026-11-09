// Incremental release announcements with a separate durable state per Discord channel.
import { group, buildMessage, footerOf, versionSort, changelog } from './releases.js';
import { fromGithub, fromModrinth, fromCurseforge } from './releases.js';

const unique = (a) => [...new Set(a.filter(Boolean))];
const keyOf = (e) => `${e.source}:${e.url}`;
const sorted = (a) => [...a].sort();

// Parse the compact Minecraft field written by older versions of the bot.
export function parseMcField(value = '') {
  const result = [];
  for (const part of value.split('·').map(s => s.trim())) {
    const range = /^(\d+\.\d+)(?:\.(\d+))?\s*[–-]\s*(\d+\.\d+)(?:\.(\d+))?$/.exec(part);
    if (range && range[1] === range[3]) {
      const start = Number(range[2] || 0), end = Number(range[4] || 0);
      if (end >= start && end - start <= 100) {
        for (let i = start; i <= end; i++) result.push(i === 0 && !range[2] ? range[1] : `${range[1]}.${i}`);
      }
    } else if (/^\d+\.\d+(?:\.\d+)?$/.test(part)) result.push(part);
  }
  return unique(result);
}

function coverage(entries, legacy, project) {
  const values = new Set();
  const add = (mc, loaders) => {
    const mcs = mc.length ? mc : ['?'];
    const ls = loaders.length ? loaders : ['?'];
    for (const m of mcs) for (const l of ls) values.add(`${m}|${l}`);
  };
  if (legacy) add(legacy.mc, legacy.loaders);
  for (const e of entries) add(e.mc.length ? e.mc : project.mc, e.loaders.length ? e.loaders : project.loaders);
  return values;
}
function combineEntries(old, fresh) {
  const map = new Map(old.map(e => [keyOf(e), e]));
  for (const e of fresh) {
    const prior = map.get(keyOf(e));
    // An upstream response can become incomplete: never erase known coverage or URLs.
    map.set(keyOf(e), prior ? { ...prior, ...e, mc: unique(prior.mc.concat(e.mc)), loaders: unique(prior.loaders.concat(e.loaders)) } : e);
  }
  return [...map.values()].sort((a, b) => keyOf(a).localeCompare(keyOf(b)));
}
function renderGroup(project, entries, legacy) {
  const g = group(entries)[0];
  if (!g) return null;
  if (legacy) {
    g.mc = unique(g.mc.concat(legacy.mc));
    g.loaders = unique(g.loaders.concat(legacy.loaders));
  }
  return g;
}
function missingCoverage(before, after) {
  // An unknown loader is less specific than any known loader for that MC.
  return [...after].some(v => {
    if (before.has(v)) return false;
    const [mc, loader] = v.split('|');
    return loader !== '?' || ![...before].some(x => x.startsWith(`${mc}|`));
  });
}

/** stateStore.load/save, channel.recentMessages/send/edit. All Discord effects are sequential. */
export function makeUpdatingAnnouncer({ fetchJson, targets, stateStore, roles = {}, log = console, projects, now = () => Date.now() }) {
  const etags = new Map(), cache = new Map();
  let state, running = false;
  async function get(url) {
    const r = await fetchJson(url, etags.has(url) ? { 'If-None-Match': etags.get(url) } : {});
    if (r.status === 304 && cache.has(url)) return cache.get(url);
    if (r.status !== 200) throw new Error(`${url} -> ${r.status}`);
    if (r.etag) etags.set(url, r.etag);
    cache.set(url, r.data);
    return r.data;
  }
  async function collect(p) {
    const requests = [];
    if (p.github) requests.push([`https://api.github.com/repos/${p.github}/releases?per_page=50`, d => fromGithub(p, d)]);
    if (p.modrinth) requests.push([`https://api.modrinth.com/v2/project/${p.modrinth}/version`, d => fromModrinth(p, d)]);
    if (p.curseforge) requests.push([`https://api.cfwidget.com/minecraft/mc-mods/${p.curseforge}`, d => fromCurseforge(p, d)]);
    const results = await Promise.allSettled(requests.map(async ([url, convert]) => convert(await get(url))));
    let complete = true;
    const entries = [];
    for (const r of results) {
      if (r.status === 'fulfilled') entries.push(...r.value);
      else if (!/-> 404$/.test(r.reason?.message || '')) { complete = false; log.warn('releases:', r.reason?.message); }
    }
    return { entries, complete };
  }
  async function textFor(p, g) {
    const c = g.notes.find(n => n.compare)?.compare;
    let commits = [];
    if (c && p.github) {
      try {
        const d = await get(`https://api.github.com/repos/${p.github}/compare/${c.base}...${c.head}`);
        commits = (d.commits || []).map(x => x.commit?.message || '');
      } catch (e) { log.warn('releases: compare', e.message); }
    }
    return changelog(g.notes, commits, g.version);
  }
  return async function tick() {
    if (running) return [];
    running = true;
    try {
      state ||= await stateStore.load();
      const fetched = new Map(await Promise.all(projects.map(async p => [p.key, await collect(p)])));
      const actions = [];
      for (const t of targets) {
        const ts = state.targets[t.id] ||= { since: t.since, projects: {} };
        const cutoff = t.dynamicSince ? ts.since : t.since;
        let recent; // Fetch only when bootstrapping or recovering an untracked message.
        const recentMessages = async () => recent ||= await t.channel.recentMessages();
        for (const p of projects.filter(p => !t.projects || t.projects.includes(p.key))) {
          const source = fetched.get(p.key);
          const ps = ts.projects[p.key] ||= { initialized: false, versions: {} };
          // Never baseline an incomplete snapshot: it would turn older, temporarily
          // missing releases into false "new" announcements on the next poll.
          if (!ps.initialized && !source.complete) continue;
          const mature = source.entries.filter(e => Number.isFinite(Date.parse(e.published)) && now() - Date.parse(e.published) >= 5 * 60_000);
          const byVersion = new Map();
          for (const e of mature) (byVersion.get(e.version) || (byVersion.set(e.version, []), byVersion.get(e.version))).push(e);
          for (const version of [...byVersion.keys()].sort(versionSort)) {
            const fresh = byVersion.get(version);
            let rec = ps.versions[version];
            if (!rec) {
              const match = (await recentMessages()).find(m => m.footer === footerOf(p, version));
              rec = { messageId: match?.id || null, entries: [], legacy: match ? { mc: parseMcField(match.minecraft), loaders: (match.loaders || '').split('·').map(s => s.trim()).filter(Boolean) } : null };
              // Historical unseen releases are baseline only; a release or build published
              // after the configured cutoff remains eligible even if the version is old.
              const priorProjectMessages = (await recentMessages()).filter(m => m.footer?.startsWith(`${p.name} `));
              const latestHistoryPost = t.history ? Math.max(0, ...priorProjectMessages.map(m => m.createdTimestamp || 0)) : 0;
              const eligible = fresh.some(e => Date.parse(e.published) >= cutoff && Date.parse(e.published) > latestHistoryPost);
              if (!match && !eligible) {
                rec.entries = combineEntries([], fresh);
                ps.versions[version] = rec;
                await stateStore.save(state);
                continue;
              }
            }
            const before = coverage(rec.entries, rec.legacy, p);
            const merged = combineEntries(rec.entries, fresh);
            const after = coverage(merged, rec.legacy, p);
            const changedCoverage = missingCoverage(before, after);
            const changedEntries = JSON.stringify(rec.entries) !== JSON.stringify(merged);
            const g = renderGroup(p, merged, rec.legacy);
            let messageId = rec.messageId;
            // A previous send may have succeeded immediately before a process crash
            // or state write failure. Recover the post rather than pinging twice.
            if (!messageId && ps.versions[version] && changedCoverage) {
              const match = (await recentMessages()).find(m => m.footer === footerOf(p, version));
              if (match) messageId = match.id;
            }
            let mustReplace = false;
            // With no known post, a new Minecraft/loader combination warrants a new one.
            // A new project version also warrants one, including metadata-only releases.
            const isNewVersion = !ps.versions[version] && !messageId;
            if (messageId && (changedCoverage || (ps.versions[version] && changedEntries))) {
              const msg = buildMessage(p, g, { text: '', fallback: t.fallback ?? null, headline: !!t.ping });
              try {
                await t.channel.edit(messageId, msg);
                actions.push(`${t.name}: updated ${footerOf(p, version)}`);
              } catch (e) {
                // Discord 10008 means the message was deleted. Other errors may be
                // transient or permissions-related; do not create duplicate posts.
                if (![10008, 50005].includes(Number(e.code))) throw e;
                const previousId = messageId;
                // Recover a replacement sent just before a crash/state write failure.
                const recovered = (await recentMessages()).find(m => m.footer === footerOf(p, version) && m.id !== previousId);
                messageId = recovered?.id || null;
                if (messageId) {
                  await t.channel.edit(messageId, msg);
                  actions.push(`${t.name}: updated ${footerOf(p, version)}`);
                } else mustReplace = true;
              }
            }
            if (!messageId && (isNewVersion || changedCoverage || mustReplace)) {
              const msg = buildMessage(p, g, { roleId: t.ping ? roles[p.key] : null, text: await textFor(p, g), fallback: t.fallback ?? null, headline: !!t.ping });
              const posted = await t.channel.send(msg);
              if (!posted?.id) throw new Error('Discord send did not return a message ID');
              messageId = posted.id;
              actions.push(`${t.name}: posted ${footerOf(p, version)}`);
            }
            ps.versions[version] = { messageId, entries: merged, legacy: rec.legacy };
            await stateStore.save(state);
          }
          ps.initialized = true;
          await stateStore.save(state);
        }
      }
      for (const action of actions) log.log(action);
      return actions;
    } catch (e) {
      state = undefined; // Reload the last durable snapshot after any failure.
      throw e;
    } finally { running = false; }
  };
}
