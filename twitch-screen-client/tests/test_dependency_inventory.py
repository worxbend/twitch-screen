"""Offline lock and wheel-notice inventory tests; all wheels here are fixtures."""
import hashlib
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('check_dependencies', Path(__file__).resolve().parents[1] / 'tools/check_dependencies.py')
assert spec is not None and spec.loader is not None
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class DependencyInventoryTests(unittest.TestCase):
    def fixture(self, root, metadata_version='1.0', notices=True):
        wheel = root / 'example-1.0-py3-none-any.whl'
        with zipfile.ZipFile(wheel, 'w') as archive:
            archive.writestr('example-1.0.dist-info/METADATA',
                             f'Metadata-Version: 2.4\nName: example\nVersion: {metadata_version}\nLicense-Expression: MIT\n')
            if notices:
                archive.writestr('example-1.0.dist-info/licenses/LICENSE', 'Synthetic fixture notice\n')
        digest = hashlib.sha256(wheel.read_bytes()).hexdigest()
        (root / 'requirements.lock').write_text(f'example==1.0 --hash=sha256:{digest}\n')
        (root / 'requirements-build.lock').write_text('# fixture: empty build closure\n')
        return wheel

    def test_exact_wheel_and_notice_hash_with_explicit_scope_limits(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='cd-') as directory:
            root = Path(directory)
            self.fixture(root)
            report = inventory.inspect(root, root)
            self.assertEqual(report['packageCount'], 1)
            self.assertFalse(report['rightsClearance'])
            self.assertFalse(report['securityAuditComplete'])
            package = report['packages'][0]
            self.assertEqual(package['licenseExpression'], 'MIT')
            self.assertFalse(package['noticeReviewRequired'])
            self.assertEqual(package['bundledNotices'][0]['sha256'],
                             hashlib.sha256(b'Synthetic fixture notice\n').hexdigest())

    def test_tampered_wheel_never_inherits_locked_metadata(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='cd-') as directory:
            root = Path(directory)
            wheel = self.fixture(root)
            with wheel.open('ab') as stream:
                stream.write(b'tampered')
            with self.assertRaisesRegex(ValueError, 'locked wheel unavailable'):
                inventory.inspect(root, root)

    def test_version_mismatch_fails_and_missing_notice_remains_pending(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='cd-') as directory:
            root = Path(directory)
            self.fixture(root, metadata_version='2.0')
            with self.assertRaisesRegex(ValueError, 'metadata differs'):
                inventory.inspect(root, root)
            self.fixture(root, notices=False)
            report = inventory.inspect(root, root)
            self.assertTrue(report['packages'][0]['noticeReviewRequired'])
