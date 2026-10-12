"""Atomic private diagnostics never reuse a predictable staging member."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from twitch_screen_client.runtime import write_status


class StatusWriterTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='csw-')
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.root.chmod(0o700)
        self.path = self.root / 'status.json'

    def test_existing_staging_hardlink_never_truncates_target(self):
        target = self.root / 'untouched'
        target.write_text('PRIVATE SYNTHETIC SENTINEL')
        os.link(target, self.path.with_suffix('.new'))
        write_status(self.path, {'captured': 1})
        self.assertEqual(target.read_text(), 'PRIVATE SYNTHETIC SENTINEL')
        self.assertEqual(json.loads(self.path.read_text()), {'captured': 1})
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)

    def test_failed_encoding_preserves_previous_snapshot_and_members(self):
        write_status(self.path, {'captured': 1})
        before = set(self.root.iterdir())
        for invalid in ({'captured': float('nan')}, {'body': 'x' * 17000}):
            with self.assertRaises(ValueError):
                write_status(self.path, invalid)
            self.assertEqual(json.loads(self.path.read_text()), {'captured': 1})
            self.assertEqual(set(self.root.iterdir()), before)

    def test_replace_failure_closes_and_removes_only_owned_staging_file(self):
        write_status(self.path, {'captured': 1})
        unrelated = self.path.with_suffix('.new')
        unrelated.write_text('sentinel')
        before = set(self.root.iterdir())
        with patch('twitch_screen_client.runtime.os.replace', side_effect=OSError('synthetic')):
            with self.assertRaises(OSError):
                write_status(self.path, {'captured': 2})
        self.assertEqual(set(self.root.iterdir()), before)
        self.assertEqual(unrelated.read_text(), 'sentinel')
        self.assertEqual(json.loads(self.path.read_text()), {'captured': 1})

    def test_replacement_never_writes_through_final_symlink(self):
        target = self.root / 'target'
        target.write_text('sentinel')
        self.path.symlink_to(target)
        write_status(self.path, {'captured': 1})
        self.assertFalse(self.path.is_symlink())
        self.assertEqual(target.read_text(), 'sentinel')
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
