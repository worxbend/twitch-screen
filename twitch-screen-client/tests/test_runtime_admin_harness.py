"""Reject counterfeit admin producer correlations; fixtures are not runtime evidence."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import check_runtime_admin as admin


class AdminCorrelationTests(unittest.TestCase):
    def fixture(self):
        epoch = '0123456789abcdef'
        proof = {'transitions': [], 'captures': []}
        events = []
        for index in range(1, 4):
            payload = {'sourceId': 'synthetic', 'host': 'Synthetic host',
                       'cpuPercent': 12.34, 'memoryPercent': 56.78, 'freshnessMs': 10000,
                       'deadline': {'relayEpoch': epoch, 'monotonicMs': 50000}}
            accepted = {'relayEpoch': epoch, 'revision': index, 'status': 'fresh',
                        'reason': 'fresh', 'remainingFreshnessMs': 9000,
                        'receivedAt': '2026-10-12T10:00:00.000Z', 'observedAtSource': 'relay_receipt',
                        'data': {'sourceId': 'synthetic', 'host': 'Synthetic host',
                                 'cpuPercent': 12.34, 'memoryPercent': 56.78,
                                 'temperatureCelsius': None, 'observedAt': '2026-10-12T10:00:00.000Z',
                                 'freshnessMs': 10000}}
            proof['transitions'].append({'cpuAccepted': accepted, 'cpuSubmitted': payload,
                                         'cpuReadback': copy.deepcopy(accepted)})
            card = {'relayEpoch': epoch, 'seq': index, 'title': f'Synthetic {index}',
                    'body': '', 'origin': 'external'}
            proof['captures'].append({'card': card})
            events.append({'id': epoch + ':' + str(index),
                'snapshot': {'relayEpoch': epoch, 'sources': {'cpu': copy.deepcopy(accepted)},
                    'notifications': [{'identity': {'originalRelayEpoch': epoch, 'seq': index, 'origin': 'external'},
                                       'title': card['title'], 'body': '', 'sourceGeneration': 0, 'twitchEpisode': 0}]}})
        return proof, events

    def test_exact_three_measurements_and_external_cards(self):
        proof, events = self.fixture()
        result = admin.verify_correlations(proof, events)
        self.assertEqual(len(result['cpuCorrelations']), 3)
        self.assertEqual(len(result['cardCorrelations']), 3)

    def test_missing_changed_or_foreign_epoch_cpu_push_fails(self):
        for mutation in ('missing', 'measurement', 'revision', 'epoch', 'snapshot_epoch', 'submitted'):
            proof, events = self.fixture()
            if mutation == 'missing':
                events.pop()
            elif mutation == 'submitted':
                proof['transitions'][0]['cpuSubmitted']['cpuPercent'] = 99
            elif mutation == 'snapshot_epoch':
                events[0]['snapshot']['relayEpoch'] = 'fedcba9876543210'
            else:
                value = events[0]['snapshot']['sources']['cpu']
                if mutation == 'measurement':
                    value['data']['cpuPercent'] = 99
                elif mutation == 'revision':
                    value['revision'] = 9
                else:
                    value['relayEpoch'] = 'fedcba9876543210'
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                admin.verify_correlations(proof, events)

    def test_missing_changed_foreign_or_twitch_card_push_fails(self):
        for mutation in ('missing', 'title', 'body', 'epoch', 'origin', 'generation'):
            proof, events = self.fixture()
            row = events[0]['snapshot']['notifications'][0]
            if mutation == 'missing':
                events[0]['snapshot']['notifications'].clear()
            elif mutation == 'epoch':
                row['identity']['originalRelayEpoch'] = 'fedcba9876543210'
            elif mutation == 'origin':
                row['identity']['origin'] = 'twitch'
            elif mutation == 'generation':
                row['sourceGeneration'] = 1
            else:
                row[mutation] = 'changed synthetic field'
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                admin.verify_correlations(proof, events)

    def test_readback_cannot_rescue_changed_acceptance(self):
        proof, events = self.fixture()
        proof['transitions'][0]['cpuReadback']['revision'] = 99
        with self.assertRaises(AssertionError):
            admin.verify_correlations(proof, events)
