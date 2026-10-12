# SPDX-License-Identifier: MIT
"""Malformed artifact metadata must fail closed with bounded CLI diagnostics."""
import copy
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from build import build
from verify_artifact import verify


class ManifestTests(unittest.TestCase):
    def test_manifest_requires_exact_typed_schema(self):
        with tempfile.TemporaryDirectory(prefix="twitch-screen-manifest-test-") as directory:
            root = Path(directory)
            original = build(root)
            cases = [None, [], 1, {}, {**original, "schemaVersion": True},
                     {**original, "schemaVersion": 1.0}, {**original, "files": []},
                     {**original, "gzipBytes": float(original["gzipBytes"])},
                     {**original, "extra": "unreviewed"}, {**original, "basePath": None}]
            for canonical in (1, [], "http://example.com/twitch-screen/",
                              "https://example.com/", "https://example.com/twitch-screen/?x=y",
                              "https://user@example.com/twitch-screen/"):
                cases.append({**original, "canonical": canonical})
            for key, value in (("bytes", True), ("bytes", -1), ("bytes", 0.0),
                               ("sha256", 1), ("sha256", "not-a-hash"), ("extra", "value")):
                changed = copy.deepcopy(original)
                changed["files"][".nojekyll"][key] = value
                cases.append(changed)
            for index, manifest in enumerate(cases):
                (root / "build-manifest.json").write_text(json.dumps(manifest))
                with self.subTest(index=index), self.assertRaises(ValueError):
                    verify(root)

    def test_canonical_must_match_rendered_metadata(self):
        with tempfile.TemporaryDirectory(prefix="twitch-screen-canonical-test-") as directory:
            root = Path(directory)
            manifest = build(root, canonical="https://worxbend.github.io/twitch-screen/")
            for canonical in (None, "https://example.com/twitch-screen/"):
                manifest["canonical"] = canonical
                (root / "build-manifest.json").write_text(json.dumps(manifest))
                with self.subTest(canonical=canonical), self.assertRaisesRegex(ValueError, "canonical"):
                    verify(root)
            manifest = build(root)
            manifest["canonical"] = "https://example.com/twitch-screen/"
            (root / "build-manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "canonical"):
                verify(root)

    def test_nested_nonfinite_and_oversized_json_fail_closed(self):
        from build import strict_json
        for text in ('[' * 1100 + '0' + ']' * 1100, '{"x":NaN}',
                     '{"x":Infinity}', '{"x":1e999}', '"' + 'x' * 65536 + '"'):
            with self.subTest(length=len(text)), self.assertRaises(ValueError):
                strict_json(text)
        self.assertEqual(strict_json(json.dumps({'text': '[' * 1000 + '\\"' + ']' * 1000})),
                         {'text': '[' * 1000 + '\\"' + ']' * 1000})
        boundary = '[' * 64 + '0' + ']' * 64
        self.assertEqual(strict_json(boundary), json.loads(boundary))

    def test_sparse_oversized_member_rejected_before_read(self):
        with tempfile.TemporaryDirectory(prefix="twitch-screen-large-artifact-") as directory:
            root = Path(directory)
            build(root)
            with (root / 'assets/demo.js').open('r+b') as member:
                member.truncate(9 * 1024 * 1024)
            with self.assertRaisesRegex(ValueError, 'uncompressed'):
                verify(root)

    def test_nonpublic_directories_and_special_members_rejected_before_read(self):
        from unittest.mock import patch
        for kind in ('directory', 'fifo', 'socket', 'expected-fifo'):
            with tempfile.TemporaryDirectory(prefix="ts-artifact-types-") as directory:
                root = Path(directory)
                build(root)
                member = root / ('assets/demo.js' if kind == 'expected-fifo' else 'unexpected')
                listener = None
                try:
                    if kind == 'directory':
                        member.mkdir()
                    elif kind == 'socket':
                        listener = socket.socket(socket.AF_UNIX)
                        listener.bind(str(member))
                    else:
                        if member.exists():
                            member.unlink()
                        os.mkfifo(member)
                    with self.subTest(kind=kind), patch.object(
                            Path, 'read_bytes', side_effect=AssertionError('read before admission')):
                        with self.assertRaises(ValueError):
                            verify(root)
                finally:
                    if listener is not None:
                        listener.close()

    def test_nested_manifest_cli_has_no_traceback(self):
        with tempfile.TemporaryDirectory(prefix="twitch-screen-nested-manifest-") as directory:
            root = Path(directory)
            build(root)
            (root / 'build-manifest.json').write_text('[' * 1100 + '0' + ']' * 1100)
            process = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1]
                                     / 'tools/verify_artifact.py'), str(root)],
                                     capture_output=True, text=True, timeout=5)
            self.assertEqual(process.returncode, 1)
            self.assertIn('artifact invalid:', process.stderr)
            self.assertNotIn('Traceback', process.stderr)

    def test_cli_malformed_manifest_has_no_traceback(self):
        with tempfile.TemporaryDirectory(prefix="twitch-screen-manifest-cli-") as directory:
            root = Path(directory)
            build(root)
            (root / "build-manifest.json").write_text("[]")
            process = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1]
                                     / "tools/verify_artifact.py"), str(root)],
                                     capture_output=True, text=True, timeout=5)
            self.assertEqual(process.returncode, 1)
            self.assertIn("artifact invalid:", process.stderr)
            self.assertNotIn("Traceback", process.stderr)


if __name__ == "__main__":
    unittest.main()
