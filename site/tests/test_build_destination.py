# SPDX-License-Identifier: MIT
"""A rebuild must reject unowned destination types before the first write."""
import os
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from build import build


class BuildDestinationTests(unittest.TestCase):
    def test_special_members_and_extra_directories_fail_before_writes(self):
        for kind in ('extra-directory', 'extra-fifo', 'expected-fifo', 'socket', 'file-directory'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix='ts-build-destination-') as directory:
                root = Path(directory)
                build(root)
                member = root / ('index.html' if kind in ('expected-fifo', 'file-directory') else 'unexpected')
                listener = None
                try:
                    if member.exists():
                        member.unlink()
                    if kind in ('extra-directory', 'file-directory'):
                        member.mkdir()
                    elif kind == 'socket':
                        listener = socket.socket(socket.AF_UNIX)
                        listener.bind(str(member))
                    else:
                        os.mkfifo(member)
                    with patch.object(Path, 'write_bytes', side_effect=AssertionError('write before admission')):
                        with self.assertRaisesRegex(ValueError, 'unowned'):
                            build(root)
                finally:
                    if listener is not None:
                        listener.close()

    def test_owned_partial_destination_rebuilds_exact_bytes(self):
        with tempfile.TemporaryDirectory(prefix='ts-build-partial-') as directory:
            root = Path(directory)
            build(root)
            original = {path.relative_to(root): path.read_bytes() for path in root.rglob('*') if path.is_file()}
            (root / 'index.html').unlink()
            (root / 'assets/demo.js').write_bytes(b'old build')
            build(root)
            self.assertEqual(original, {path.relative_to(root): path.read_bytes()
                                       for path in root.rglob('*') if path.is_file()})


if __name__ == '__main__':
    unittest.main()
