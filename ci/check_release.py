#!/usr/bin/env python3
"""Fail closed before creating a public-facing release draft."""
import re
import sys
from pathlib import Path
from package_mod import ROOT, version


def check(tag):
    v = version()
    if not re.fullmatch(r'\d+\.\d+\.\d+', v) or tag != f'v{v}':
        raise ValueError(f'tag must equal v<stable mod_version>; got {tag!r} and {v!r}')
    api_sources = [
        'src/main/java/com/feed/client/account/AccountApi.java',
        'neoforge-1.20.1/src/main/java/com/feed/client/account/AccountApi.java',
        'fabric-26.1.2/src/main/java/com/feed/client/account/AccountApi.java',
        'neoforge-26.1.2/src/main/java/com/feed/client/account/AccountApi.java',
    ]
    for path in api_sources:
        text = (ROOT / path).read_text()
        # Production URL must be explicitly configured; the development Worker must
        # never become the default for a stable release.
        if 'minefeed-dev.' in text:
            raise ValueError(f'{path} still uses the development API')
    print(f'Stable tag and API preflight passed: {tag}')


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('usage: check_release.py vX.Y.Z')
        check(sys.argv[1])
    except ValueError as exc:
        print(f'Release blocked: {exc}', file=sys.stderr)
        sys.exit(1)
