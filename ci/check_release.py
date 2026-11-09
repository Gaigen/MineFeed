#!/usr/bin/env python3
"""Fail closed if a release tag and the explicitly approved API do not match."""
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit
from package_mod import ROOT, version

API_SOURCES = [
    'src/main/java/com/feed/client/account/AccountApi.java',
    'neoforge-1.20.1/src/main/java/com/feed/client/account/AccountApi.java',
    'fabric-26.1.2/src/main/java/com/feed/client/account/AccountApi.java',
    'neoforge-26.1.2/src/main/java/com/feed/client/account/AccountApi.java',
]


def approved_api():
    p = ROOT / 'ci' / 'release-api.txt'
    if not p.is_file():
        raise ValueError('approved release API is not configured')
    url = p.read_text(encoding='utf-8').strip()
    parts = urlsplit(url)
    if (parts.scheme != 'https' or not parts.hostname or parts.username or parts.password
            or parts.query or parts.fragment or parts.path not in ('', '/') or parts.port):
        raise ValueError('release API must be a plain HTTPS origin')
    return url.rstrip('/')


def check(tag):
    v = version()
    if not re.fullmatch(r'\d+\.\d+\.\d+', v) or tag != f'v{v}':
        raise ValueError(f'tag must equal v<stable mod_version>; got {tag!r} and {v!r}')
    target = approved_api()
    for path in API_SOURCES:
        text = (ROOT / path).read_text(encoding='utf-8')
        matches = re.findall(r'DEFAULT_BASE\s*=\s*"([^"\\]*)"\s*\+\s*"([^"\\]*)"\s*;', text)
        if len(matches) != 1 or ''.join(matches[0]).rstrip('/') != target:
            raise ValueError(f'{path} does not use the approved API origin')
    print(f'Stable tag and configured API preflight passed: {tag} ({target})')


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('usage: check_release.py vX.Y.Z')
        check(sys.argv[1])
    except (ValueError, OSError) as exc:
        print(f'Release blocked: {exc}', file=sys.stderr)
        sys.exit(1)
