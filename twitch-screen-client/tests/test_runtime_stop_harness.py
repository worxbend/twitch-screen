"""Synthetic verdict mutations; actual foreground/JVM execution is a separate gate."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
SPEC = importlib.util.spec_from_file_location('runtime_stop', ROOT / 'tools/check_runtime_stop.py')
assert SPEC is not None and SPEC.loader is not None
STOP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STOP)


class StopVerdictTests(unittest.TestCase):
    def fixture(self):
        examples = json.loads((ROOT.parent / 'protocol/desk-v1-http-examples.json').read_text())['examples']
        cpu = copy.deepcopy(next(r['value'] for r in examples if r['name'] == 'cpu_fresh'))
        cpu['data']['memoryPercent'] = 50
        payload = {k: v for k, v in cpu['data'].items() if
                   k in ('sourceId', 'host', 'cpuPercent', 'memoryPercent', 'freshnessMs', 'observedAt') and v is not None}
        payload['deadline'] = {'relayEpoch': cpu['relayEpoch'], 'monotonicMs': 50000}
        card = {'title': 'Synthetic held stop', 'body': '', 'origin': 'external', 'remainingExpiryMs': 50000}
        return {'heldAtSignal': True, 'signalBoottimeMs': 2000, 'heldBoottimeMs': 1000,
                'stopElapsedMs': 100, 'exitCode': 0, 'ordinaryReplies': [42, 42], 'firstPid': 100,
                'stopped': {'pid': 100, 'capture': 'stopped', 'cpu': 'stopped', 'relay': 'stopped',
                            'notification_accepted': 0, 'notification_ambiguous': 0, 'notification_expired': 0},
                'title': card['title'], 'records': [
                    {'method': 'POST', 'status': 200, 'fault': 'hold_response', 'payload': {'title': card['title'], 'body': ''}},
                    {'method': 'PUT', 'status': 200, 'response': cpu, 'payload': payload}],
                'readback': copy.deepcopy(cpu), 'cpuAccepted': cpu, 'cpuSubmitted': payload,
                'restart': {'pid': 101, 'cpu_accepted': 1, 'captured': 0, 'notification_accepted': 0,
                            'notification_ambiguous': 0, 'pendingNotifications': 0, 'pendingNotificationBytes': 0},
                'restartExitCode': 0, 'configurationUnchanged': True,
                'historyBefore': [card], 'historyAfter': [{**card, 'remainingExpiryMs': 49000}]}

    def test_exact_held_commit_cpu_and_restart_proof(self):
        self.assertTrue(STOP.verify_stop(self.fixture()))

    def test_reject_release_timeout_force_or_counter_only_claim(self):
        for key, value in (('heldAtSignal', False), ('signalBoottimeMs', 4000), ('stopElapsedMs', 5000),
                           ('exitCode', -9), ('ordinaryReplies', [42]), ('configurationUnchanged', False)):
            proof = self.fixture()
            proof[key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                STOP.verify_stop(proof)

    def test_reject_duplicate_post_changed_measurement_or_revision(self):
        for kind in ('duplicate', 'measurement', 'revision', 'unrecorded', 'body'):
            proof = self.fixture()
            if kind == 'duplicate':
                proof['records'].append(proof['records'][0])
            elif kind == 'measurement':
                proof['cpuSubmitted']['cpuPercent'] = 99
            elif kind == 'revision':
                proof['readback']['revision'] += 1
            elif kind == 'unrecorded':
                proof['records'] = proof['records'][:1]
            else:
                proof['records'][0]['payload']['body'] = 'Synthetic body incorrectly forwarded'
            with self.subTest(kind=kind), self.assertRaises(AssertionError):
                STOP.verify_stop(proof)

    def test_reject_restart_spool_or_renewed_card_age(self):
        for kind in ('pid', 'pending', 'replay', 'age', 'accepted'):
            proof = self.fixture()
            if kind == 'pid':
                proof['restart']['pid'] = proof['firstPid']
            elif kind == 'pending':
                proof['restart']['pendingNotificationBytes'] = 1
            elif kind == 'replay':
                proof['historyAfter'].append(proof['historyAfter'][0])
            elif kind == 'age':
                proof['historyAfter'][0]['remainingExpiryMs'] = 50001
            else:
                proof['stopped']['notification_accepted'] = 1
            with self.subTest(kind=kind), self.assertRaises(AssertionError):
                STOP.verify_stop(proof)
