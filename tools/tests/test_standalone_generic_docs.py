"""Standalone Generic documentation must not equate relay loss with Wi-Fi loss."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class StandaloneGenericDocsTests(unittest.TestCase):
    def test_documents_public_services_and_distinct_connectivity(self):
        text = (ROOT / 'docs/guides/standalone-generic.md').read_text()
        for required in ('No Wi-Fi', 'Checking internet', 'No internet',
                         'relay absent', 'https://picsum.photos/',
                         'https://open-meteo.com/', '50.45466', '30.5238',
                         'five-second', '96 × 96', 'no PSRAM',
                         'non-commercial', 'physical LCD'):
            with self.subTest(required=required):
                self.assertIn(required, text)
        self.assertIn('Cached photos alone never renew connectivity evidence', text)
        self.assertIn('separate from the relay', text)

    def test_discoverable_without_old_generic_orbit_claim(self):
        for name in ('docs/README.md', 'docs/guides/widgets.md',
                     'twitch-screen-firmware/README.md'):
            with self.subTest(path=name):
                text = (ROOT / name).read_text()
                self.assertIn('standalone-generic.md', text)
                self.assertNotIn('Generic orbit,', text)
        widgets = (ROOT / 'docs/guides/widgets.md').read_text()
        self.assertIn('weather text over every photo', widgets)
        guide = (ROOT / 'docs/guides/standalone-generic.md').read_text()
        self.assertIn('there is no\nseparate weather slide', guide)
        self.assertIn('?random=<new nonce>', guide)
        self.assertNotIn('rotates a bounded catalog', guide)
        service = (ROOT / 'twitch-screen-firmware/src/generic_service.cpp').read_text()
        self.assertIn('randomPhoto(nonce)', service)
        self.assertNotIn('PhotoPaths[', service)
        self.assertIn('built-in weather comes from the relay', widgets.lower())

    def test_actual_generic_environment_is_in_ci_and_workbench_commands(self):
        workflow = (ROOT / '.github/workflows/ci.yml').read_text()
        readme = (ROOT / 'twitch-screen-firmware/README.md').read_text()
        self.assertIn('pio test -e native-generic', workflow)
        self.assertIn('pio test -e native-generic', readme)

    def test_decoder_license_and_local_modifications_are_retained(self):
        vendor = ROOT / 'twitch-screen-firmware/lib/vendor/progressive_jpeg'
        self.assertIn('Copyright (c) 2017 Sean Barrett', (vendor / 'LICENSE').read_text())
        upstream = (vendor / 'README.txt').read_text()
        self.assertIn('2c980bb59875b0d32144a71867fbdebb2f77cd20', upstream)
        self.assertIn('progressive coefficients', upstream)
        self.assertIn('invalid marker', upstream)
        self.assertIn('Standalone Generic additions', (ROOT / 'THIRD_PARTY_NOTICES.md').read_text())


if __name__ == '__main__':
    unittest.main()
