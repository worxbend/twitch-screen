"""Local diagnostics are historical unless the exact source lock is held."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from twitch_screen_client.cli import local_status
from twitch_screen_client.config import parse
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.runtime import initial_status, instance, write_status


class DiagnosticsTests(unittest.TestCase):
    def test_running_stale_and_stopped_status_without_content(self):
        config = parse({'relay_url': 'http://localhost', 'source_id': 'status-test'})
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='cs-') as directory:
            Path(directory).chmod(0o700)
            with patch.dict(os.environ, {'XDG_RUNTIME_DIR': directory}):
                self.assertEqual(local_status(config), {'state': 'not_running'})
                with instance(config.source_id) as path:
                    value = {**initial_status(), 'observedBoottimeMs': boottime_ms()}
                    write_status(path, value)
                    self.assertEqual(local_status(config)['state'], 'running')
                    write_status(path, {**value, 'observedBoottimeMs': boottime_ms() - 6000})
                    self.assertEqual(local_status(config)['state'], 'stale')
                self.assertEqual(local_status(config)['state'], 'not_running')
                write_status(path, {**value, 'body': 'synthetic private content'})
                with self.assertRaises(ValueError):
                    local_status(config)
