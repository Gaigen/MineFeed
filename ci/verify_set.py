#!/usr/bin/env python3
"""Refuse to create a draft unless exactly the six validated target JARs exist."""
import sys
from pathlib import Path
from package_mod import version, inspect_jar

v = version()
root = Path(sys.argv[1])
expected = {f'minefeed-{v}+{mc}-{loader}.jar': (loader, mc)
            for mc in ('1.20.1', '1.21.1', '26.1.2') for loader in ('fabric', 'neoforge')}
actual = {p.name for p in root.glob('*.jar')}
if actual != set(expected):
    raise SystemExit(f'Artifact set mismatch; missing={sorted(set(expected)-actual)}; unexpected={sorted(actual-set(expected))}')
for name, (loader, mc) in expected.items():
    if not inspect_jar(root / name, loader, mc, v):
        raise SystemExit(f'Invalid distributable: {name}')
print('Verified six distributable JARs:', ', '.join(sorted(actual)))
