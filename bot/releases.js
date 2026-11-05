// Release announcer: polls GitHub, Modrinth and CurseForge for new versions of our mods and posts
// one message per version into the announcements channel, pinging that mod's role.
// Stateless: the channel itself is the memory (footer "<Name> <version>"), plus RELEASES_SINCE.

export const PROJECTS = [
  {
    key: "minefeed", name: "MineFeed", color: 0xffff55, github: "Gaigen/MineFeed", curseforge: "minefeed", modrinth: "minefeed",
    mc: ["1.21.1"], loaders: ["NeoForge"],
    pages: { CurseForge: "https://www.curseforge.com/minecraft/mc-mods/minefeed", Modrinth: "https://modrinth.com/mod/minefeed" },
  },
  {
    key: "retroconsole", name: "RetroConsole", color: 0x55ffff, github: "Gaigen/retroconsole", curseforge: "retroconsole", modrinth: "retroconsole",
    mc: [], loaders: [],
    pages: { CurseForge: "https://www.curseforge.com/minecraft/mc-mods/retroconsole", Modrinth: "https://modrinth.com/mod/retroconsole" },
  },
];

const LOADERS = { neoforge: "NeoForge", fabric: "Fabric", forge: "Forge", quilt: "Quilt" };
const L = "(?:neoforge|fabric|forge|quilt)";
const VERSION = `\\d+(?:\\.\\d+){1,3}(?:-(?!${L}\\b|mc\\d)[a-z][0-9a-z.]*)?`;
const JAR = new RegExp(`^[a-z0-9_]+-(${VERSION})(?:[+-](\\d+(?:\\.\\d+){1,2}))?(?:-(${L}))?\\.jar$`, "i");
const PRE = /(alpha|beta|rc|dev|pre|snapshot)/i;
const uniq = (a) => [...new Set(a.filter(Boolean))];

/** "retroconsole-0.3.3+1.21.1-neoforge.jar" -> {version: "0.3.3", mc: "1.21.1", loader: "NeoForge"} */
export function parseJar(name) {
  const m = JAR.exec(String(name || ""));
  return m ? { version: m[1], mc: m[2] || null, loader: m[3] ? LOADERS[m[3].toLowerCase()] : null } : null;
}

/** "v0.3.3-mc1.21.1" / "0.3.3+1.21.1-fabric" -> "0.3.3" (null if there is no version) */
export function cleanVersion(s) {
  const m = new RegExp(`^v?(${VERSION})`, "i").exec(String(s || ""));
  return m ? m[1] : null;
}

export const isPrerelease = (version) => PRE.test(version || "");

export function fromGithub(project, releases) {
  const out = [];
  for (const r of Array.isArray(releases) ? releases : []) {
    if (r.draft || r.prerelease) continue;
    const jars = (r.assets || []).map((a) => parseJar(a.name)).filter(Boolean);
    const version = jars[0]?.version || cleanVersion(r.tag_name);
    if (!version || isPrerelease(version)) continue;
    const tagMc = /-mc(\d+(?:\.\d+){1,2})$/i.exec(r.tag_name || "")?.[1];
    const compare = /\/compare\/([^\s)]+?)\.\.\.([^\s)]+)/.exec(r.body || "");
    out.push({
      project: project.key, version, source: "github", url: r.html_url, published: r.published_at,
      mc: uniq(jars.map((j) => j.mc).concat(tagMc)), loaders: uniq(jars.map((j) => j.loader)),
      body: r.body || "", compare: compare ? { base: compare[1], head: compare[2] } : null,
    });
  }
  return out;
}

export function fromModrinth(project, versions) {
  return (Array.isArray(versions) ? versions : []).filter((v) => v.version_type === "release").map((v) => ({
    project: project.key, version: cleanVersion(v.version_number), source: "modrinth",
    url: `https://modrinth.com/mod/${project.modrinth}/version/${v.id}`, published: v.date_published,
    mc: v.game_versions || [], loaders: (v.loaders || []).map((l) => LOADERS[l] || l),
  })).filter((e) => e.version && !isPrerelease(e.version));
}

/** cfwidget.com answer for a CurseForge project. */
export function fromCurseforge(project, data) {
  const files = Array.isArray(data?.files) ? data.files : [];
  return files.filter((f) => f.type === "release").map((f) => {
    const jar = parseJar(f.display) || parseJar(f.name); // CurseForge turns "+" into "-" in file names
    const tags = f.versions || [];
    return {
      project: project.key, version: jar?.version || cleanVersion(f.display), source: "curseforge", url: f.url, published: f.uploaded_at,
      mc: tags.filter((t) => /^\d+\.\d+/.test(t)), loaders: tags.map((t) => LOADERS[String(t).toLowerCase()]).filter(Boolean),
    };
  }).filter((e) => e.version && !isPrerelease(e.version));
}

/** One entry per project+version, merged over sources and Minecraft builds. */
export function group(entries) {
  const map = new Map();
  for (const e of entries) {
    const k = `${e.project}@${e.version}`;
    const g = map.get(k) || { project: e.project, version: e.version, mc: [], loaders: [], urls: {}, published: e.published, notes: [] };
    g.mc = uniq(g.mc.concat(e.mc));
    g.loaders = uniq(g.loaders.concat(e.loaders));
    if (!g.urls[e.source]) g.urls[e.source] = e.url;
    if (Date.parse(e.published) < Date.parse(g.published)) g.published = e.published;
    if (e.source === "github") g.notes.push({ body: e.body, compare: e.compare });
    map.set(k, g);
  }
  return [...map.values()];
}

export const footerOf = (project, version) => `${project.name} ${version}`;

/** Versions to announce now: new since `since`, settled for a few minutes (other builds/sites catch up), not posted yet. */
export function pickNew(groups, { since, now, posted, settleMs = 5 * 60_000 }) {
  return groups.filter((g) => {
    const t = Date.parse(g.published);
    const p = PROJECTS.find((x) => x.key === g.project);
    return p && t >= since && now - t >= settleMs && !posted.has(footerOf(p, g.version));
  }).sort((a, b) => versionSort(a.version, b.version) || Date.parse(a.published) - Date.parse(b.published));
}

const nums = (v) => String(v).split(/[^0-9]+/).filter(Boolean).map(Number);
export const versionSort = (a, b) => {
  const x = nums(a), y = nums(b);
  for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0);
  return 0;
};

/** ["1.21.1", "1.21.2", "1.21.3", "26.1.2"] -> "1.21.1 – 1.21.3 · 26.1.2" (runs of 3+ patch versions collapse) */
export function mcRange(list) {
  const sorted = uniq(list).sort(versionSort);
  const out = [];
  for (let i = 0; i < sorted.length;) {
    let j = i;
    const [ma, mi] = nums(sorted[i]);
    while (j + 1 < sorted.length) {
      const [mb, mj, pb] = nums(sorted[j + 1]);
      const [, , pj] = nums(sorted[j]);
      if (mb === ma && mj === mi && pb === (pj || 0) + 1) j++; else break;
    }
    out.push(j - i >= 2 ? `${sorted[i]} – ${sorted[j]}` : sorted.slice(i, j + 1).join(" · "));
    i = j + 1;
  }
  return out.join(" · ");
}

/** Release text: the GitHub release body if it says something, else the commits of the release. */
export function changelog(notes, commits, version = "") {
  const same = version ? new RegExp(`^\\S+\\s+v?${version.replace(/[.+]/g, "\\$&")}\\s*$`, "i") : null;
  for (const n of notes || []) {
    const lines = String(n.body || "").split(/\r?\n/).filter((l) => !/^\s*\*\*Full Changelog\*\*/i.test(l))
      .map((l) => l.replace(/^\s*#{1,6}\s+(.+?)\s*$/, (_, h) => (same && same.test(h) ? "" : `**${h}**`)))
      .map(tableRow).filter((l) => l !== null);
    const body = lines.join("\n").replace(/\n{3,}/g, "\n\n").trim();
    if (body) return clip(body, 1500);
  }
  const msgs = (commits || []).map((m) => String(m || "").split(/\r?\n/));
  if (msgs.length === 1) {
    const [subject, ...rest] = msgs[0];
    const body = unwrap(rest.filter((l) => !/^(co-authored-by|signed-off-by):/i.test(l)));
    return [tidy(subject) && `**${tidy(subject)}**`, body && clip(body, 900)].filter(Boolean).join("\n");
  }
  return uniq(msgs.map((l) => tidy(l[0])).filter((s) => s && !/^merge\b/i.test(s) && !/^(bump|version)\b/i.test(s)))
    .slice(-10).map((s) => `• ${s}`).join("\n");
}

/** Discord shows no tables: "| Java | **21** |" -> "Java: **21**", separator/empty rows go away (null). */
function tableRow(l) {
  if (!/^\s*\|.*\|\s*$/.test(l)) return l;
  const cells = l.trim().slice(1, -1).split("|").map((c) => c.trim()).filter(Boolean);
  if (!cells.length || cells.every((c) => /^:?-{2,}:?$/.test(c))) return null;
  return cells.length === 2 ? `${cells[0]}: ${cells[1]}` : cells.join(" · ");
}

/** Commit bodies are hard-wrapped at ~72 columns: glue lines back, keep bullets and blank lines. */
function unwrap(lines) {
  const out = [];
  for (const raw of lines) {
    const l = raw.trim();
    const bullet = /^[*\-•]\s+(.*)$/.exec(l);
    if (!l) { if (out.length && out.at(-1) !== "") out.push(""); }
    else if (bullet) out.push(`• ${bullet[1]}`);
    else if (out.length && out.at(-1) !== "") out[out.length - 1] += ` ${l}`;
    else out.push(l);
  }
  return out.join("\n").trim();
}

/** "Release 0.3.3: rebindable keys" -> "Rebindable keys"; a bare "Release 0.2.5" -> "" */
function tidy(subject) {
  const s0 = String(subject || "").trim();
  if (/^release\s+v?\d[\w.+-]*\s*$/i.test(s0)) return "";
  const s = s0.replace(/^release\s+v?\d[\w.+-]*\s*[:—–-]\s*/i, "").trim();
  return s ? s[0].toUpperCase() + s.slice(1) : "";
}

function clip(s, max) {
  if (s.length <= max) return s;
  const cut = s.slice(0, max);
  const end = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("\n"));
  return (end > max * 0.5 ? cut.slice(0, end + 1) : cut.trimEnd() + "…").trim();
}

/** discord.js message options for one version. */
export function buildMessage(project, g, { roleId, text, fallback = "Fixes and improvements.", headline = true }) {
  const link = (label, url) => ({ type: 2, style: 5, label, url, emoji: { name: "⬇️" } });
  const buttons = [];
  if (g.urls.modrinth) buttons.push(link("Modrinth", g.urls.modrinth));
  if (g.urls.curseforge) buttons.push(link("CurseForge", g.urls.curseforge));
  if (g.urls.github) buttons.push(link("GitHub", g.urls.github));
  const fields = [];
  const mc = (g.mc.length ? g.mc : project.mc).slice().sort(versionSort);
  const loaders = g.loaders.length ? g.loaders : project.loaders;
  if (mc.length) fields.push({ name: "Minecraft", value: mcRange(mc), inline: true });
  if (loaders.length) fields.push({ name: "Loaders", value: loaders.join(" · "), inline: true });
  return {
    content: roleId ? `<@&${roleId}>` : undefined,
    allowedMentions: { parse: [], roles: roleId ? [roleId] : [] },
    embeds: [{
      title: headline ? `${project.name} ${g.version} is out` : `${project.name} ${g.version}`, url: g.urls.github || g.urls.modrinth || g.urls.curseforge,
      color: project.color, description: text || fallback || undefined, fields,
      footer: { text: footerOf(project, g.version) }, timestamp: new Date(Date.parse(g.published)).toISOString(),
    }],
    components: buttons.length ? [{ type: 1, components: buttons.slice(0, 5) }] : [],
  };
}

/**
 * Poller. `fetchJson(url, headers)` -> {status, data, etag}.
 * targets: [{name, channel: {send, recentFooters}, since, projects?: [keys], ping?, fallback?}] —
 * e.g. the announcements channel (ping, only new) and per-mod release history channels (all versions, no ping).
 * Each source failing alone just skips that source for this round.
 */
export function makeAnnouncer({ fetchJson, targets, roles = {}, log = console, projects = PROJECTS, now = () => Date.now() }) {
  const etags = new Map();
  const cache = new Map();
  const texts = new Map();
  async function get(url) {
    const r = await fetchJson(url, etags.has(url) ? { "If-None-Match": etags.get(url) } : {});
    if (r.status === 304) return cache.get(url);
    if (r.status !== 200) throw new Error(`${url} -> ${r.status}`);
    if (r.etag) etags.set(url, r.etag);
    cache.set(url, r.data);
    return r.data;
  }
  async function collect(p) {
    const jobs = [];
    if (p.github) jobs.push(get(`https://api.github.com/repos/${p.github}/releases?per_page=50`).then((d) => fromGithub(p, d)));
    if (p.modrinth) jobs.push(get(`https://api.modrinth.com/v2/project/${p.modrinth}/version`).then((d) => fromModrinth(p, d)));
    if (p.curseforge) jobs.push(get(`https://api.cfwidget.com/minecraft/mc-mods/${p.curseforge}`).then((d) => fromCurseforge(p, d)));
    const res = await Promise.allSettled(jobs);
    for (const r of res) if (r.status === "rejected" && !/-> 404$/.test(r.reason?.message || "")) log.warn("releases:", r.reason?.message);
    return res.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
  }
  async function textFor(p, g) {
    const key = footerOf(p, g.version);
    if (texts.has(key)) return texts.get(key);
    const c = g.notes.find((n) => n.compare)?.compare;
    let commits = [];
    if (c && p.github) {
      try {
        const d = await get(`https://api.github.com/repos/${p.github}/compare/${c.base}...${c.head}`);
        commits = (d.commits || []).map((x) => x.commit?.message || "");
      } catch (e) { log.warn("releases: compare", e.message); return ""; }
    }
    const text = changelog(g.notes, commits, g.version);
    texts.set(key, text);
    return text;
  }
  return async function tick() {
    const groups = group((await Promise.all(projects.map(collect))).flat());
    const sent = [];
    for (const t of targets) {
      const mine = t.projects ? groups.filter((g) => t.projects.includes(g.project)) : groups;
      if (!pickNew(mine, { since: t.since, now: now(), posted: new Set() }).length) continue;
      const posted = await t.channel.recentFooters();
      for (const g of pickNew(mine, { since: t.since, now: now(), posted })) {
        const p = projects.find((x) => x.key === g.project);
        const msg = buildMessage(p, g, { roleId: t.ping ? roles[p.key] : null, text: await textFor(p, g), fallback: t.fallback ?? null, headline: !!t.ping });
        await t.channel.send(msg);
        posted.add(footerOf(p, g.version));
        sent.push(`${t.name}: ${footerOf(p, g.version)}`);
        log.log(`posted ${footerOf(p, g.version)} to ${t.name}`);
      }
    }
    return sent;
  };
}
