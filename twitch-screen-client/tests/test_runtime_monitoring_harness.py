"""Intent versus revision at source-switch boundaries; synthetic fixtures only."""
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
SPEC = importlib.util.spec_from_file_location('runtime_monitoring', ROOT / 'tools/check_runtime_monitoring.py')
assert SPEC is not None and SPEC.loader is not None
MONITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITOR)


class MonitoringBoundaryTests(unittest.TestCase):
    def before(self):
        return {'relayEpoch': '0123456789abcdef', 'revision': 1,
                'twitchDisplayEnabled': False, 'preferredDashboard': 'cpu'}

    def test_source_fence_revision_is_not_display_intent_mutation(self):
        before = self.before()
        self.assertTrue(MONITOR.verify_display_independent(before, {**before, 'revision': 2}))
        self.assertTrue(MONITOR.verify_display_independent(before, before))

    def test_reject_changed_override_or_preference(self):
        before = self.before()
        for field, value in (('twitchDisplayEnabled', True), ('preferredDashboard', 'generic')):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                MONITOR.verify_display_independent(before, {**before, 'revision': 2, field: value})

    def test_reject_regressed_revision_or_changed_epoch(self):
        before = self.before()
        for field, value in (('revision', 0), ('relayEpoch', '0123456789abcdee')):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                MONITOR.verify_display_independent(before, {**before, field: value})
