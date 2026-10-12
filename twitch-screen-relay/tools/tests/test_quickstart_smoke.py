"""Fail-closed quickstart acceptance/history correlation fixtures."""
import copy
import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).resolve().parents[1] / 'smoke_compose.py'
SPEC = importlib.util.spec_from_file_location('compose_quickstart_smoke', PATH)
assert SPEC is not None and SPEC.loader is not None
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


class QuickstartPublicationTests(unittest.TestCase):
    def setUp(self):
        self.payload = dict(type='info', title='Synthetic template quickstart', body='Generic notification', ttlMs=8000)
        self.accepted = dict(self.payload, id='synthetic-id', seq=1, relayEpoch='0000000000000001', origin='external')
        self.history = dict(notifications=[dict(self.accepted)])

    def test_exact_original_identity_and_content_pass(self):
        self.assertTrue(SMOKE.verify_publication(self.payload, self.accepted, self.history))

    def test_wrong_acceptance_content_hold_or_origin_fails(self):
        for key, value in [('type', 'warning'), ('title', 'Wrong'), ('body', 'Wrong'), ('ttlMs', 100), ('origin', 'twitch')]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                accepted = dict(self.accepted, **{key: value})
                SMOKE.verify_publication(self.payload, accepted, dict(notifications=[accepted]))

    def test_wrong_retained_identity_or_content_fails(self):
        for key, value in [('id', 'other'), ('seq', 2), ('relayEpoch', '0000000000000002'),
                           ('title', 'Wrong'), ('body', 'Wrong'), ('type', 'warning'), ('ttlMs', 100), ('origin', 'twitch')]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                history = copy.deepcopy(self.history)
                history['notifications'][0][key] = value
                SMOKE.verify_publication(self.payload, self.accepted, history)

    def test_missing_or_duplicate_original_identity_fails(self):
        for rows in ([], [dict(self.accepted), dict(self.accepted)]):
            with self.subTest(rows=rows), self.assertRaises(AssertionError):
                SMOKE.verify_publication(self.payload, self.accepted, dict(notifications=rows))


if __name__ == '__main__':
    unittest.main()
