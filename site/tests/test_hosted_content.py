# SPDX-License-Identifier: MIT
"""Local synthetic handoff tests, not evidence of hosted GitHub execution."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from test_hosted_identity import ROOT, SHA, fixtures
from build import build
from check_hosted_identity import check
from artifact_archive import PUBLIC_FILES


class HostedContentTests(unittest.TestCase):
    def prepare(self, root, base='/twitch-screen/'):
        directory = root / 'output'
        build(directory, base)
        archive = root / 'local.zip'
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
            for name in sorted(PUBLIC_FILES):
                bundle.writestr(name, (directory / name).read_bytes())
        data = archive.read_bytes()
        archive_pin = hashlib.sha256(data).hexdigest()
        manifest_pin = hashlib.sha256((directory / 'build-manifest.json').read_bytes()).hexdigest()
        run, artifacts = fixtures()
        artifacts['artifacts'][0].update(size_in_bytes=len(data), digest='sha256:' + archive_pin)
        return archive, data, archive_pin, manifest_pin, run, artifacts

    def test_joint_content_identity_both_bases_without_publication_claim(self):
        for base in ('/', '/twitch-screen/'):
            with self.subTest(base=base), tempfile.TemporaryDirectory() as root:
                archive, data, pin, manifest, run, artifacts = self.prepare(Path(root), base)
                result = check(run, artifacts, 10, 30, SHA, 'pull_request', archive, pin,
                               expected_manifest_sha256=manifest)
                self.assertTrue(result['archiveBytesMatched'])
                self.assertTrue(result['artifactBytesVerified'])
                self.assertEqual(result['artifact']['manifestSha256'], manifest)
                self.assertEqual(result['artifact']['basePath'], base)
                self.assertEqual(result['artifact']['files'], 8)
                for key in ('metadataAuthenticated', 'environmentProtectionVerified', 'publicationAuthorized'):
                    self.assertIs(result[key], False)
                self.assertEqual(archive.read_bytes(), data)

    def test_manifest_pin_requires_archive_and_rejects_before_read(self):
        run, artifacts = fixtures()
        with patch('check_hosted_identity.read_member') as reader:
            for pin in ('', 'A' * 64, 'a' * 64 + '\n', 1):
                with self.subTest(pin=pin), self.assertRaises(ValueError):
                    check(run, artifacts, 10, 30, SHA, 'pull_request', Path('absent.zip'), 'b' * 64,
                          expected_manifest_sha256=pin)
            with self.assertRaises(ValueError):
                check(run, artifacts, 10, 30, SHA, 'pull_request', expected_manifest_sha256='a' * 64)
            reader.assert_not_called()

    def test_content_checks_same_once_admitted_bytes_despite_path_replacement(self):
        with tempfile.TemporaryDirectory() as root:
            archive, data, pin, manifest, run, artifacts = self.prepare(Path(root))
            def admitted(*args):
                archive.write_bytes(b'replaced input must not be reopened')
                return data
            with patch('check_hosted_identity.read_member', side_effect=admitted) as reader:
                result = check(run, artifacts, 10, 30, SHA, 'pull_request', archive, pin,
                               expected_manifest_sha256=manifest)
                self.assertTrue(result['artifactBytesVerified'])
                reader.assert_called_once()

    def test_matching_archive_digest_cannot_bypass_manifest_or_content_policy(self):
        with tempfile.TemporaryDirectory() as root:
            archive, data, pin, manifest, run, artifacts = self.prepare(Path(root))
            with self.assertRaises(ValueError):
                check(run, artifacts, 10, 30, SHA, 'pull_request', archive, pin,
                      expected_manifest_sha256='a' * 64)
            for data in (b'not a ZIP',):
                archive.write_bytes(data)
                pin = hashlib.sha256(data).hexdigest()
                artifacts['artifacts'][0].update(size_in_bytes=len(data), digest='sha256:' + pin)
                with self.assertRaises(ValueError):
                    check(run, artifacts, 10, 30, SHA, 'pull_request', archive, pin,
                          expected_manifest_sha256=manifest)

    def test_matching_zip_digest_rejects_tampered_members_and_cleans_extraction(self):
        import artifact_archive
        for mutation in ('content', 'extra'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                root = Path(root)
                archive, data, pin, manifest, run, artifacts = self.prepare(root)
                with zipfile.ZipFile(archive) as bundle:
                    entries = [(item.filename, bundle.read(item)) for item in bundle.infolist()]
                with zipfile.ZipFile(archive, 'w') as bundle:
                    for name, payload in entries:
                        bundle.writestr(name, b'tampered' if mutation == 'content' and name == 'index.html' else payload)
                    if mutation == 'extra':
                        bundle.writestr('private.txt', b'not public')
                data = archive.read_bytes()
                pin = hashlib.sha256(data).hexdigest()
                artifacts['artifacts'][0].update(size_in_bytes=len(data), digest='sha256:' + pin)
                scratch = root / 'scratch'
                scratch.mkdir()
                temporary = tempfile.TemporaryDirectory
                with patch.object(artifact_archive.tempfile, 'TemporaryDirectory',
                                  side_effect=lambda **kwargs: temporary(dir=scratch, **kwargs)):
                    with self.assertRaises(ValueError):
                        check(run, artifacts, 10, 30, SHA, 'pull_request', archive, pin,
                              expected_manifest_sha256=manifest)
                self.assertEqual(list(scratch.iterdir()), [])
                self.assertEqual(archive.read_bytes(), data)

    def test_shared_buffer_bound_rejects_before_zip_parser(self):
        import artifact_archive
        with patch.object(artifact_archive, 'MAX_ARCHIVE_BYTES', 2), patch.object(
                artifact_archive.zipfile, 'ZipFile', side_effect=AssertionError('must not parse')):
            for data in (b'123', bytearray(b'12'), None):
                with self.subTest(data=data), self.assertRaises(ValueError):
                    with artifact_archive.archive_bytes_directory(data):
                        self.fail('unbounded or mutable buffer admitted')

    def test_cli_joint_gate_and_concise_rejection(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            archive, data, pin, manifest, run, artifacts = self.prepare(root)
            (root / 'run.json').write_text(json.dumps(run))
            (root / 'artifacts.json').write_text(json.dumps(artifacts))
            command = [sys.executable, str(ROOT / 'site/tools/check_hosted_identity.py'),
                       '--run-json', str(root / 'run.json'), '--artifacts-json', str(root / 'artifacts.json'),
                       '--run-id', '10', '--artifact-id', '30', '--head-sha', SHA, '--event', 'pull_request',
                       '--archive', str(archive), '--expected-archive-sha256', pin,
                       '--expected-manifest-sha256']
            result = subprocess.run(command + [manifest], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(json.loads(result.stdout)['artifactBytesVerified'])
            result = subprocess.run(command + ['a' * 64], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            self.assertIn('hosted identity rejected:', result.stderr)
            self.assertNotIn('Traceback', result.stderr)
