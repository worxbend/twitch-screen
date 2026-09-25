"""The parity gate must fail if its specification assertions stop parsing."""
import importlib.util
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "twitch-screen-firmware/docs/PROTOCOL.md"
GENERATOR = ROOT / "twitch-screen-firmware/test/test_proto_codec/gen_vectors.py"
GENERATED_HEADER = ROOT / "twitch-screen-firmware/test/test_proto_codec/vectors.h"
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

    def test_generated_header_uses_constexpr_declarations(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "vectors.h"
            result = subprocess.run([sys.executable, str(GENERATOR), str(SPEC), str(output)],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            generated = output.read_text(encoding="utf-8")
        self.assertIsNone(re.search(r"^static const\b", generated, re.M))
        self.assertIn("static constexpr", generated)
        self.assertIsNone(re.search(r"^static const\b", GENERATED_HEADER.read_text(encoding="utf-8"), re.M))

    def write_spec(self, directory, text):
        path = Path(directory) / "PROTOCOL.md"
        path.write_text(text, encoding="utf-8")
        return path

    @staticmethod
    def corrupt_v5(text):
        block = text.index("```\n", text.index("### V5.")) + len("```\n")
        return text[:block] + "zz" + text[block + 2:]

    @staticmethod
    def swap_anchors(text):
        start, end = "tsb3-golden-vectors:start", "tsb3-golden-vectors:end"
        return text.replace(start, "\0").replace(end, start).replace("\0", end)

    def test_check_spec_vectors_rejects_renamed_start_anchor(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_spec(directory, SPEC.read_text().replace("tsb3-golden-vectors:start", "renamed"))
            with self.assertRaisesRegex(ValueError, "anchors"):
                vectors.spec_vectors(path)

    def test_check_spec_vectors_rejects_swapped_anchors(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_spec(directory, self.swap_anchors(SPEC.read_text()))
            with self.assertRaisesRegex(ValueError, "anchors"):
                vectors.spec_vectors(path)

    def test_check_spec_vectors_names_vector_with_malformed_hex(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_spec(directory, self.corrupt_v5(SPEC.read_text()))
            with self.assertRaisesRegex(ValueError, r"^V5: malformed hex block"):
                vectors.spec_vectors(path)

    def test_check_relay_vectors_names_vector_with_malformed_hex(self):
        cases = {
            5: 'val V1: String = "0a0b"\nval V5: String = """zz 00"""\n',
            3: 'val V1: String = "0a0b"\nval V3: String = "zz 00"\n',
        }
        for number, source in cases.items():
            with self.subTest(vector=number), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "Tsb3Vectors.scala"
                path.write_text(source, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, rf"V{number}: malformed hex block in relay Tsb3Vectors\.scala"):
                    vectors.relay_vectors(path)

    def test_check_script_fails_cleanly_on_bad_spec(self):
        cases = {
            "anchors": SPEC.read_text().replace("tsb3-golden-vectors:start", "renamed"),
            "V5": self.corrupt_v5(SPEC.read_text()),
        }
        for expected, text in cases.items():
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as directory:
                path = self.write_spec(directory, text)
                result = subprocess.run([sys.executable, str(ROOT / "tools/check_protocol_vectors.py"), str(path)],
                                        capture_output=True, text=True, timeout=30)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Protocol vector check failed", result.stderr)
                self.assertIn(expected, result.stderr)
                self.assertNotIn("Traceback", result.stderr)

    def test_check_script_passes_on_real_tree(self):
        result = subprocess.run([sys.executable, str(ROOT / "tools/check_protocol_vectors.py")],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("All 20 TSB/3 vectors match", result.stdout)
