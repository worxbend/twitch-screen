"""Pinned baseline discovery must work from shallow CI checkouts without changing their Git state."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASELINE = 'b30b91f4a29bc70a50428ac1bf6894e3ba4b7ac2'


class BaselineSourcesTests(unittest.TestCase):
    def resolver(self):
        spec = importlib.util.spec_from_file_location('baseline_sources', Path(__file__).with_name('baseline_sources.py'))
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.baseline_repository

    def git(self, directory, *args):
        return subprocess.check_output(['git', '-C', str(directory), *args], text=True, stderr=subprocess.PIPE).strip()

    def test_present_commit_uses_local_objects_without_fetch(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as temporary:
            self.assertEqual(self.resolver()(ROOT, BASELINE), ROOT)

    def test_shallow_checkout_requires_pinned_history_without_modifying_git_state(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as temporary:
            scratch = Path(temporary)
            origin = scratch / 'origin'
            subprocess.run(['git', 'init', '-q', str(origin)], check=True)
            self.git(origin, 'config', 'user.name', 'Synthetic Test')
            self.git(origin, 'config', 'user.email', 'synthetic@example.invalid')
            (origin / 'source.txt').write_text('immutable baseline\n')
            self.git(origin, 'add', 'source.txt')
            self.git(origin, 'commit', '-qm', 'baseline')
            baseline = self.git(origin, 'rev-parse', 'HEAD')
            (origin / 'source.txt').write_text('current\n')
            self.git(origin, 'commit', '-qam', 'current')
            checkout = scratch / 'checkout'
            subprocess.run(['git', 'clone', '-q', '--depth=1', origin.as_uri(), str(checkout)], check=True)
            before = self.git(checkout, 'rev-parse', 'HEAD')
            with self.assertRaisesRegex(RuntimeError, 'fetch-depth: 0'):
                self.resolver()(checkout, baseline)
            self.assertEqual(self.git(checkout, 'rev-parse', 'HEAD'), before)
            self.assertEqual(self.git(checkout, 'rev-parse', '--is-shallow-repository'), 'true')
            self.assertNotEqual(subprocess.run(['git', '-C', str(checkout), 'cat-file', '-e', baseline],
                                              capture_output=True).returncode, 0)

    def test_malformed_revision_is_rejected_before_git(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as temporary:
            with self.assertRaises(ValueError):
                self.resolver()(Path(temporary) / 'missing', '--all')


if __name__ == '__main__':
    unittest.main(verbosity=2)
