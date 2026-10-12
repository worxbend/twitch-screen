# SPDX-License-Identifier: MIT
"""Preview existing artifact bytes without rebuilding or exposing mutable inputs."""
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import build
import preview


class ArtifactPreviewTests(unittest.TestCase):
    def test_snapshot_exact_bytes_base_http_and_cleanup(self):
        for base in ('/', '/twitch-screen/'):
            with self.subTest(base=base), tempfile.TemporaryDirectory() as root:
                artifact = Path(root) / 'artifact'
                build.build(artifact, base, 'https://worxbend.github.io' + base)
                expected = {name: (artifact / name).read_bytes() for name in preview.PUBLIC_FILES}
                with patch.object(preview, 'build', side_effect=AssertionError('must not rebuild')):
                    with preview.preview_directory(artifact) as (snapshot, selected):
                        self.assertEqual(selected, base)
                        self.assertNotEqual(snapshot, artifact)
                        self.assertEqual(expected, {name: (snapshot / name).read_bytes()
                                                    for name in preview.PUBLIC_FILES})
                        (artifact / 'index.html').write_text('changed after snapshot')
                        with preview.server(snapshot, selected) as http:
                            worker = threading.Thread(target=http.serve_forever)
                            worker.start()
                            try:
                                for name, data in expected.items():
                                    with urlopen(f'http://127.0.0.1:{http.server_port}{base}{name}',
                                                 timeout=3) as response:
                                        self.assertEqual(response.read(), data)
                            finally:
                                http.shutdown()
                                worker.join(timeout=3)
                                self.assertFalse(worker.is_alive())
                    self.assertFalse(snapshot.exists())
                self.assertEqual((artifact / 'index.html').read_text(), 'changed after snapshot')

    def test_bad_hash_extra_member_and_base_mismatch_reject(self):
        with self.assertRaises(ValueError):
            with preview.preview_directory(base=''):
                self.fail('empty explicit base accepted')
        for mutation in ('hash', 'extra', 'base'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                artifact = Path(root) / 'artifact'
                build.build(artifact)
                if mutation == 'hash':
                    (artifact / 'assets/demo.js').write_text('changed')
                elif mutation == 'extra':
                    (artifact / 'private.txt').write_text('not public')
                with self.assertRaises(ValueError):
                    with preview.preview_directory(artifact, '/' if mutation == 'base' else None):
                        self.fail('invalid artifact yielded a preview')

    def test_symlink_replacement_after_verification_rejects_before_yield(self):
        for member in ('index.html', 'assets'):
            with self.subTest(member=member), tempfile.TemporaryDirectory() as root:
                artifact = Path(root) / 'artifact'
                build.build(artifact)
                real_verify = preview.verify

                def replace_after_verify(directory):
                    result = real_verify(directory)
                    if Path(directory) == artifact:
                        target = artifact / member
                        moved = Path(root) / 'moved'
                        target.rename(moved)
                        target.symlink_to(moved, target_is_directory=moved.is_dir())
                    return result

                with patch.object(preview, 'verify', side_effect=replace_after_verify):
                    with self.assertRaises((ValueError, OSError)):
                        with preview.preview_directory(artifact):
                            self.fail('symlink replacement yielded a preview')

    def test_change_during_copy_reverified_before_yield(self):
        with tempfile.TemporaryDirectory() as root:
            artifact = Path(root) / 'artifact'
            build.build(artifact)
            real_verify = preview.verify

            def mutate_after_verify(directory):
                result = real_verify(directory)
                if Path(directory) == artifact:
                    (artifact / 'index.html').write_text('changed during admission')
                return result

            with patch.object(preview, 'verify', side_effect=mutate_after_verify):
                with self.assertRaisesRegex(ValueError, 'mismatch'):
                    with preview.preview_directory(artifact):
                        self.fail('changed artifact yielded a preview')


if __name__ == '__main__':
    unittest.main()
