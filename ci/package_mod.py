#!/usr/bin/env python3
"""Select and validate one distributable JAR, then give it a bot-friendly name."""
import argparse
import json
import re
import shutil
import sys
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def version():
    matches = re.findall(r'^mod_version\s*=\s*(\S+)\s*$', (ROOT / 'gradle.properties').read_text(), re.M)
    if len(matches) != 1 or not re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-z][a-z0-9.]*)?', matches[0], re.I):
        raise ValueError('gradle.properties must contain one valid mod_version')
    return matches[0]


def inspect_jar(path, loader, mc, expected):
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        if 'com/feed/Feed.class' not in names:
            return False
        if loader == 'fabric':
            if 'fabric.mod.json' not in names:
                return False
            meta = json.loads(jar.read('fabric.mod.json'))
            if meta.get('id') != 'feed' or meta.get('version') != expected:
                raise ValueError(f'{path}: Fabric mod ID or internal version does not match')
            if meta.get('depends', {}).get('minecraft') != f'={mc}':
                raise ValueError(f'{path}: Fabric Minecraft version does not match {mc}')
        else:
            name = 'META-INF/mods.toml' if mc == '1.20.1' else 'META-INF/neoforge.mods.toml'
            if name not in names:
                return False
            meta = tomllib.loads(jar.read(name).decode('utf-8'))
            mods = meta.get('mods', [])
            if not any(m.get('modId') == 'feed' and m.get('version') == expected for m in mods):
                raise ValueError(f'{path}: NeoForge mod ID or internal version does not match')
            deps = meta.get('dependencies', {}).get('feed', [])
            if not any(d.get('modId') == 'minecraft' and mc in d.get('versionRange', '') for d in deps):
                raise ValueError(f'{path}: NeoForge Minecraft version does not match {mc}')
        return True


def package(libs, output, loader, mc):
    v = version()
    candidates = []
    for path in sorted(Path(libs).glob('*.jar')):
        if path.name.endswith(('-sources.jar', '-javadoc.jar', '-dev.jar', '-plain.jar')):
            continue
        if inspect_jar(path, loader, mc, v):
            candidates.append(path)
    if len(candidates) != 1:
        raise ValueError(f'expected one distributable JAR in {libs}, found {len(candidates)}: {candidates}')
    out = Path(output)
    out.mkdir(parents=True, exist_ok=True)
    target = out / f'minefeed-{v}+{mc}-{loader}.jar'
    shutil.copyfile(candidates[0], target)
    print(f'{candidates[0]} -> {target}')
    return target


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--libs', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--loader', choices=['fabric', 'neoforge'], required=True)
    parser.add_argument('--minecraft', choices=['1.20.1', '1.21.1', '26.1.2'], required=True)
    args = parser.parse_args()
    package(args.libs, args.output, args.loader, args.minecraft)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, json.JSONDecodeError, tomllib.TOMLDecodeError) as exc:
        print(f'Packaging error: {exc}', file=sys.stderr)
        sys.exit(1)
