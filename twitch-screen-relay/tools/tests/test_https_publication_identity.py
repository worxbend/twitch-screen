"""HTTPS rejection checks must ignore only advancing remaining-expiry values."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('https_smoke', Path(__file__).parents[1] / 'smoke_private_https.py')
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PublicationIdentityTests(unittest.TestCase):
    def test_expiry_clock_advances_without_mutation(self):
        before = [{'seq': 1, 'title': 'Synthetic', 'remainingExpiryMs': 60000}]
        after = [{'seq': 1, 'title': 'Synthetic', 'remainingExpiryMs': 59900}]
        self.assertEqual(MODULE.publication_identity(before), MODULE.publication_identity(after))
        self.assertEqual(before[0]['remainingExpiryMs'], 60000)

    def test_new_or_changed_publication_is_not_hidden(self):
        before = [{'seq': 1, 'title': 'Synthetic', 'remainingExpiryMs': 60000}]
        for after in ([{'seq': 2, 'title': 'Synthetic'}], [{'seq': 1, 'title': 'Changed'}], [], before + before):
            self.assertNotEqual(MODULE.publication_identity(before), MODULE.publication_identity(after))


if __name__ == '__main__':
    unittest.main()
