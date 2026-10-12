#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Bounded ZIP admission for downloaded static-site artifacts, not arbitrary archives."""
from contextlib import contextmanager
import io
from pathlib import Path
import stat
import struct
import tempfile
import zipfile
import zlib

from build import ASSETS, MAX_RAW_BYTES
from verify_artifact import read_member, verify

PUBLIC_FILES = frozenset(ASSETS) | {'index.html', '.nojekyll', 'build-manifest.json'}
MAX_ARCHIVE_BYTES = MAX_RAW_BYTES * 2
MAX_DIRECTORY_BYTES = 64 * 1024


def validate_directory_extent(data):
    """Bound ZIP metadata before ZipFile allocates its member objects."""
    end = data.rfind(b'PK\x05\x06', max(0, len(data) - 65557))
    if end < 0 or end + 22 > len(data):
        raise ValueError('archive end record missing')
    _, disk, directory_disk, disk_count, count, size, offset, comment = struct.unpack_from(
        '<4s4H2IH', data, end)
    if (disk or directory_disk or disk_count != count or count > len(PUBLIC_FILES) + 1
            or size > MAX_DIRECTORY_BYTES or offset + size != end
            or end + 22 + comment != len(data)):
        raise ValueError('archive directory extent exceeds bounded single-disk ZIP policy')
    cursor = offset
    actual_count = 0
    while cursor < end:
        if (actual_count >= len(PUBLIC_FILES) + 1 or cursor + 46 > end
                or data[cursor:cursor + 4] != b'PK\x01\x02'):
            raise ValueError('invalid bounded archive directory record')
        name_size, extra_size, comment_size, member_disk = struct.unpack_from('<4H', data, cursor + 28)
        record_end = cursor + 46 + name_size + extra_size + comment_size
        compressed, expanded = struct.unpack_from('<2I', data, cursor + 20)
        header_offset = struct.unpack_from('<I', data, cursor + 42)[0]
        if (record_end > end or member_disk or compressed == 0xffffffff
                or expanded == 0xffffffff or header_offset == 0xffffffff):
            raise ValueError('archive member extent or disk differs from bounded ZIP policy')
        extra_cursor = cursor + 46 + name_size
        extra_end = extra_cursor + extra_size
        while extra_cursor < extra_end:
            if extra_cursor + 4 > extra_end:
                raise ValueError('truncated archive directory extra field')
            tag, length = struct.unpack_from('<2H', data, extra_cursor)
            extra_cursor += 4 + length
            if tag == 1 or extra_cursor > extra_end:
                raise ValueError('ZIP64 or truncated archive directory extra field')
        actual_count += 1
        cursor = record_end
    if actual_count != count:
        raise ValueError('archive directory member count differs from end record')


def admitted_members(bundle):
    """Validate the complete directory before writing into an owned snapshot."""
    members = bundle.infolist()
    if len(members) > len(PUBLIC_FILES) + 1:
        raise ValueError('archive member count exceeds public allowlist')
    seen = set()
    total = 0
    files = []
    for item in members:
        name = item.filename
        if name in seen or item.orig_filename != name:
            raise ValueError('duplicate or truncated archive member')
        seen.add(name)
        mode = item.external_attr >> 16
        kind = stat.S_IFMT(mode)
        if item.flag_bits & 1 or item.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            raise ValueError('encrypted or unsupported archive member')
        if name == 'assets/':
            if not item.is_dir() or kind not in (0, stat.S_IFDIR) or item.file_size != 0:
                raise ValueError('invalid archive directory')
            continue
        if (name not in PUBLIC_FILES or item.is_dir() or kind not in (0, stat.S_IFREG)
                or item.external_attr & 0x10):
            raise ValueError('archive member differs from regular public allowlist')
        total += item.file_size
        if total > MAX_RAW_BYTES:
            raise ValueError('archive expansion exceeds 8 MiB bound')
        files.append(item)
    if {item.filename for item in files} != PUBLIC_FILES:
        raise ValueError('archive public file set differs')
    return files


@contextmanager
def archive_directory(archive):
    """Read once, bound expansion, verify content, and remove only owned temporary files."""
    archive = Path(archive).absolute()
    data = read_member(archive.parent, archive.name, MAX_ARCHIVE_BYTES)
    with archive_bytes_directory(data) as directory:
        yield directory


@contextmanager
def archive_bytes_directory(data):
    """Admit one immutable bounded buffer shared with archive-identity verification."""
    if not isinstance(data, bytes) or len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError('archive buffer exceeds bounded immutable ZIP policy')
    validate_directory_extent(data)
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as bundle:
            members = admitted_members(bundle)
            with tempfile.TemporaryDirectory(prefix='twitch-screen-archive-') as root:
                directory = Path(root)
                remaining = MAX_RAW_BYTES
                for item in members:
                    with bundle.open(item) as source:
                        payload = source.read(min(item.file_size, remaining) + 1)
                    if len(payload) != item.file_size or len(payload) > remaining:
                        raise ValueError('archive expanded member size differs')
                    remaining -= len(payload)
                    target = directory / item.filename
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(payload)
                verify(directory)
                yield directory
    except (zipfile.BadZipFile, NotImplementedError, RuntimeError, zlib.error, EOFError) as error:
        raise ValueError('invalid static artifact ZIP') from error
