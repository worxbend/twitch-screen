# SPDX-License-Identifier: MIT
"""Opened-inode admission survives artifact path replacement without blocking."""
import os
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import build
import verify_artifact


class OpenedArtifactTests(unittest.TestCase):
    def test_regular_bytes_and_bounds(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / 'member'
            target.write_bytes(b'original')
            self.assertEqual(verify_artifact.read_member(root, 'member', 8), b'original')
            self.assertEqual(verify_artifact.read_member(root, 'member', 9), b'original')
            with self.assertRaisesRegex(ValueError, 'bound'):
                verify_artifact.read_member(root, 'member', 7)
            target.write_bytes(b'')
            self.assertEqual(verify_artifact.read_member(root, 'member', 0), b'')
            for name in ('../member', '/member'):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    verify_artifact.read_member(root, name, 8)

    def test_special_members_fail_without_reading(self):
        for kind in ('fifo', 'socket', 'directory', 'symlink'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                target = Path(root) / 'member'
                with socket.socket(socket.AF_UNIX) as listener:
                    if kind == 'fifo':
                        os.mkfifo(target)
                    elif kind == 'socket':
                        listener.bind(str(target))
                    elif kind == 'directory':
                        target.mkdir()
                    else:
                        real = Path(root) / 'real'
                        real.write_bytes(b'original')
                        target.symlink_to(real)
                    with self.assertRaises((ValueError, OSError)):
                        verify_artifact.read_member(root, 'member', 8)

    def test_replacement_after_enumeration_rejected(self):
        for kind in ('fifo', 'symlink', 'ancestor', 'grown'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                artifact = Path(root) / 'artifact'
                build.build(artifact)
                read = verify_artifact.read_member
                replaced = False

                def replace_before_open(directory, name, limit):
                    nonlocal replaced
                    if not replaced and name == 'assets/demo.js':
                        replaced = True
                        target = artifact / ('assets' if kind == 'ancestor' else name)
                        moved = Path(root) / 'moved'
                        target.rename(moved)
                        if kind == 'fifo':
                            os.mkfifo(target)
                        elif kind == 'grown':
                            with target.open('wb') as member:
                                member.truncate(build.MAX_RAW_BYTES + 1)
                        else:
                            target.symlink_to(moved, target_is_directory=moved.is_dir())
                    return read(directory, name, limit)

                with patch.object(verify_artifact, 'read_member', side_effect=replace_before_open):
                    with self.assertRaises((ValueError, OSError)):
                        verify_artifact.verify(artifact)
                self.assertTrue(replaced)


if __name__ == '__main__':
    unittest.main()
