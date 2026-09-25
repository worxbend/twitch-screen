"""PlatformIO test runner for the two host suites.

Why a custom runner rather than Unity: both suites assert the SPEC's bytes — the
golden vectors of docs/PROTOCOL.md §18 — and they do it by comparing a frame the
suite builds from §6's offset tables against the hex block in the document. That
is the property §18 exists for, a framework would not improve it, and rewriting
several thousand assertions into Unity cases would mean rewriting the one part of
this repository that is currently pinned to the document.

What this adds is the thing that was missing: §17 requires both implementations to
run their vector suites under a command the repository configures. Before this file
the firmware's only ran if a human typed a g++ line out of a comment, so a future
edit that moved `actor` from +24 to +28 left `pio run` green and shipped.

    ~/.platformio/penv/bin/pio test -e native

Each suite prints one summary line, `<checks> checks, <failures> failures`, and
exits non-zero if anything failed. A non-zero exit is already fatal to PlatformIO;
the parser below turns the summary into a named test case so that the run reports
how much was actually asserted rather than only whether the process survived.
"""

import re

from platformio.test.result import TestCase, TestCaseSource, TestStatus
from platformio.test.runners.base import TestRunnerBase

SUMMARY = re.compile(r"^(\d+) checks, (\d+) failures\s*$")
FAILURE = re.compile(r"^FAIL")


class CustomTestRunner(TestRunnerBase):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._failures = []

    def on_testing_line_output(self, line):
        if self.options.verbose:
            print(line, end="")

        stripped = line.strip()
        if FAILURE.match(stripped):
            self._failures.append(stripped)

        match = SUMMARY.match(stripped)
        if not match:
            return

        checks, failures = int(match.group(1)), int(match.group(2))
        passed = failures == 0
        self.test_suite.add_case(
            TestCase(
                name="%s (%d checks)" % (self.test_suite.test_name, checks),
                status=TestStatus.PASSED if passed else TestStatus.FAILED,
                message=None if passed else "%d of %d checks failed" % (failures, checks),
                stdout="".join(self._failures) or line,
                source=TestCaseSource(filename=self.test_suite.test_dir),
            )
        )
        self._failures = []
