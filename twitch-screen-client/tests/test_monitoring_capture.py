"""Synthetic verdict mutations for source-switch foreground capture evidence."""
import copy
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import check_runtime_monitoring as monitor


class CaptureSwitchTests(unittest.TestCase):
    def proof(self):
        payload = {'type': 'message', 'title': 'repair11 captured switch 0', 'body': '',
                   'ttlMs': 8000, 'expiresInMs': 60000,
                   'deadline': {'relayEpoch': '0123456789abcdef', 'monotonicMs': 100000}}
        card = {**payload, 'relayEpoch': '0123456789abcdef', 'seq': 7, 'id': 'synthetic',
                'origin': 'external', 'sourceGeneration': 0, 'twitchEpisode': 0}
        return {'reply': 42, 'title': payload['title'], 'pid': 123, 'expectedPid': 123,
                'configurationUnchanged': True, 'status': {'capture': 'available', 'captured': 1,
                'notification_accepted': 1}, 'card': card,
                'records': [{'method': 'POST', 'status': 200, 'payload': payload, 'response': copy.deepcopy(card)}]}

    def test_accept_exact_capture_and_publication(self):
        self.assertTrue(monitor.verify_capture_switch(self.proof()))

    def test_reject_duplicate_missing_changed_or_unaged_publication(self):
        for change in ('duplicate', 'missing', 'body', 'title', 'epoch', 'identity', 'origin', 'deadline'):
            proof = self.proof()
            record = proof['records'][0]
            if change == 'duplicate':
                proof['records'].append(copy.deepcopy(record))
            elif change == 'missing':
                proof['records'] = []
            elif change == 'deadline':
                del record['payload']['deadline']
            elif change == 'identity':
                record['response']['seq'] += 1
            elif change == 'epoch':
                record['response']['relayEpoch'] = '0123456789abcdee'
            elif change == 'origin':
                proof['card']['origin'] = 'twitch'
            else:
                record['payload'][change] = 'changed synthetic text'
            with self.subTest(change=change), self.assertRaises((AssertionError, KeyError)):
                monitor.verify_capture_switch(proof)

    def test_reject_restarted_child_denial_or_missing_ordinary_delivery(self):
        for field, value in (('pid', 124), ('reply', 0), ('configurationUnchanged', False)):
            proof = self.proof()
            proof[field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                monitor.verify_capture_switch(proof)
        proof = self.proof()
        proof['status']['capture'] = 'monitor_denied'
        with self.assertRaises(AssertionError):
            monitor.verify_capture_switch(proof)
