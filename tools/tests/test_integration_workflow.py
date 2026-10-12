"""Final integration CI must exercise the installed producer/browser seam."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class IntegrationWorkflowTests(unittest.TestCase):
    def test_frozen_installed_producer_browser_modes_are_not_optional(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('Verify installed producer to real Compose/native/SSE/Chromium', workflow)
        self.assertIn('docker image inspect --format', workflow)
        self.assertIn('tools/check_runtime_device.py --image-id', workflow)
        self.assertIn('--browser-executable "$TMPDIR/browser/chrome-headless-shell-linux64/chrome-headless-shell"', workflow)
        self.assertIn('--deny-monitor', workflow)
        self.assertIn('--malformed-response', workflow)
        self.assertIn('TSC_TEST_INSTALLED=1 PYTHONPATH=', workflow)

    def test_full_panel_browser_uses_actual_main_and_native(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('Verify real panel workflows on the final Main and native artifacts', workflow)
        self.assertIn('--slow-reader --browser-executable "$TMPDIR/browser/chrome-headless-shell-linux64/chrome-headless-shell"', workflow)


if __name__ == '__main__':
    unittest.main()
