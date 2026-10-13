import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location('admin_runtime', TOOLS / 'check_admin_runtime.py')
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ROOT = TOOLS.parents[1]
SNAPSHOT = next(row['value'] for row in json.loads((ROOT / 'protocol/desk-v1-http-examples.json').read_text())['examples']
                if row['schema'] == 'AdminSnapshot')


class RuntimeCleanupTests(unittest.TestCase):
    def test_run_preserves_feed_timeout_reaps_child_and_writes_failure(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as temporary:
            evidence = Path(temporary) / 'evidence'
            assembly = Path(temporary) / 'synthetic.jar'
            assembly.write_bytes(b'not launched: mocked process')
            child = Mock(pid=123)
            child.poll.return_value = None
            child.returncode = -signal.SIGKILL
            def wait(timeout):
                if not child.kill.called:
                    raise subprocess.TimeoutExpired('owned-main', timeout)
                child.poll.return_value = -signal.SIGKILL
                return -signal.SIGKILL
            child.wait.side_effect = wait
            response = Mock(status=200)
            response.getheaders.return_value = []
            response.read.return_value = json.dumps(SNAPSHOT).encode()
            forbidden = Mock(status=403)
            forbidden.getheaders.return_value = []
            forbidden.read.return_value = b'{}'
            connections = []
            for value in (response, forbidden, forbidden, forbidden):
                connection = Mock()
                connection.getresponse.return_value = value
                connections.append(connection)
            failed_feed = Mock()
            failed_feed.getresponse.side_effect = TimeoutError('original feed failure')
            connections.append(failed_feed)
            with patch.object(MODULE.subprocess, 'Popen', return_value=child), \
                    patch.object(MODULE.subprocess, 'run', side_effect=FileNotFoundError('synthetic missing diagnostics')), \
                    patch.object(MODULE.http.client, 'HTTPConnection', side_effect=connections):
                with self.assertRaisesRegex(TimeoutError, 'original feed failure'):
                    MODULE.run(assembly, evidence)
            failed_feed.close.assert_called()
            child.kill.assert_called_once()
            result = json.loads((evidence / 'result.json').read_text())
            self.assertFalse(result['passed'])
            self.assertEqual(result['failure']['type'], 'TimeoutError')
            self.assertEqual(result['failure']['message'], 'original feed failure')
            self.assertEqual(result['failureDiagnostics'], ['jstack: FileNotFoundError', 'jcmd: FileNotFoundError'])
            self.assertTrue(result['mainReaped'])
            self.assertTrue(result['mainKilled'])
            self.assertTrue(result['listenersClosed'])

    def test_real_sigterm_ignoring_child_is_killed_reaped_without_masking_failure(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as temporary:
            evidence = Path(temporary)
            child = subprocess.Popen([sys.executable, '-c',
                                      'import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);print("ready",flush=True);time.sleep(60)'],
                                     stdout=subprocess.PIPE, text=True)
            assert child.stdout is not None
            try:
                self.assertEqual(child.stdout.readline().strip(), 'ready')
                result = {'passed': False}
                original = TimeoutError('primary failure')
                # This is deliberately not a JVM; exercise the real kill/reap without JVM attach probing a Python process.
                with patch.object(MODULE, 'capture_threads', return_value=[]), \
                        self.assertRaisesRegex(TimeoutError, 'primary failure'):
                    try:
                        raise original
                    finally:
                        MODULE.finish_run(result, evidence, child, [], [], lambda *_: None,
                                          failure=original, shutdown_seconds=.05)
                self.assertIsNotNone(child.poll())
                written = json.loads((evidence / 'result.json').read_text())
                self.assertTrue(written['mainKilled'])
                self.assertTrue(written['mainReaped'])
                self.assertFalse(written['passed'])
            finally:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)
                child.stdout.close()

    def test_cleanup_failure_turns_success_red_but_still_writes_result(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as temporary:
            evidence = Path(temporary)
            result = {'passed': True}
            close = Mock(side_effect=OSError('synthetic close failure'))
            with self.assertRaisesRegex(RuntimeError, 'cleanup'):
                MODULE.finish_run(result, evidence, None, [(object(), object())], [], close)
            written = json.loads((evidence / 'result.json').read_text())
            self.assertFalse(written['passed'])
            self.assertTrue(written['cleanupErrors'])


if __name__ == '__main__':
    unittest.main()
