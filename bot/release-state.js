// Durable release announcements. A corrupt/unreadable state file is an error, not an empty history.
import { readFile, mkdir, writeFile, rename } from 'node:fs/promises';
import { dirname } from 'node:path';

export function fileReleaseState(path) {
  return {
    async load() {
      try {
        const data = JSON.parse(await readFile(path, 'utf8'));
        if (data.version !== 1 || !data.targets || typeof data.targets !== 'object') throw new Error('unsupported release state');
        return data;
      } catch (e) {
        if (e.code === 'ENOENT') return { version: 1, targets: {} };
        throw e;
      }
    },
    async save(data) {
      await mkdir(dirname(path), { recursive: true });
      const temp = `${path}.tmp`;
      await writeFile(temp, JSON.stringify(data, null, 2) + '\n', { mode: 0o600 });
      await rename(temp, path);
    },
  };
}
