"""Reject false producer-to-application evidence; no physical claims."""
import copy
import struct
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import check_runtime_device as device


class DeviceEvidenceTests(unittest.TestCase):
    def proof(self):
        accepted = {'relayEpoch': '0123456789abcdef', 'revision': 2,
                    'data': {'cpuPercent': 12.34, 'memoryPercent': 56.78}}
        return {'accepted': accepted,
                'payload': {'cpuPercent': 12.34, 'memoryPercent': 56.78,
                            'deadline': {'relayEpoch': accepted['relayEpoch'], 'monotonicMs': 50000}},
                'wire': {'epoch': accepted['relayEpoch'], 'connection': '0000000000000001',
                         'revision': 2, 'status': 1, 'presence': 1, 'cpu': 1234,
                         'memory': 5678, 'deadline': 49000},
                'view': {'event': 'application_view', 'epoch': accepted['relayEpoch'], 'connection': '0000000000000001',
                         'cpuRevision': 2, 'displayRevision': 4, 'baseScreen': 4, 'cpuVisible': True,
                         'weatherVisible': False, 'covered': False},
                'telemetry': {'event': 'application_telemetry', 'atMs': 123, 'epoch': accepted['relayEpoch'],
                              'connection': '0000000000000001', 'displayRevision': 4,
                              'cpuRevision': 2, 'cpuBasisPoints': 1234,
                              'memoryBasisPoints': 5678, 'cpuPresence': 1}}

    def test_missing_numeric_application_is_not_revision_evidence(self):
        proof = self.proof()
        del proof['telemetry']
        with self.assertRaises((AssertionError, KeyError)):
            device.verify_cpu_application(proof)

    def test_reject_changed_cpu_memory_presence_or_stale_numeric_application(self):
        for field, value in (('cpuBasisPoints', 10000), ('memoryBasisPoints', 10000),
                             ('cpuPresence', 0), ('cpuPresence', 3), ('cpuPresence', True),
                             ('cpuBasisPoints', 1234.0), ('memoryBasisPoints', 5678.0),
                             ('cpuRevision', 1), ('displayRevision', 3),
                             ('connection', '0000000000000002'), ('event', 'application_view')):
            proof = self.proof()
            proof['telemetry'][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(AssertionError):
                device.verify_cpu_application(proof)

    def test_wire_and_adapter_agreement_cannot_change_submitted_values(self):
        proof = self.proof()
        proof['payload']['cpuPercent'] = 99
        with self.assertRaises(AssertionError):
            device.verify_cpu_application(proof)

    def test_only_adjacent_same_view_numeric_observation_qualifies(self):
        proof = self.proof()
        records = [proof['view'], proof['telemetry']]
        result = device.cpu_application_proof(proof['accepted'], proof['payload'], proof['wire'], records)
        self.assertEqual(result['telemetry'], proof['telemetry'])
        self.assertEqual(result['observationIndex'], 0)
        for rows in ([proof['view']], [proof['telemetry'], proof['view']],
                     [proof['view'], {'event': 'other'}, proof['telemetry']]):
            with self.subTest(rows=rows):
                self.assertIsNone(device.cpu_application_proof(proof['accepted'], proof['payload'], proof['wire'], rows))

    def test_canonical_half_away_from_zero_values_reach_adapter(self):
        proof = self.proof()
        proof['payload'].update(cpuPercent=12.345, memoryPercent=56.785)
        proof['accepted']['data'].update(cpuPercent=12.35, memoryPercent=56.79)
        proof['wire'].update(cpu=1235, memory=5679)
        proof['telemetry'].update(cpuBasisPoints=1235, memoryBasisPoints=5679)
        self.assertTrue(device.verify_cpu_application(proof))

    def test_later_good_pair_cannot_excuse_a_matching_bad_adapter(self):
        proof = self.proof()
        changed = copy.deepcopy(proof['telemetry'])
        changed['cpuBasisPoints'] = 9999
        with self.assertRaises(AssertionError):
            device.cpu_application_proof(proof['accepted'], proof['payload'], proof['wire'],
                                         [proof['view'], changed, proof['view'], proof['telemetry']])

    def test_reject_missing_zero_foreign_or_malformed_observer_epoch(self):
        for component in ('view', 'telemetry'):
            for epoch in (None, '0000000000000000', 'fedcba9876543210', '0123456789ABCDEF', 1):
                proof = self.proof()
                if epoch is None:
                    del proof[component]['epoch']
                else:
                    proof[component]['epoch'] = epoch
                with self.subTest(component=component, epoch=epoch), self.assertRaises((AssertionError, KeyError)):
                    device.verify_cpu_application(proof)

    def test_mutually_matching_wrong_epochs_cannot_certify_application(self):
        for epoch in ('0000000000000000', 'fedcba9876543210'):
            proof = self.proof()
            proof['view']['epoch'] = proof['telemetry']['epoch'] = epoch
            with self.subTest(epoch=epoch), self.assertRaises(AssertionError):
                device.verify_cpu_application(proof)

    def test_foreign_epoch_same_connection_pair_cannot_rescue_missing_current_pair(self):
        proof = self.proof()
        foreign = copy.deepcopy(proof)
        foreign['view']['epoch'] = foreign['telemetry']['epoch'] = 'fedcba9876543210'
        self.assertIsNone(device.cpu_application_proof(proof['accepted'], proof['payload'], proof['wire'],
                                                     [foreign['view'], foreign['telemetry']]))
        result = device.cpu_application_proof(proof['accepted'], proof['payload'], proof['wire'],
            [foreign['view'], foreign['telemetry'], proof['view'], proof['telemetry']])
        self.assertEqual(result['observationIndex'], 2)

    def test_exact_measured_wire_and_application_revision(self):
        self.assertTrue(device.verify_cpu_application(self.proof()))

    def test_reject_changed_measurement_revision_epoch_presence_or_renewed_age(self):
        for field, value in (('cpu', 1235), ('memory', 5679), ('revision', 3),
                             ('epoch', '0000000000000000'), ('presence', 0),
                             ('deadline', 50001), ('status', 0)):
            proof = self.proof()
            proof['wire'][field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                device.verify_cpu_application(proof)

    def test_reject_hidden_stale_or_unrelated_application(self):
        for field, value in (('cpuVisible', False), ('weatherVisible', True),
                             ('baseScreen', 2), ('covered', True), ('cpuRevision', 3),
                             ('connection', '0000000000000002')):
            proof = self.proof()
            proof['view'][field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                device.verify_cpu_application(proof)

    def test_complete_capture_lifecycle_not_ack_or_summary_alone(self):
        proof = {'cards': [{'seq': 7, 'relayEpoch': '0123456789abcdef'},
                           {'seq': 8, 'relayEpoch': '0123456789abcdef'}],
                 'records': [{'event': 'started', 'seq': seq, 'epoch': '0123456789abcdef', 'origin': 0}
                             for seq in (7, 8)] +
                            [{'event': 'card_lifecycle', 'seq': seq, 'epoch': '0123456789abcdef', 'phase': phase}
                             for seq in (7, 8) for phase in (2, 3, 0)]}
        self.assertTrue(device.verify_cards(proof))
        for mutation in ('missing_completion', 'duplicate', 'wrong_epoch', 'twitch_origin'):
            changed = copy.deepcopy(proof)
            if mutation == 'missing_completion':
                changed['records'].pop()
            elif mutation == 'duplicate':
                changed['records'].append(copy.deepcopy(changed['records'][0]))
            elif mutation == 'wrong_epoch':
                changed['cards'][0]['relayEpoch'] = '0000000000000000'
            else:
                changed['records'][0]['origin'] = 1
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                device.verify_cards(changed)


class PublicationEvidenceTests(unittest.TestCase):
    def proof(self):
        result = {'cards': [], 'responses': [], 'frames': []}
        for index in range(2):
            card = {'id': f'synthetic-{index}', 'seq': index + 1, 'relayEpoch': '0123456789abcdef',
                    'title': f'synthetic {index}', 'body': '', 'origin': 'external'}
            result['cards'].append(card)
            payload = {'title': card['title'], 'body': '', 'deadline': {'relayEpoch': card['relayEpoch'], 'monotonicMs': 50000}}
            result['responses'].append({'method': 'POST', 'status': 200, 'payload': payload,
                                       'response': copy.deepcopy(card), 'fault': None if index == 0 else 'lose_response'})
            raw = bytearray(204)
            raw[3] = 0x2b
            struct.pack_into('<I', raw, 8, card['seq'])
            struct.pack_into('<Q', raw, 176, int(card['relayEpoch'], 16))
            struct.pack_into('<Q', raw, 192, 49000)
            result['frames'].append({'direction': 'relay', 'hex': raw.hex()})
        return result

    def test_exact_capture_post_history_and_original_wire_deadline(self):
        self.assertTrue(device.verify_publications(self.proof()))

    def test_reject_duplicate_unaged_changed_body_or_lost_identity(self):
        for mutation in ('duplicate', 'unaged', 'body', 'missing_event', 'deadline', 'origin'):
            proof = self.proof()
            if mutation == 'duplicate':
                proof['responses'].append(copy.deepcopy(proof['responses'][0]))
            elif mutation == 'unaged':
                del proof['responses'][0]['payload']['deadline']
            elif mutation == 'body':
                proof['responses'][0]['payload']['body'] = 'changed'
            elif mutation == 'missing_event':
                proof['frames'].pop()
            else:
                raw = bytearray.fromhex(proof['frames'][0]['hex'])
                if mutation == 'deadline':
                    struct.pack_into('<Q', raw, 192, 50001)
                else:
                    raw[200] = 1
                proof['frames'][0]['hex'] = raw.hex()
            with self.subTest(mutation=mutation), self.assertRaises((AssertionError, KeyError)):
                device.verify_publications(proof)
