"""Contract checks for the PlatformIO custom runner (use the PlatformIO Python)."""

import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from platformio.test.result import TestStatus
from platformio.test.runners.base import TestRunnerBase

spec = importlib.util.spec_from_file_location(
    "host_runner", Path(__file__).resolve().parents[1] / "test/test_custom_runner.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RunnerContract(unittest.TestCase):
    def setUp(self):
        self.cases = []
        self.runner = object.__new__(module.CustomTestRunner)
        self.runner.options = SimpleNamespace(verbose=False, without_testing=False)
        self.runner.test_suite = SimpleNamespace(
            test_name="fixture", test_dir="fixture", add_case=self.cases.append
        )
        self.runner._failures = []
        self.runner._saw_summary = False

    def test_missing_summary_is_an_error(self):
        with patch.object(TestRunnerBase, "stage_testing", return_value=None):
            self.runner.stage_testing()
        self.assertEqual([c.status for c in self.cases], [TestStatus.ERRORED])

    def test_crash_preserves_failure_record(self):
        with patch.object(TestRunnerBase, "stage_testing", side_effect=RuntimeError("crash")):
            with self.assertRaisesRegex(RuntimeError, "crash"):
                self.runner.stage_testing()
        self.assertEqual([c.status for c in self.cases], [TestStatus.ERRORED])

    def test_valid_summary_is_preserved(self):
        with patch.object(TestRunnerBase, "stage_testing", side_effect=lambda:
                          self.runner.on_testing_line_output("25 checks, 0 failures\n")):
            self.runner.stage_testing()
        self.assertEqual([c.status for c in self.cases], [TestStatus.PASSED])

    def test_zero_checks_is_failure(self):
        self.runner.on_testing_line_output("0 checks, 0 failures\n")
        self.assertEqual(self.cases[0].status, TestStatus.FAILED)

    def test_skipped_testing_does_not_invent_failure(self):
        self.runner.options.without_testing = True
        self.runner.stage_testing()
        self.assertEqual(self.cases, [])


if __name__ == "__main__":
    unittest.main()
