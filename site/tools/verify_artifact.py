#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Independent artifact read-back: exact allowlist, manifest hashes and gzip budget."""
import argparse
from contextlib import ExitStack
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import stat
from urllib.parse import urlsplit

from build import ASSETS, Document, MAX_GZIP_BYTES, MAX_RAW_BYTES, base_path, strict_json, validate_output


def validate_manifest(manifest, files):
    """Reject malformed metadata before indexing or trusting its claimed values."""
    if (not isinstance(manifest, dict) or set(manifest) !=
            {"schemaVersion", "basePath", "canonical", "gzipBytes", "files"}):
        raise ValueError("invalid manifest schema")
    if type(manifest["schemaVersion"]) is not int or manifest["schemaVersion"] != 1:
        raise ValueError("invalid manifest schema version")
    if not isinstance(manifest["basePath"], str):
        raise ValueError("invalid manifest base path")
    base_path(manifest["basePath"])
    if type(manifest["gzipBytes"]) is not int or not 0 <= manifest["gzipBytes"] <= MAX_GZIP_BYTES:
        raise ValueError("invalid manifest compressed byte count")
    if not isinstance(manifest["files"], dict) or set(manifest["files"]) != set(files):
        raise ValueError("manifest members differ")
    for details in manifest["files"].values():
        if (not isinstance(details, dict) or set(details) != {"bytes", "sha256"}
                or type(details["bytes"]) is not int or details["bytes"] < 0
                or not isinstance(details["sha256"], str)
                or not re.fullmatch(r"[0-9a-f]{64}", details["sha256"])):
            raise ValueError("invalid manifest file metadata")
    canonical = manifest["canonical"]
    if canonical is not None:
        if not isinstance(canonical, str):
            raise ValueError("invalid manifest canonical URL")
        parts = urlsplit(canonical)
        if (parts.scheme != "https" or not parts.hostname or parts.username or parts.password
                or parts.query or parts.fragment or parts.path != manifest["basePath"]):
            raise ValueError("invalid manifest canonical URL")
    document = Document()
    document.feed(files["index.html"].decode("utf-8"))
    if document.canonicals != ([] if canonical is None else [canonical]):
        raise ValueError("manifest canonical differs from rendered metadata")


def read_member(directory, name, limit):
    """Admit opened regular inodes, without following replaced path components."""
    relative = Path(name)
    if relative.is_absolute() or not relative.parts or '..' in relative.parts:
        raise ValueError('invalid artifact member path')
    path = Path(directory).absolute() / relative
    if '..' in path.parts:
        raise ValueError('invalid artifact directory path')
    with ExitStack() as resources:
        parent = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        resources.callback(os.close, parent)
        for component in path.parts[1:-1]:
            parent = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                             dir_fd=parent)
            resources.callback(os.close, parent)
        descriptor = os.open(path.name, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW,
                             dir_fd=parent)
        with os.fdopen(descriptor, 'rb') as member:
            info = os.fstat(member.fileno())
            if not stat.S_ISREG(info.st_mode):
                raise ValueError('nonregular opened artifact member forbidden')
            if info.st_size > limit:
                raise ValueError('uncompressed artifact exceeds 8 MiB bound')
            data = member.read(limit + 1)
            if len(data) > limit:
                raise ValueError('uncompressed artifact exceeds 8 MiB bound')
            return data


def verify(directory, expected_manifest_sha256=None):
    if (expected_manifest_sha256 is not None
            and (not isinstance(expected_manifest_sha256, str)
                 or not re.fullmatch(r'[0-9a-f]{64}', expected_manifest_sha256))):
        raise ValueError('expected manifest identity must be 64 lowercase hexadecimal characters')
    directory = Path(directory).absolute()
    if any(path.is_symlink() for path in (directory, *directory.parents)):
        raise ValueError("symlink artifact root forbidden")
    expected = set(ASSETS) | {"index.html", ".nojekyll", "build-manifest.json"}
    expected_directories = {parent.as_posix() for name in expected
                            for parent in Path(name).parents if parent != Path('.')}
    actual = set()
    for path in directory.rglob("*"):
        mode = path.lstat().st_mode
        name = path.relative_to(directory).as_posix()
        if stat.S_ISLNK(mode):
            raise ValueError("symlink artifact member forbidden")
        if stat.S_ISDIR(mode):
            if name not in expected_directories:
                raise ValueError("artifact directory differs from public allowlist")
        elif stat.S_ISREG(mode):
            actual.add(name)
        else:
            raise ValueError("nonregular artifact member forbidden")
    if actual != expected:
        raise ValueError("artifact file set differs from public allowlist")
    if sum((directory / name).stat().st_size for name in expected) > MAX_RAW_BYTES:
        raise ValueError('uncompressed artifact exceeds 8 MiB bound')
    files = {}
    remaining = MAX_RAW_BYTES
    for name in sorted(expected):
        files[name] = read_member(directory, name, remaining)
        remaining -= len(files[name])
    manifest_bytes = files.pop("build-manifest.json")
    manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
    if expected_manifest_sha256 is not None and manifest_sha256 != expected_manifest_sha256:
        raise ValueError('artifact manifest identity differs from expected reviewed bytes')
    manifest = strict_json(manifest_bytes.decode())
    validate_manifest(manifest, files)
    for name, data in files.items():
        if manifest["files"][name] != {"bytes":len(data), "sha256":hashlib.sha256(data).hexdigest()}:
            raise ValueError(f"manifest content mismatch: {name}")
    compressed_content = validate_output(files, base_path(manifest["basePath"]))
    if compressed_content != manifest["gzipBytes"]:
        raise ValueError("manifest compressed content count differs")
    total = compressed_content + len(gzip.compress(manifest_bytes, mtime=0))
    if total > MAX_GZIP_BYTES:
        raise ValueError("complete artifact exceeds gzip budget")
    return {"status":"passed", "files":len(expected), "gzipBytesIncludingManifest":total,
            "basePath":manifest["basePath"], "canonical":manifest["canonical"],
            "manifestSha256":manifest_sha256}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--expected-manifest-sha256", help="compare against independently reviewed manifest bytes")
    args = parser.parse_args()
    try:
        result = verify(args.directory, args.expected_manifest_sha256)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f"artifact invalid: {error}\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
