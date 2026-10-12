#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Install the reviewed Linux x86_64 test-only browser, never an unpinned latest build."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import platform
import re
import stat
import tempfile
import urllib.request
import zipfile

SITE = Path(__file__).resolve().parents[1]
MAX_EXPANDED_BYTES = 512 * 1024 * 1024
MAX_MEMBERS = 512


def baseline():
    data = json.loads((SITE / 'browser-baseline.json').read_text())
    fields = {'schemaVersion', 'channel', 'version', 'revision', 'platform', 'url', 'sha256',
              'archiveBytes', 'executable', 'reviewedAt', 'releaseSource', 'crossCheckSource'}
    if (not isinstance(data, dict) or set(data) != fields or type(data['schemaVersion']) is not int
            or data['schemaVersion'] != 1 or data['channel'] != 'Stable' or data['platform'] != 'linux64'
            or any(not isinstance(data[key], str) for key in fields - {'schemaVersion', 'archiveBytes'})
            or not re.fullmatch(r'[0-9]+(?:\.[0-9]+){3}', data['version'])
            or not re.fullmatch(r'[0-9a-f]{64}', data['sha256'])
            or type(data['archiveBytes']) is not int or not 0 < data['archiveBytes'] <= 256 * 1024 * 1024
            or data['executable'] != 'chrome-headless-shell-linux64/chrome-headless-shell'
            or data['url'] != 'https://storage.googleapis.com/chrome-for-testing-public/'
            + data['version'] + '/linux64/chrome-headless-shell-linux64.zip'):
        raise ValueError('invalid reviewed browser baseline')
    return data


def empty_destination(output):
    output = Path(output).absolute()
    if any(path.is_symlink() for path in (output, *output.parents)):
        raise ValueError('browser destination symlinks forbidden')
    output = output.resolve()
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError('browser destination must be absent or empty; no files are overwritten')
    return output


def extract(archive, output, data):
    """Validate all paths, types and extents before creating any destination member."""
    output = empty_destination(output)
    if archive.stat().st_size != data['archiveBytes']:
        raise ValueError('browser archive byte count differs')
    digest = hashlib.sha256()
    with archive.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    if digest.hexdigest() != data['sha256']:
        raise ValueError('browser archive SHA-256 differs')
    with zipfile.ZipFile(archive) as bundle:
        members = bundle.infolist()
        if len(members) > MAX_MEMBERS or sum(item.file_size for item in members) > MAX_EXPANDED_BYTES:
            raise ValueError('browser archive expansion exceeds bounds')
        seen = {}
        for item in members:
            path = PurePosixPath(item.filename)
            mode = item.external_attr >> 16
            kind = stat.S_IFMT(mode)
            if (path.is_absolute() or '..' in path.parts or '\\' in item.filename
                    or path.as_posix() != item.filename.rstrip('/')
                    or not path.parts or path.parts[0] != 'chrome-headless-shell-linux64'
                    or path.as_posix() in seen or kind not in (0, stat.S_IFREG, stat.S_IFDIR)
                    or (kind == stat.S_IFDIR and not item.is_dir())
                    or (kind == stat.S_IFREG and item.is_dir())
                    or (item.is_dir() and item.file_size != 0)
                    or item.flag_bits & 1):
                raise ValueError('unsafe browser archive member')
            seen[path.as_posix()] = item.is_dir()
        # Check the entire hierarchy independently of member order. A file cannot
        # also be an ancestor; zipfile would otherwise fail after partial writes.
        for name in seen:
            if any(seen.get(parent.as_posix()) is False
                   for parent in PurePosixPath(name).parents):
                raise ValueError('unsafe browser archive member hierarchy')
        if data['executable'] not in seen or seen[data['executable']]:
            raise ValueError('browser executable missing')
        # Full CRC validation before extraction, in addition to the pinned archive hash.
        if bundle.testzip() is not None:
            raise ValueError('browser archive CRC differs')
        bundle.extractall(output)
    executable = output / data['executable']
    executable.chmod(0o700)
    return executable


def install(output):
    if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'AMD64'):
        raise ValueError('reviewed browser supports Linux x86_64 only')
    data = baseline()
    output = empty_destination(output)
    with tempfile.TemporaryDirectory(prefix='twitch-screen-browser-download-') as directory:
        archive = Path(directory) / 'browser.zip'
        with urllib.request.urlopen(data['url'], timeout=60) as response, archive.open('wb') as target:
            if response.geturl() != data['url']:
                raise ValueError('browser download redirect forbidden')
            while chunk := response.read(1024 * 1024):
                target.write(chunk)
                if target.tell() > data['archiveBytes']:
                    raise ValueError('browser download exceeds pinned size')
        return extract(archive, output, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=SITE / '.browser')
    args = parser.parse_args()
    try:
        executable = install(args.output)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f'browser installation failed: {error}\n')
    print(executable)


if __name__ == '__main__':
    main()
