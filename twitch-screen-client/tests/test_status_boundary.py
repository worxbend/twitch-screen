"""Private local diagnostics reject content and unsafe members before reading."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from twitch_screen_client.cli import local_status
from twitch_screen_client.config import parse
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.runtime import initial_status, instance, write_status


class StatusBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='csb-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.root.chmod(0o700)
        environment = patch.dict(os.environ, {'XDG_RUNTIME_DIR': str(self.root)})
        environment.start()
        self.addCleanup(environment.stop)
        self.config = parse({'relay_url': 'http://localhost', 'source_id': 'status-boundary'})
        self.path = self.root / 'twitch-screen-client-status-boundary.json'
        self.lock = self.path.with_suffix('.lock')
        self.value = {**initial_status(), 'observedBoottimeMs': boottime_ms()}

    def raw_status(self, wire):
        self.path.write_text(wire)
        self.path.chmod(0o600)

    def test_allowed_keys_cannot_carry_arbitrary_content_or_wrong_types(self):
        invalid = ({'capture': 'PRIVATE SYNTHETIC CONTENT'}, {'cpu': ['private']},
                   {'relay': {'body': 'private'}}, {'captured': 'private'},
                   {'notification_accepted': True}, {'cpu_dropped': -1},
                   {'pendingNotifications': 33}, {'pendingNotificationBytes': 65537},
                   {'pendingCpu': 1}, {'pid': 0}, {'observedBoottimeMs': 'private'},
                   {'observedBoottimeMs': True}, {'observedBoottimeMs': -1})
        for change in invalid:
            with self.subTest(fields=list(change)):
                self.raw_status(json.dumps({**self.value, **change}))
                with self.assertRaises(ValueError):
                    local_status(self.config)

    def test_duplicate_nonfinite_nested_and_missing_age_rejected(self):
        invalid = ('{"capture":"available","capture":"disabled","observedBoottimeMs":0}',
                   '{"captured":NaN,"observedBoottimeMs":0}',
                   '{"captured":1e9999,"observedBoottimeMs":0}',
                   '{"captured":' + '[' * 100 + '0' + ']' * 100 + ',"observedBoottimeMs":0}',
                   json.dumps(initial_status()))
        for wire in invalid:
            with self.subTest(size=len(wire)):
                self.raw_status(wire)
                with self.assertRaises(ValueError):
                    local_status(self.config)

    def test_future_observation_never_becomes_fresh(self):
        with instance(self.config.source_id):
            write_status(self.path, {**self.value, 'observedBoottimeMs': boottime_ms() + 10000})
            status = local_status(self.config)
            self.assertEqual(status['state'], 'stale')
            self.assertIsNone(status['observationAgeMs'])

    def test_nonprivate_status_and_runtime_directory_rejected(self):
        self.raw_status(json.dumps(self.value))
        self.path.chmod(0o644)
        with self.assertRaises(ValueError):
            local_status(self.config)
        self.path.chmod(0o600)
        self.root.chmod(0o755)
        with self.assertRaises(ValueError):
            local_status(self.config)

    def test_lock_nonregular_or_nonprivate_rejected(self):
        self.raw_status(json.dumps(self.value))
        self.lock.mkdir()
        with self.assertRaises(ValueError):
            local_status(self.config)
        self.lock.rmdir()
        self.lock.write_text('')
        self.lock.chmod(0o644)
        with self.assertRaises(ValueError):
            local_status(self.config)
        with self.assertRaises(ValueError):
            with instance(self.config.source_id):
                self.fail('insecure existing lock admitted')

    def test_fifo_status_cli_returns_without_blocking_or_leaking(self):
        os.mkfifo(self.path, 0o600)
        config = self.root / 'config.toml'
        config.write_text('relay_url="http://localhost"\nsource_id="status-boundary"\n')
        # Source and installed-suite environments determine the child import path.
        result = subprocess.run([sys.executable, '-m', 'twitch_screen_client', 'status',
                                 '--config', str(config)], capture_output=True, timeout=2)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b'')
        self.assertNotIn(str(self.root).encode(), result.stderr)
        self.assertNotIn(b'Traceback', result.stderr)

    def test_actual_cli_bad_typed_status_has_safe_error(self):
        self.raw_status(json.dumps({**self.value, 'observedBoottimeMs': []}))
        config = self.root / 'config.toml'
        config.write_text('relay_url="http://localhost"\nsource_id="status-boundary"\n')
        result = subprocess.run([sys.executable, '-m', 'twitch_screen_client', 'status',
                                 '--config', str(config)], capture_output=True, timeout=2)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b'')
        self.assertNotIn(b'Traceback', result.stderr)
