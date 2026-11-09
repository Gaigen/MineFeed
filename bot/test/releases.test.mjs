import { test } from "node:test";
import assert from "node:assert/strict";
import { PROJECTS, parseJar, cleanVersion, fromGithub, fromModrinth, fromCurseforge, group, pickNew, changelog, buildMessage, footerOf, mcRange, versionSort } from "../releases.js";

const RC = PROJECTS.find((p) => p.key === "retroconsole");
const MF = PROJECTS.find((p) => p.key === "minefeed");
const gh = (tag, when, assets, body = "") => ({ tag_name: tag, html_url: `https://github.com/x/releases/${tag}`, published_at: when, draft: false, prerelease: false, body, assets: assets.map((name) => ({ name })) });

test("jar names and versions", () => {
  assert.deepEqual(parseJar("retroconsole-0.3.3+1.21.1-neoforge.jar"), { version: "0.3.3", mc: "1.21.1", loader: "NeoForge" });
  assert.deepEqual(parseJar("retroconsole-0.3.2+26.1.2-fabric.jar"), { version: "0.3.2", mc: "26.1.2", loader: "Fabric" });
  assert.deepEqual(parseJar("feed-0.2.0.jar"), { version: "0.2.0", mc: null, loader: null });
  assert.deepEqual(parseJar("retroconsole-0.4.0-neoforge.jar"), { version: "0.4.0", mc: null, loader: "NeoForge" });
  assert.equal(parseJar("feed-0.2.0-dev19.jar").version, "0.2.0-dev19");
  assert.equal(parseJar("retroconsole-0.3.3+1.21.1-neoforge-sources.jar"), null);
  assert.deepEqual(parseJar("retroconsole-0.3.1-26.1.2-fabric.jar"), { version: "0.3.1", mc: "26.1.2", loader: "Fabric" });
  assert.equal(parseJar("feed-0.2.0-beta.2.jar").version, "0.2.0-beta.2");
  assert.equal(cleanVersion("v0.3.3-mc1.21.1"), "0.3.3");
  assert.equal(cleanVersion("0.3.3+1.21.1-fabric"), "0.3.3");
  assert.equal(cleanVersion("nightly"), null);
});

test("GitHub builds of one version for two Minecraft versions become one announcement", () => {
  const releases = [
    gh("v0.3.2", "2026-10-30T00:07:57Z", ["retroconsole-0.3.2+26.1.2-fabric.jar", "retroconsole-0.3.2+26.1.2-neoforge.jar"]),
    gh("v0.3.2-mc1.21.1", "2026-10-30T00:07:17Z", ["retroconsole-0.3.2+1.21.1-fabric.jar", "retroconsole-0.3.2+1.21.1-neoforge.jar"], "**Full Changelog**: https://github.com/x/compare/v0.3.1-mc1.21.1...v0.3.2-mc1.21.1"),
    { ...gh("v0.4.0-beta.1", "2026-11-01T00:00:00Z", []), prerelease: true },
    gh("v0.5.0-rc1", "2026-11-01T00:00:00Z", []),
  ];
  const groups = group(fromGithub(RC, releases));
  assert.equal(groups.length, 1);
  const [g] = groups;
  assert.equal(g.version, "0.3.2");
  assert.deepEqual(g.mc.sort(), ["1.21.1", "26.1.2"]);
  assert.deepEqual(g.loaders.sort(), ["Fabric", "NeoForge"]);
  assert.equal(g.published, "2026-10-30T00:07:17Z");
  assert.deepEqual(g.notes.find((n) => n.compare).compare, { base: "v0.3.1-mc1.21.1", head: "v0.3.2-mc1.21.1" });
});

test("Modrinth and CurseForge merge into the GitHub entry", () => {
  const e = [
    ...fromGithub(MF, [gh("v0.2.0", "2026-12-01T10:00:00Z", ["feed-0.2.0.jar"])]),
    ...fromModrinth(MF, [{ id: "abc", version_number: "0.2.0+1.21.1", version_type: "release", game_versions: ["1.21.1"], loaders: ["neoforge"], date_published: "2026-12-01T10:30:00Z" },
      { id: "b", version_number: "0.2.1-beta", version_type: "beta", game_versions: [], loaders: [], date_published: "2026-12-02T00:00:00Z" }]),
    ...fromCurseforge(MF, { files: [{ name: "feed-0.2.0.jar", display: "feed-0.2.0.jar", type: "release", versions: ["Client", "1.21.1", "NeoForge"], uploaded_at: "2026-12-01T09:59:00Z", url: "https://cf/files/1" }] }),
  ];
  const [g, ...rest] = group(e);
  assert.equal(rest.length, 0);
  assert.equal(g.published, "2026-12-01T09:59:00Z");
  assert.deepEqual(Object.keys(g.urls).sort(), ["curseforge", "github", "modrinth"]);
  assert.deepEqual(g.loaders, ["NeoForge"]);
});

test("only new, settled and not yet posted versions are picked", () => {
  const since = Date.parse("2026-11-05T00:00:00Z");
  const now = Date.parse("2026-11-06T00:00:00Z");
  const g = (version, published) => ({ project: "retroconsole", version, published, mc: [], loaders: [], urls: {}, notes: [] });
  const groups = [g("0.3.3", "2026-11-02T00:00:00Z"), g("0.3.4", "2026-11-05T12:00:00Z"), g("0.3.5", "2026-11-05T23:58:00Z"), g("0.3.6", "2026-11-05T13:00:00Z")];
  const picked = pickNew(groups, { since, now, posted: new Set([footerOf(RC, "0.3.6")]) });
  assert.deepEqual(picked.map((x) => x.version), ["0.3.4"]);
});

test("changelog text", () => {
  assert.equal(changelog([{ body: "Big update!\n\n**Full Changelog**: https://x" }], []), "Big update!");
  assert.equal(changelog([{ body: "**Full Changelog**: https://x" }], ["Release 0.3.3: rebindable keys\n\nF4 is now a key mapping. Fixes #7.\nCo-authored-by: bot"]),
    "**Rebindable keys**\nF4 is now a key mapping. Fixes #7.");
  assert.equal(changelog([], ["fix crash on load", "Merge branch 'x'", "Bump version", "add Fabric support"]), "• Fix crash on load\n• Add Fabric support");
  assert.equal(changelog([], []), "");
  assert.equal(changelog([{ body: "**Requirements**\n\n| | |\n|---|---|\n| Minecraft | **1.21.1** |\n| Java | **21** |" }], []), "**Requirements**\n\nMinecraft: **1.21.1**\nJava: **21**");
  assert.equal(changelog([{ body: "## RetroConsole 0.1.0\nFirst build\n### Cores\n- snes" }], [], "0.1.0"), "First build\n**Cores**\n- snes");
  assert.equal(changelog([], ["Release 0.2.5\n\n* Doom 3: catalog entry, core\n  options, setup script\n* Volume 0 mutes\n\nLong text that was\nwrapped by git."]),
    "• Doom 3: catalog entry, core options, setup script\n• Volume 0 mutes\n\nLong text that was wrapped by git.");
});

test("message: ping, buttons, fields, footer", () => {
  const g = { project: "retroconsole", version: "0.3.3", mc: ["26.1.2", "1.21.1"], loaders: ["NeoForge", "Fabric"], published: "2026-11-02T19:32:18Z", notes: [],
    urls: { github: "https://github.com/r", modrinth: "https://modrinth.com/v" } };
  const m = buildMessage(RC, g, { roleId: "42", text: "**New keys**" });
  assert.equal(m.content, "<@&42>");
  assert.deepEqual(m.allowedMentions, { parse: [], roles: ["42"] });
  const e = m.embeds[0];
  assert.equal(e.title, "RetroConsole 0.3.3 is out");
  assert.equal(e.url, "https://github.com/r");
  assert.equal(e.footer.text, "RetroConsole 0.3.3");
  assert.deepEqual(e.fields.map((f) => f.value), ["1.21.1 · 26.1.2", "NeoForge · Fabric"]);
  assert.deepEqual(m.components[0].components.map((b) => b.label), ["Modrinth", "GitHub"]);
  const plain = buildMessage(MF, { ...g, project: "minefeed", mc: [], loaders: [], urls: { curseforge: "https://cf" } }, { text: "" });
  assert.equal(plain.content, undefined);
  assert.equal(plain.embeds[0].description, "Fixes and improvements.");
  assert.deepEqual(plain.embeds[0].fields.map((f) => f.value), ["1.21.1", "NeoForge"]);
});

test("version order and Minecraft ranges", () => {
  assert.deepEqual(["0.3.10", "0.2.6", "0.3.2", "0.2.5"].sort(versionSort), ["0.2.5", "0.2.6", "0.3.2", "0.3.10"]);
  assert.equal(mcRange(["1.21.1", "1.21.10", "1.21.11", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9"]), "1.21.1 – 1.21.11");
  assert.equal(mcRange(["26.1.2", "1.21.1"]), "1.21.1 · 26.1.2");
  assert.equal(mcRange(["1.20.1", "1.21.1", "1.21.2", "1.21.4"]), "1.20.1 · 1.21.1 · 1.21.2 · 1.21.4");
  assert.equal(mcRange(["1.21", "1.21.1", "1.21.2"]), "1.21 – 1.21.2");
});
