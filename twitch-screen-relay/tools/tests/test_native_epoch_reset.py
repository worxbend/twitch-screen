"""Fixture-specific reset verdict regressions. Synthetic rows are not runtime evidence."""
import copy
import importlib.util
from pathlib import Path
import unittest
from typing import Any

SPEC = importlib.util.spec_from_file_location('reset_verdict', Path(__file__).resolve().parents[1] / 'check_native_epoch_reset.py')
assert SPEC is not None and SPEC.loader is not None
VERDICT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERDICT)


class ResetVerdictTests(unittest.TestCase):
    def fixture(self) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        old, new = '000000000000002a', '000000000000002b'
        ready = [{'event': 'desk_ready', 'epoch': old, 'connection': '1'},
                 {'event': 'desk_ready', 'epoch': new, 'connection': '2'}]
        rows: list[dict[str, Any]] = ready + [{'event': 'started', 'epoch': epoch, 'seq': seq, 'origin': 0, 'replay': False}
                        for epoch, seq in ((old, 1), (old, 2), (new, 1))]
        def entry(epoch, seq):
            return {'epoch': epoch, 'seq': seq, 'origin': 0}
        revision = {old: 0, new: 0}
        def report(epoch, processed, pending, phase, active_epoch, seq, remaining):
            revision[epoch] += 1
            rows.append({'event': 'device_report_written', 'epoch': epoch, 'connection': '1' if epoch == old else '2',
                         'revision': revision[epoch], 'observedDeviceMs': revision[epoch] * 200,
                         'observedRelayLowerMs': revision[epoch] * 100, 'pending': len(pending), 'pendingEntries': pending,
                         'processedSeq': processed, 'enqueuedSeq': processed, 'grantTotal': 8 - len(pending) + processed,
                         'expiredTotal': 0, 'suppressedTotal': 0, 'refusedTotal': 0,
                         'phase': phase, 'activeEpoch': active_epoch, 'activeSeq': seq, 'remainingMs': remaining,
                         'activeOrigin': 0, 'cpuRevision': 1, 'weatherRevision': 1,
                         'displayRevision': 1, 'baseScreen': 0, 'reason': 4})
        report(old, 0, [], 0, old, 0, 0)
        report(old, 2, [entry(old, 2)], 1, old, 1, 3500)
        report(old, 2, [entry(old, 2)], 2, old, 1, 3000)
        report(new, 0, [entry(old, 2)], 2, old, 1, 2000)
        report(new, 1, [entry(old, 2), entry(new, 1)], 3, old, 1, 200)
        for phase in (1, 2, 3):
            report(new, 1, [entry(new, 1)], phase, old, 2, 1000 - phase * 100)
        for phase in (1, 2, 3):
            report(new, 1, [], phase, new, 1, 1000 - phase * 100)
        report(new, 1, [], 0, new, 0, 0)
        rows += [{'event': 'application_view', 'connection': '1', 'baseScreen': 1, 'covered': True,
                  'cpuVisible': False, 'weatherVisible': False},
                 {'event': 'application_view', 'connection': '2', 'baseScreen': 0, 'covered': True,
                  'cpuVisible': False, 'weatherVisible': False},
                 {'event': 'summary', 'success': True, 'deskNegotiated': True, 'deskReadyObserved': True,
                  'greetings': 2, 'started': 3, 'holds': 3, 'exits': 3, 'completed': 3, 'admittedClassic': 0, 'lastClassicSeq': 0}]
        state = {'oldEpoch': old, 'newEpoch': new, 'intentPreserved': True, 'replayEmpty': True,
                 'latestSeq': 0, 'cpuMissing': True, 'weatherMissing': True}
        return rows, state

    def test_valid_fixture(self):
        rows, state = self.fixture()
        result = VERDICT.verify(rows, state)
        self.assertEqual(result['completedCards'], 3)
        self.assertFalse(result['automaticResetVerified'])
        self.assertFalse(result['productAccepted'])

    def test_rejects_manufactured_credit_and_old_cursor_baseline(self):
        rows, state = self.fixture()
        for field in ('grantTotal', 'processedSeq', 'enqueuedSeq'):
            altered = copy.deepcopy(rows)
            first = next(row for row in altered if row.get('event') == 'device_report_written' and row['epoch'] == state['newEpoch'])
            first[field] += 1
            with self.assertRaises(AssertionError, msg=field):
                VERDICT.verify(altered, state)

    def test_rejects_relabelled_retained_identity(self):
        rows, state = self.fixture()
        first = next(row for row in rows if row.get('event') == 'device_report_written' and row['epoch'] == state['newEpoch'])
        first['pendingEntries'][0]['epoch'] = state['newEpoch']
        with self.assertRaises(AssertionError):
            VERDICT.verify(rows, state)

    def test_rejects_renewed_active_remaining_time(self):
        rows, state = self.fixture()
        first = next(row for row in rows if row.get('event') == 'device_report_written' and row['epoch'] == state['newEpoch'])
        first['remainingMs'] = 3001
        with self.assertRaises(AssertionError):
            VERDICT.verify(rows, state)

    def test_rejects_telemetry_resurrection(self):
        rows, state = self.fixture()
        next(row for row in rows if row.get('event') == 'application_view' and row['connection'] == '2')['cpuVisible'] = True
        with self.assertRaises(AssertionError):
            VERDICT.verify(rows, state)

    def test_rejects_incomplete_lifecycle(self):
        rows, state = self.fixture()
        rows[-1]['completed'] = 2
        with self.assertRaises(AssertionError):
            VERDICT.verify(rows, state)


if __name__ == '__main__':
    unittest.main()
