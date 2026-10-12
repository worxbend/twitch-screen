# SPDX-License-Identifier: MIT
"""Synthetic metadata admission fixtures; never claimed as hosted execution evidence."""
import hashlib
import json
import os
from unittest.mock import patch
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'site/tools'))
from check_hosted_identity import API, WORKFLOW, check, read_metadata

SHA = 'a' * 40


def fixtures():
    run = {'id': 10, 'repository': {'id': 20, 'full_name': 'worxbend/twitch-screen'},
           'head_repository': {'id': 21}, 'head_sha': SHA, 'head_branch': 'synthetic-branch',
           'path': WORKFLOW, 'url': API + '/actions/runs/10', 'event': 'pull_request',
           'status': 'completed', 'conclusion': 'success', 'run_attempt': 1}
    artifact = {'id': 30, 'name': 'twitch-screen-site-' + SHA, 'expired': False,
                'url': API + '/actions/artifacts/30',
                'archive_download_url': API + '/actions/artifacts/30/zip',
                'size_in_bytes': 1000, 'digest': 'sha256:' + 'b' * 64,
                'workflow_run': {'id': 10, 'repository_id': 20, 'head_repository_id': 21,
                                 'head_sha': SHA, 'head_branch': 'synthetic-branch'}}
    return run, {'total_count': 1, 'artifacts': [artifact]}


class HostedIdentityTests(unittest.TestCase):
    def accept(self, run, artifacts):
        return check(run, artifacts, 10, 30, SHA, 'pull_request')

    def test_exact_run_and_artifact_match_without_authenticity_or_publication_claim(self):
        result = self.accept(*fixtures())
        self.assertEqual(result['status'], 'metadata-matched')
        self.assertEqual(result['artifactId'], 30)
        self.assertEqual(result['archiveDigest'], 'sha256:' + 'b' * 64)
        for key in ('metadataAuthenticated', 'artifactBytesVerified',
                    'environmentProtectionVerified', 'publicationAuthorized'):
            self.assertIs(result[key], False)
        # PR head repositories may be forks; compare their exact IDs rather than imposing origin equality.
        self.assertEqual(result['headSha'], SHA)

    def test_run_identity_status_and_attempt_mismatches_reject(self):
        for key, value in (('id', True), ('id', 11), ('path', '.github/workflows/ci.yml'),
                           ('url', API + '/actions/runs/11'), ('head_sha', 'c' * 40),
                           ('event', 'push'), ('status', 'in_progress'), ('conclusion', 'failure'),
                           ('run_attempt', 2), ('run_attempt', True), ('head_branch', None)):
            run, artifacts = fixtures()
            run[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.accept(run, artifacts)
        for key, value in (('repository', None), ('repository', {'id': True, 'full_name': 'worxbend/twitch-screen'}),
                           ('repository', {'id': 20, 'full_name': 'other/repository'}), ('head_repository', [])):
            run, artifacts = fixtures()
            run[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.accept(run, artifacts)

    def test_expected_identifiers_are_literal_and_typed(self):
        run, artifacts = fixtures()
        for args in ((True, 30, SHA, 'pull_request'), (10, False, SHA, 'pull_request'),
                     (10, 30, SHA.upper(), 'pull_request'), (10, 30, SHA + '\n', 'pull_request'),
                     (10, 30, 'a' * 39, 'pull_request'), (10, 30, SHA, 'pull_request_target')):
            with self.subTest(args=args), self.assertRaises(ValueError):
                check(run, artifacts, *args)

    def test_artifact_identity_expiry_size_and_digest_mismatches_reject(self):
        for key, value in (('id', True), ('id', 31), ('name', 'twitch-screen-site-' + 'c' * 40),
                           ('expired', True), ('expired', 0), ('url', 'https://example.invalid/30'),
                           ('archive_download_url', API + '/actions/artifacts/31/zip'),
                           ('size_in_bytes', True), ('size_in_bytes', 0), ('size_in_bytes', 16777217),
                           ('digest', None), ('digest', 'sha256:' + 'B' * 64), ('digest', 'b' * 64),
                           ('workflow_run', [])):
            run, artifacts = fixtures()
            artifacts['artifacts'][0][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.accept(run, artifacts)

    def test_artifact_run_head_and_repository_must_correlate(self):
        for key, value in (('id', 11), ('id', True), ('repository_id', 21),
                           ('head_repository_id', 20), ('head_sha', 'c' * 40), ('head_branch', 'other')):
            run, artifacts = fixtures()
            artifacts['artifacts'][0]['workflow_run'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.accept(run, artifacts)

    def test_complete_inventory_and_unique_ids_required(self):
        run, artifacts = fixtures()
        cases = [[], {}, {'total_count': True, 'artifacts': artifacts['artifacts']},
                 {'total_count': 2, 'artifacts': artifacts['artifacts']},
                 {'total_count': 1, 'artifacts': [None]}, {'total_count': 0, 'artifacts': []},
                 {'total_count': 2, 'artifacts': artifacts['artifacts'] * 2},
                 {'total_count': 101, 'artifacts': artifacts['artifacts'] * 101}]
        for value in cases:
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.accept(run, value)

    def test_archive_bytes_match_independent_pin_without_content_or_hosted_claim(self):
        # Deliberately not a ZIP: this guard correlates bytes, not static-site policy.
        payload = b'synthetic archive identity fixture, not hosted output'
        digest = hashlib.sha256(payload).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'fixture.zip'
            archive.write_bytes(payload)
            run, artifacts = fixtures()
            artifact = artifacts['artifacts'][0]
            artifact['size_in_bytes'] = len(payload)
            artifact['digest'] = 'sha256:' + digest
            result = check(run, artifacts, 10, 30, SHA, 'pull_request', archive, digest)
            self.assertIs(result['archiveBytesMatched'], True)
            self.assertEqual(result['archiveSha256'], digest)
            self.assertEqual(result['archiveSizeBytes'], len(payload))
            for key in ('metadataAuthenticated', 'artifactBytesVerified',
                        'environmentProtectionVerified', 'publicationAuthorized'):
                self.assertIs(result[key], False)
            self.assertEqual(archive.read_bytes(), payload)
            for pin in ('a' * 64, digest.upper(), digest + '\n', '', None):
                with self.subTest(pin=pin), self.assertRaises(ValueError):
                    check(run, artifacts, 10, 30, SHA, 'pull_request', archive, pin)
            with self.assertRaises(ValueError):
                check(run, artifacts, 10, 30, SHA, 'pull_request', None, digest)

    def test_archive_pin_and_metadata_reject_before_file_read(self):
        run, artifacts = fixtures()
        with patch('check_hosted_identity.read_member') as reader:
            with self.assertRaises(ValueError):
                check(run, artifacts, 10, 30, SHA, 'pull_request', Path('absent.zip'), 'a' * 64)
            reader.assert_not_called()
            run['path'] = '.github/workflows/ci.yml'
            with self.assertRaises(ValueError):
                check(run, artifacts, 10, 30, SHA, 'pull_request', Path('absent.zip'), 'b' * 64)
            reader.assert_not_called()

    def test_archive_size_digest_and_opened_inode_bounds(self):
        payload = b'synthetic bytes'
        digest = hashlib.sha256(payload).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            archive = directory / 'fixture.zip'
            archive.write_bytes(payload)
            run, artifacts = fixtures()
            artifact = artifacts['artifacts'][0]
            artifact.update(size_in_bytes=len(payload), digest='sha256:' + digest)
            for changed in (payload + b'x', payload[:-1], b'x' * len(payload)):
                archive.write_bytes(changed)
                with self.subTest(changed=changed), self.assertRaises(ValueError):
                    check(run, artifacts, 10, 30, SHA, 'pull_request', archive, digest)
            archive.write_bytes(payload)
            alias = directory / 'alias.zip'
            alias.symlink_to(archive)
            fifo = directory / 'fifo.zip'
            os.mkfifo(fifo)
            oversized = directory / 'oversized.zip'
            with oversized.open('wb') as output:
                output.truncate(16777217)
            for path in (alias, fifo, oversized, directory, directory / 'missing.zip'):
                with self.subTest(path=path), self.assertRaises((OSError, ValueError)):
                    check(run, artifacts, 10, 30, SHA, 'pull_request', path, digest)

    def test_archive_cli_matches_bytes_and_rejects_wrong_or_missing_pin(self):
        payload = b'synthetic CLI fixture, not hosted ZIP'
        digest = hashlib.sha256(payload).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            archive = directory / 'fixture.zip'
            archive.write_bytes(payload)
            run, artifacts = fixtures()
            artifacts['artifacts'][0].update(size_in_bytes=len(payload), digest='sha256:' + digest)
            run_path, artifacts_path = directory / 'run.json', directory / 'artifacts.json'
            run_path.write_text(json.dumps(run))
            artifacts_path.write_text(json.dumps(artifacts))
            command = [sys.executable, str(ROOT / 'site/tools/check_hosted_identity.py'),
                       '--run-json', str(run_path), '--artifacts-json', str(artifacts_path),
                       '--run-id', '10', '--artifact-id', '30', '--head-sha', SHA,
                       '--event', 'pull_request', '--archive', str(archive)]
            result = subprocess.run(command + ['--expected-archive-sha256', digest],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(json.loads(result.stdout)['archiveBytesMatched'])
            for extra in ([], ['--expected-archive-sha256', 'a' * 64]):
                result = subprocess.run(command + extra, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 1)
                self.assertIn('hosted identity rejected:', result.stderr)
                self.assertNotIn('Traceback', result.stderr)
            archive.write_bytes(b'x' * len(payload))
            result = subprocess.run(command + ['--expected-archive-sha256', digest],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            self.assertNotIn('Traceback', result.stderr)

    def test_metadata_bound_duplicate_keys_special_files_and_cli(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            run, artifacts = fixtures()
            run_path, artifacts_path = root / 'run.json', root / 'artifacts.json'
            run_path.write_text(json.dumps(run))
            artifacts_path.write_text(json.dumps(artifacts))
            command = [sys.executable, str(ROOT / 'site/tools/check_hosted_identity.py'),
                       '--run-json', str(run_path), '--artifacts-json', str(artifacts_path),
                       '--run-id', '10', '--artifact-id', '30', '--head-sha', SHA, '--event', 'pull_request']
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(json.loads(result.stdout)['artifactBytesVerified'])
            for text in ('{"id":10,"id":11}', '[]', '{"number":NaN}', ' ' * 65537, '{invalid'):
                run_path.write_text(text)
                result = subprocess.run(command, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 1)
                self.assertIn('hosted identity rejected:', result.stderr)
                self.assertNotIn('Traceback', result.stderr)
            alias = root / 'alias.json'
            alias.symlink_to(artifacts_path)
            with self.assertRaises(OSError):
                read_metadata(alias)
            with self.assertRaises((OSError, ValueError)):
                read_metadata(root)


if __name__ == '__main__':
    unittest.main()
