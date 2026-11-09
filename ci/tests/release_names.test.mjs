import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PROJECTS, parseJar, fromGithub, group } from '../../bot/releases.js';

test('all six CI artifact names are understood by the live Discord announcer', () => {
  const assets = [];
  for (const mc of ['1.20.1', '1.21.1', '26.1.2']) for (const loader of ['fabric', 'neoforge']) {
    const name = `minefeed-0.2.0+${mc}-${loader}.jar`;
    assert.deepEqual(parseJar(name), { version: '0.2.0', mc, loader: loader === 'fabric' ? 'Fabric' : 'NeoForge' });
    assets.push({ name });
  }
  const p = PROJECTS.find(x => x.key === 'minefeed');
  const entries = fromGithub(p, [{ tag_name: 'v0.2.0', html_url: 'https://github.com/Gaigen/MineFeed/releases/tag/v0.2.0', published_at: '2026-11-01T00:00:00Z', assets }]);
  const groups = group(entries);
  assert.equal(groups.length, 1);
  assert.deepEqual(groups[0].mc.sort(), ['1.20.1', '1.21.1', '26.1.2']);
  assert.deepEqual(groups[0].loaders.sort(), ['Fabric', 'NeoForge']);
});
