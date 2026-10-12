# SPDX-License-Identifier: MIT
"""Downloaded ZIPs admit only the bounded, content-verified public snapshot."""
from pathlib import Path
import os
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import artifact_archive
from build import build
from preview import PUBLIC_FILES


class ArtifactArchiveTests(unittest.TestCase):
    def make_archive(self, root, base='/twitch-screen/', extra=None, compression=zipfile.ZIP_DEFLATED):
        artifact = root / 'artifact'
        build(artifact, base)
        archive = root / 'site.zip'
        with zipfile.ZipFile(archive, 'w', compression=compression) as bundle:
            if extra is None:
                bundle.writestr('assets/', b'')
            for name in sorted(PUBLIC_FILES):
                bundle.writestr(name, (artifact / name).read_bytes())
            if extra:
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    bundle.writestr(*extra)
        return artifact, archive

    def test_exact_bytes_bases_and_cleanup_without_modifying_input(self):
        for base in ('/', '/twitch-screen/'):
            for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                with self.subTest(base=base, compression=compression), tempfile.TemporaryDirectory() as root:
                    artifact, archive = self.make_archive(Path(root), base, compression=compression)
                    original = archive.read_bytes()
                    with artifact_archive.archive_directory(archive) as snapshot:
                        self.assertEqual({name: (snapshot / name).read_bytes() for name in PUBLIC_FILES},
                                         {name: (artifact / name).read_bytes() for name in PUBLIC_FILES})
                        self.assertNotEqual(snapshot, artifact)
                    self.assertFalse(snapshot.exists())
                    self.assertEqual(archive.read_bytes(), original)

    def test_metadata_rejects_before_temporary_extraction(self):
        invalid = ('../escaped.txt', '/index.html', 'assets\\orbit.svg', 'assets//orbit.svg',
                   './index.html', 'private.txt', 'index.html', 'empty/', 'index.html\x00tail')
        for name in invalid:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as root:
                _, archive = self.make_archive(Path(root), extra=(name, b'not public'))
                with patch.object(artifact_archive.tempfile, 'TemporaryDirectory',
                                  side_effect=AssertionError('must reject before extraction')):
                    with self.assertRaises(ValueError):
                        with artifact_archive.archive_directory(archive):
                            self.fail('unsafe archive admitted')
        for kind in (stat.S_IFLNK, stat.S_IFIFO, stat.S_IFSOCK, stat.S_IFDIR):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                info = zipfile.ZipInfo('index.html')
                info.create_system = 3
                info.external_attr = (kind | 0o600) << 16
                _, archive = self.make_archive(Path(root))
                # Rewrite an exact member, avoiding duplicate-count rejection.
                with zipfile.ZipFile(archive) as bundle:
                    entries = [(item, bundle.read(item)) for item in bundle.infolist()]
                with zipfile.ZipFile(archive, 'w') as bundle:
                    for item, data in entries:
                        bundle.writestr(info if item.filename == 'index.html' else item, data)
                with self.assertRaises(ValueError):
                    with artifact_archive.archive_directory(archive):
                        self.fail('special member admitted')

    def test_missing_tampered_unsupported_corrupt_and_bounds(self):
        for mutation in ('missing', 'tampered', 'unsupported', 'corrupt', 'expanded', 'compressed'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                _, archive = self.make_archive(Path(root))
                if mutation in ('missing', 'tampered', 'unsupported'):
                    with zipfile.ZipFile(archive) as bundle:
                        entries = [(item.filename, bundle.read(item)) for item in bundle.infolist()]
                    with zipfile.ZipFile(archive, 'w', compression=(zipfile.ZIP_BZIP2
                                         if mutation == 'unsupported' else zipfile.ZIP_DEFLATED)) as bundle:
                        for name, data in entries:
                            if name == 'index.html':
                                if mutation == 'missing':
                                    continue
                                if mutation == 'tampered':
                                    data = b'tampered'
                            bundle.writestr(name, data)
                elif mutation == 'corrupt':
                    archive.write_bytes(b'not a ZIP')
                limit = 'MAX_RAW_BYTES' if mutation == 'expanded' else 'MAX_ARCHIVE_BYTES'
                with patch.object(artifact_archive, limit, 1 if mutation in ('expanded', 'compressed')
                                  else getattr(artifact_archive, limit)):
                    with self.assertRaises(ValueError):
                        with artifact_archive.archive_directory(archive):
                            self.fail('invalid archive admitted')

    def test_encrypted_dos_directory_and_inflated_metadata_reject(self):
        for mutation in ('encrypted', 'encrypted-directory', 'dos-directory', 'oversized', 'too-many', 'directory-data'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                _, archive = self.make_archive(Path(root))
                with zipfile.ZipFile(archive) as bundle:
                    members = bundle.infolist()
                    member = next(item for item in members if item.filename == 'index.html')
                    if mutation == 'encrypted-directory':
                        members[0].flag_bits |= 1
                    elif mutation == 'encrypted':
                        member.flag_bits |= 1
                    elif mutation == 'dos-directory':
                        member.external_attr |= 0x10
                    elif mutation == 'oversized':
                        member.file_size = artifact_archive.MAX_RAW_BYTES + 1
                    elif mutation == 'too-many':
                        members.append(zipfile.ZipInfo('extra.txt'))
                    else:
                        members[0].file_size = 1
                    with patch.object(bundle, 'infolist', return_value=members):
                        with self.assertRaises(ValueError):
                            artifact_archive.admitted_members(bundle)

    def test_directory_allocation_bound_before_zip_parser(self):
        mutations = ('disk', 'count', 'directory-size', 'offset', 'trailing', 'zip64')
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                _, archive = self.make_archive(Path(root))
                data = bytearray(archive.read_bytes())
                end = len(data) - 22
                if mutation == 'disk':
                    struct.pack_into('<H', data, end + 4, 1)
                elif mutation == 'count':
                    struct.pack_into('<HH', data, end + 8, 1000, 1000)
                elif mutation == 'directory-size':
                    struct.pack_into('<I', data, end + 12, artifact_archive.MAX_DIRECTORY_BYTES + 1)
                elif mutation == 'offset':
                    struct.pack_into('<I', data, end + 16, 0)
                elif mutation == 'zip64':
                    struct.pack_into('<HH', data, end + 8, 65535, 65535)
                else:
                    data.extend(b'unexpected trailing bytes')
                archive.write_bytes(data)
                with patch.object(artifact_archive.zipfile, 'ZipFile',
                                  side_effect=AssertionError('must bound metadata before parsing')):
                    with self.assertRaises(ValueError):
                        with artifact_archive.archive_directory(archive):
                            self.fail('invalid directory admitted')

    def test_directory_records_checked_before_zip_parser(self):
        mutations = ('zero-count', 'under-count', 'over-count', 'signature',
                     'name-overrun', 'extra-overrun', 'comment-overrun', 'entry-disk')
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                _, archive = self.make_archive(Path(root))
                data = bytearray(archive.read_bytes())
                end = len(data) - 22
                offset = struct.unpack_from('<I', data, end + 16)[0]
                if mutation in ('zero-count', 'under-count', 'over-count'):
                    count = {'zero-count': 0, 'under-count': 8, 'over-count': 10}[mutation]
                    struct.pack_into('<HH', data, end + 8, count, count)
                elif mutation == 'signature':
                    data[offset:offset + 4] = b'FAIL'
                elif mutation == 'entry-disk':
                    struct.pack_into('<H', data, offset + 34, 1)
                else:
                    field = {'name-overrun': 28, 'extra-overrun': 30, 'comment-overrun': 32}[mutation]
                    struct.pack_into('<H', data, offset + field, 65535)
                archive.write_bytes(data)
                with patch.object(artifact_archive.zipfile, 'ZipFile',
                                  side_effect=AssertionError('must admit directory records before parsing')):
                    with self.assertRaises(ValueError):
                        with artifact_archive.archive_directory(archive):
                            self.fail('inconsistent directory admitted')

    def test_extra_fields_and_actual_count_are_bounded_before_parser(self):
        for mutation in ('zip64', 'short-extra', 'long-extra', 'hidden-tenth', 'valid-extra'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                artifact, archive = self.make_archive(Path(root))
                with zipfile.ZipFile(archive) as bundle:
                    entries = [(item, bundle.read(item)) for item in bundle.infolist()]
                with zipfile.ZipFile(archive, 'w') as bundle:
                    for item, payload in entries:
                        if item.filename == 'index.html':
                            item.extra = {'zip64': b'\x01\x00\x00\x00',
                                          'short-extra': b'\xfe',
                                          'long-extra': b'\xfe\xca\x01\x00',
                                          'hidden-tenth': b'',
                                          'valid-extra': b'\xfe\xca\x02\x00OK'}[mutation]
                            item.comment = b'bounded member comment'
                        bundle.writestr(item, payload)
                    bundle.comment = b'bounded archive comment'
                    if mutation == 'hidden-tenth':
                        bundle.writestr('extra.txt', b'')
                if mutation == 'valid-extra':
                    with artifact_archive.archive_directory(archive) as snapshot:
                        self.assertEqual((snapshot / 'index.html').read_bytes(),
                                         (artifact / 'index.html').read_bytes())
                    continue
                if mutation == 'hidden-tenth':
                    data = bytearray(archive.read_bytes())
                    end = data.rfind(b'PK\x05\x06')
                    struct.pack_into('<HH', data, end + 8, 8, 8)
                    archive.write_bytes(data)
                with patch.object(artifact_archive.zipfile, 'ZipFile',
                                  side_effect=AssertionError('must bound records before parsing')):
                    with self.assertRaises(ValueError):
                        with artifact_archive.archive_directory(archive):
                            self.fail('invalid extra/count admitted')

    def test_corrupt_member_cleans_snapshot(self):
        with tempfile.TemporaryDirectory() as root:
            _, archive = self.make_archive(Path(root), compression=zipfile.ZIP_STORED)
            with zipfile.ZipFile(archive) as bundle:
                member = bundle.getinfo('index.html')
                offset = member.header_offset + 30 + len(member.filename.encode()) + len(member.extra)
            data = bytearray(archive.read_bytes())
            data[offset] ^= 1
            archive.write_bytes(data)
            scratch = Path(root) / 'scratch'
            scratch.mkdir()
            result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1]
                                     / 'tools/preview.py'), '--archive', str(archive), '--port', '0'],
                                    env=dict(os.environ, TMPDIR=str(scratch)),
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 1)
            self.assertIn('invalid static artifact ZIP', result.stderr)
            self.assertNotIn('Traceback', result.stderr)
            self.assertEqual(list(scratch.iterdir()), [])

    def test_input_symlink_rejected_and_cli_diagnostic(self):
        with tempfile.TemporaryDirectory() as root:
            _, archive = self.make_archive(Path(root))
            alias = Path(root) / 'alias.zip'
            alias.symlink_to(archive)
            with self.assertRaises(OSError):
                with artifact_archive.archive_directory(alias):
                    self.fail('archive symlink admitted')
            archive.write_bytes(b'not a ZIP')
            result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1]
                                     / 'tools/preview.py'), '--archive', str(archive), '--port', '0'],
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 1)
            self.assertIn('preview failed:', result.stderr)
            self.assertNotIn('Traceback', result.stderr)
            self.assertNotIn('Preview:', result.stdout)


if __name__ == '__main__':
    unittest.main()
