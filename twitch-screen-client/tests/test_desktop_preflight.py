"""Read-only supported-desktop/service prerequisites; never connect to the bus."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import check_desktop_preflight as desktop


class DesktopPreflightTests(unittest.TestCase):
    def inputs(self):
        return {'os': {'ID': 'ubuntu', 'VERSION_ID': '24.04'}, 'gnome': 'GNOME Shell 46.0',
                'python': (3, 12), 'environment': {'XDG_SESSION_TYPE': 'wayland',
                    'XDG_CURRENT_DESKTOP': 'ubuntu:GNOME', 'DBUS_SESSION_BUS_ADDRESS': 'unix:path=/run/user/1000/bus'},
                'dependencies': {'dbus-fast': '5.2.0', 'httpx': '0.28.1'},
                'runtimeDirectoryPrivate': True, 'busSocketPresent': True,
                'service': {'LoadState': 'not-found', 'ActiveState': 'inactive', 'SubState': 'dead'}}

    def test_qualified_target_is_only_ready_for_scoped_probe(self):
        result = desktop.assess(self.inputs())
        self.assertTrue(result['targetPrerequisitesReady'])
        self.assertFalse(result['desktopVerified'])
        self.assertFalse(result['serviceVerified'])
        self.assertFalse(result['productAccepted'])
        self.assertEqual(result['capturePermission'], 'not_probed')
        self.assertEqual(result['service']['ActiveState'], 'inactive')

    def test_missing_or_wrong_target_blocks_without_fallback(self):
        for key, value in (('os', {'ID': 'ubuntu', 'VERSION_ID': '26.04'}), ('gnome', 'GNOME Shell 50.0'),
                           ('python', (3, 11)), ('busSocketPresent', False),
                           ('runtimeDirectoryPrivate', False), ('dependencies', {'dbus-fast': '5.2.1'})):
            inputs = self.inputs()
            inputs[key] = value
            with self.subTest(key=key):
                result = desktop.assess(inputs)
                self.assertFalse(result['targetPrerequisitesReady'])
                self.assertTrue(result['blockers'])

    def test_no_bus_address_or_private_environment_in_output(self):
        inputs = self.inputs()
        inputs['environment']['SECRET_SYNTHETIC'] = 'DO NOT PRINT'
        result = desktop.assess(inputs)
        text = str(result)
        self.assertNotIn('DO NOT PRINT', text)
        self.assertNotIn('/run/user/1000/bus', text)
        self.assertNotIn('SECRET_SYNTHETIC', text)

    def test_unit_metadata_does_not_prove_service_operation(self):
        inputs = self.inputs()
        inputs['service'] = {'LoadState': 'loaded', 'ActiveState': 'active', 'SubState': 'running'}
        result = desktop.assess(inputs)
        self.assertEqual(result['service']['ActiveState'], 'active')
        self.assertFalse(result['serviceVerified'])
        self.assertIn('preserve_existing_service', result['requiredEvidence'])
