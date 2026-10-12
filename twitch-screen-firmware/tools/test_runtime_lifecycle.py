"""Deterministic verifier fixtures; these are not runtime execution evidence."""
import unittest
from runtime_lifecycle import verify_callbacks, verify_report_phases


def report(phase, seq=1, epoch='0123456789abcdef', connection='first'):
    return dict(event='device_report_written', phase=phase, activeSeq=seq,
                activeEpoch=epoch, connection=connection)


class ReportPhaseTests(unittest.TestCase):
    def test_coalesced_intermediate_phases_are_optional(self):
        self.assertEqual(verify_report_phases([report(1), report(3), report(0)]), 1)
        self.assertEqual(verify_report_phases([report(2), report(2), report(0)]), 1)

    def test_hold_to_entrance_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'phase regressed'):
            verify_report_phases([report(2), report(1)])

    def test_exit_to_hold_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'phase regressed'):
            verify_report_phases([report(3), report(2)])

    def test_reconnect_does_not_reset_original_identity(self):
        with self.assertRaisesRegex(AssertionError, 'phase regressed'):
            verify_report_phases([report(3), report(2, connection='second')])
        self.assertEqual(verify_report_phases([report(2), report(3, connection='second')]), 1)

    def test_equal_sequence_in_new_epoch_is_distinct(self):
        self.assertEqual(verify_report_phases([report(3), report(1, epoch='0000000000000002')]), 2)

    def test_completed_identity_cannot_reappear(self):
        with self.assertRaisesRegex(AssertionError, 'reappeared'):
            verify_report_phases([report(2), report(0), report(2)])

    def test_new_card_can_skip_unobserved_completion(self):
        self.assertEqual(verify_report_phases([report(2), report(2, seq=2)]), 2)


class CallbackTests(unittest.TestCase):
    def setUp(self):
        self.records = [dict(event='started', epoch='a', seq=1, atMs=0, holdMs=100),
                        dict(event='card_lifecycle', epoch='a', seq=1, phase=2, atMs=350),
                        dict(event='card_lifecycle', epoch='a', seq=1, phase=3, atMs=450),
                        dict(event='card_lifecycle', epoch='a', seq=1, phase=0, atMs=730)]

    def test_ordered_original_hold_and_exit(self):
        self.assertEqual(verify_callbacks(self.records, (100,))['timestampedLifecyclesChecked'], 1)

    def test_missing_completion_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'Incomplete callback'):
            verify_callbacks(self.records[:-1], (100,))

    def test_shortened_hold_rejected(self):
        self.records[2]['atMs'] = 449
        with self.assertRaisesRegex(AssertionError, 'hold did not finish'):
            verify_callbacks(self.records, (100,))

    def test_changed_original_hold_rejected(self):
        self.records[0]['holdMs'] = 99
        with self.assertRaisesRegex(AssertionError, 'hold changed'):
            verify_callbacks(self.records, (100,))

    def test_reversed_callback_rejected(self):
        self.records[2]['phase'] = 2
        with self.assertRaisesRegex(AssertionError, 'phase regressed'):
            verify_callbacks(self.records, (100,))

    def test_paused_hold_exception_preserves_global_exclusion(self):
        self.records[2]['atMs'] = 61500
        self.records[3]['atMs'] = 61780
        successor = [dict(event='started', epoch='a', seq=2, atMs=61780, holdMs=100),
                     dict(event='card_lifecycle', epoch='a', seq=2, phase=2, atMs=62130),
                     dict(event='card_lifecycle', epoch='a', seq=2, phase=3, atMs=62230),
                     dict(event='card_lifecycle', epoch='a', seq=2, phase=0, atMs=62510)]
        rows = self.records + successor
        self.assertEqual(verify_callbacks(rows, (100, 100), hold_upper_ms={('a', 1): 63200})
                         ['timestampedLifecyclesChecked'], 2)
        self.records[3]['atMs'] += 1500
        with self.assertRaisesRegex(AssertionError, 'Overlapping callback'):
            verify_callbacks(rows, (100, 100), hold_upper_ms={('a', 1): 63200})

    def test_pause_exception_does_not_weaken_successor_hold(self):
        rows = self.records + [dict(event='started', epoch='a', seq=2, atMs=730, holdMs=100),
                               dict(event='card_lifecycle', epoch='a', seq=2, phase=2, atMs=1080),
                               dict(event='card_lifecycle', epoch='a', seq=2, phase=3, atMs=1179),
                               dict(event='card_lifecycle', epoch='a', seq=2, phase=0, atMs=1459)]
        with self.assertRaisesRegex(AssertionError, 'hold did not finish'):
            verify_callbacks(rows, (100, 100), hold_upper_ms={('a', 1): 63200})

    def test_unknown_pause_exception_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'no original lifecycle'):
            verify_callbacks(self.records, (100,), hold_upper_ms={('a', 2): 63200})

    def test_report_contradicting_callback_rejected(self):
        self.records.append(dict(event='device_report_written', activeEpoch='a', activeSeq=1,
                                 phase=2, observedDeviceMs=460))
        with self.assertRaisesRegex(AssertionError, 'contradicts callback'):
            verify_callbacks(self.records, (100,))


if __name__ == '__main__':
    unittest.main(verbosity=2)
