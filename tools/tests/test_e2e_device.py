"""The preflight must not turn missing acceptance into simulated passes."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('e2e_device', Path(__file__).parents[1] / 'e2e_device.py')
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PreflightTests(unittest.TestCase):
    def test_read_only_checkout_inventory(self):
        with patch.object(MODULE.subprocess, 'run') as run:
            run.return_value.stdout = 'fixture\n'
            report = MODULE.preflight()
        self.assertEqual([call.args[0] for call in run.call_args_list],
                         [['git', 'rev-parse', 'HEAD'], ['git', 'status', '--porcelain']])
        self.assertFalse(report['allSoftwareVerified'])
        self.assertEqual(report['status'], 'blocked')
        self.assertTrue(all(item['status'] == 'blocked' and item['evidence'] == []
                            for item in report['scenarios']))
        self.assertEqual(len(report['scenarios']), len(MODULE.SCENARIOS))

    def test_authorization_is_separate_from_missing_acceptance_prerequisites(self):
        with patch.object(MODULE.subprocess, 'run') as run:
            run.return_value.stdout = 'fixture\n'
            report = MODULE.preflight()
        self.assertEqual(report['executionAuthorization'], 'scoped PLAN tests after verified prerequisites and resource ownership')
        self.assertIn('flash', report['actionsNotPerformed'])
        self.assertNotIn('flash', report['actionsForbidden'])
        self.assertNotIn('service install/enable', report['actionsForbidden'])
        self.assertIn('public publication', report['actionsForbidden'])
        self.assertFalse(report['productAccepted'])
        self.assertTrue(all('separate service/live authorization' not in item
                            for item in report['physicalLivePrerequisites']))

    def test_scenario_inputs_keep_missing_configuration_and_visual_authority_explicit(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as directory:
            with patch.object(MODULE.subprocess, 'run') as run:
                run.return_value.stdout = 'fixture\n'
                report = MODULE.preflight(Path(directory))
        self.assertEqual(report['missingInputs'], ['target_configuration'])
        scenarios = {item['id']: item for item in report['scenarios']}
        self.assertEqual(set(scenarios), set(MODULE.SCENARIOS))
        self.assertTrue(all(value['status'] == 'unverified'
                            for value in report['acceptanceInputs'].values()))
        for item in scenarios.values():
            self.assertIn('lcd_observation', item['requiredInputs'])
            self.assertTrue(set(item['requiredInputs']) <= set(report['acceptanceInputs']))
            self.assertTrue(item['nextAction'])
        self.assertIn('obs_ownership', scenarios['worxbend-obs-live-offline']['requiredInputs'])
        self.assertIn('owned_service', scenarios['synthetic-desktop-to-lcd']['requiredInputs'])
        self.assertIn('mobile_https', scenarios['admin-mobile-feed-reconnect']['requiredInputs'])
        self.assertIn('resource_soak', scenarios['lcd-resource-soak']['requiredInputs'])

    def test_new_report_private_and_never_overwritten(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as directory:
            target = Path(directory) / 'report.json'
            MODULE.write_report(target, {'status': 'blocked'})
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(target.read_text()), {'status': 'blocked'})
            with self.assertRaises(FileExistsError):
                MODULE.write_report(target, {'status': 'passed'})
            link = Path(directory) / 'link.json'
            link.symlink_to(target)
            with self.assertRaises(FileExistsError):
                MODULE.write_report(link, {})


if __name__ == '__main__':
    unittest.main()
