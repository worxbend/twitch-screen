# SPDX-License-Identifier: MIT
"""Correlate inspected bytes to an independently supplied manifest identity."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'site/tools'))
from build import build
import preview
from verify_artifact import verify


class ManifestIdentityTests(unittest.TestCase):
    def test_exact_identity_is_returned_and_optional_pin_is_enforced(self):
        with tempfile.TemporaryDirectory() as root:
            artifact = Path(root) / 'artifact'
            build(artifact)
            digest = hashlib.sha256((artifact / 'build-manifest.json').read_bytes()).hexdigest()
            self.assertEqual(verify(artifact)['manifestSha256'], digest)
            self.assertEqual(verify(artifact, digest)['manifestSha256'], digest)
            for invalid in ('0' * 64, digest.upper(), 'sha256:' + digest, digest + '\n', '', None):
                if invalid is None:
                    continue
                with self.subTest(pin=invalid), self.assertRaises(ValueError):
                    verify(artifact, invalid)

    def test_different_valid_build_cannot_satisfy_reviewed_identity(self):
        with tempfile.TemporaryDirectory() as root:
            reviewed = Path(root) / 'reviewed'
            different = Path(root) / 'different'
            build(reviewed, '/')
            build(different, '/twitch-screen/')
            digest = hashlib.sha256((reviewed / 'build-manifest.json').read_bytes()).hexdigest()
            self.assertEqual(verify(different)['status'], 'passed')
            with self.assertRaisesRegex(ValueError, 'identity'):
                verify(different, digest)
            with self.assertRaisesRegex(ValueError, 'identity'):
                with preview.preview_directory(different, expected_manifest_sha256=digest):
                    self.fail('different valid build served')

    def test_snapshot_pin_is_rechecked_after_input_replacement(self):
        with tempfile.TemporaryDirectory() as root:
            artifact = Path(root) / 'artifact'
            build(artifact, '/')
            digest = hashlib.sha256((artifact / 'build-manifest.json').read_bytes()).hexdigest()
            real_verify = preview.verify

            def replace_after_admission(directory, *args, **kwargs):
                result = real_verify(directory, *args, **kwargs)
                if Path(directory) == artifact:
                    build(artifact, '/twitch-screen/')
                return result

            with patch.object(preview, 'verify', side_effect=replace_after_admission):
                with self.assertRaisesRegex(ValueError, 'identity'):
                    with preview.preview_directory(artifact, expected_manifest_sha256=digest):
                        self.fail('replaced valid artifact served')

    def test_matching_manifest_identity_does_not_skip_content_checks(self):
        with tempfile.TemporaryDirectory() as root:
            artifact = Path(root) / 'artifact'
            build(artifact)
            digest = hashlib.sha256((artifact / 'build-manifest.json').read_bytes()).hexdigest()
            (artifact / 'assets/demo.js').write_text('changed after reviewed build')
            with self.assertRaisesRegex(ValueError, 'content mismatch'):
                verify(artifact, digest)
            result = subprocess.run(
                [sys.executable, str(ROOT / 'site/tools/verify_artifact.py'), str(artifact),
                 '--expected-manifest-sha256', digest], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            self.assertIn('content mismatch', result.stderr)
            self.assertNotIn('Traceback', result.stderr)

    def test_preview_cli_bad_pin_rejects_before_listener_and_cleans(self):
        with tempfile.TemporaryDirectory() as root:
            artifact = Path(root) / 'artifact'
            scratch = Path(root) / 'scratch'
            scratch.mkdir()
            build(artifact)
            archive = Path(root) / 'artifact.zip'
            with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
                for member in sorted(artifact.rglob('*')):
                    if member.is_file():
                        bundle.write(member, member.relative_to(artifact).as_posix())
            for arguments in (['--artifact', str(artifact)], ['--archive', str(archive)], []):
                result = subprocess.run(
                    [sys.executable, str(ROOT / 'site/tools/preview.py'), *arguments,
                     '--expected-manifest-sha256', '0' * 64, '--port', '0'],
                    env=dict(os.environ, TMPDIR=str(scratch)), capture_output=True,
                    text=True, timeout=10)
                self.assertEqual(result.returncode, 1)
                self.assertIn('preview failed:', result.stderr)
                self.assertNotIn('Traceback', result.stderr)
                self.assertNotIn('Preview:', result.stdout)
                self.assertEqual(list(scratch.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
