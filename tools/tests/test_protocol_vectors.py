"""The parity gate must fail if its specification assertions stop parsing."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "twitch-screen-firmware/docs/PROTOCOL.md"
GENERATOR = ROOT / "twitch-screen-firmware/test/test_proto_codec/gen_vectors.py"
module_spec = importlib.util.spec_from_file_location("vectors", ROOT / "tools/check_protocol_vectors.py")
vectors = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(vectors)


class ProtocolVectorsTest(unittest.TestCase):
    def generate(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "PROTOCOL.md"
            path.write_text(text, encoding="utf-8")
            return subprocess.run([sys.executable, str(GENERATOR), str(path), str(Path(directory) / "vectors.h")],
                                  capture_output=True, text=True, timeout=10)

    def test_all_normative_vectors_are_available_to_smoke_test(self):
        self.assertEqual(set(vectors.spec_vectors()), set(range(1, 21)))
        self.assertEqual(len(vectors.spec_vectors()[1]), 68)

    def test_missing_anchor_reports_actionable_error(self):
        result = self.generate(SPEC.read_text().replace("tsb3-golden-vectors:start", "renamed"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("anchors", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_lost_prose_assertions_fail_even_when_bytes_are_valid(self):
        result = self.generate(SPEC.read_text().replace("- `", "* `"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no prose expectations checked", result.stderr)

    def test_lost_assertions_in_one_vector_fail(self):
        text = SPEC.read_text()
        begin, end = text.index("### V2."), text.index("### V3.")
        result = self.generate(text[:begin] + text[begin:end].replace("- `", "* `") + text[end:])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no prose expectations checked", result.stderr)
